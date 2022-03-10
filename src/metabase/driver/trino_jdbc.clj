(ns metabase.driver.trino-jdbc
  "Trino JDBC driver. See https://trino.io/docs/current/ for complete dox."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [honeysql.core :as hsql]
            [honeysql.format :as hformat]
            [java-time :as t]
            [metabase.db.spec :as db.spec]
            [metabase.driver :as driver]
            [metabase.driver.presto-common :as presto-common]
            [metabase.driver.sql-jdbc.common :as sql-jdbc.common]
            [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
            [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
            [metabase.driver.sql-jdbc.execute.legacy-impl :as legacy]
            [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
            [metabase.driver.sql-jdbc.sync.describe-database :as sql-jdbc.describe-database]
            [metabase.driver.sql.parameters.substitution :as sql.params.substitution]
            [metabase.driver.sql.query-processor :as sql.qp]
            [metabase.query-processor.timezone :as qp.timezone]
            [metabase.util :as u]
            [metabase.util.date-2 :as u.date]
            [metabase.util.honeysql-extensions :as hx]
            [metabase.util.i18n :refer [trs]])
  (:import io.trino.jdbc.TrinoConnection
           com.mchange.v2.c3p0.C3P0ProxyConnection
           [java.sql Connection PreparedStatement ResultSet ResultSetMetaData Time Types]
           [java.time LocalDateTime LocalTime OffsetDateTime OffsetTime ZonedDateTime]
           java.time.format.DateTimeFormatter
           [java.time.temporal ChronoField Temporal]))

(driver/register! :trino-jdbc, :parent #{:presto-common :sql-jdbc ::legacy/use-legacy-classes-for-read-and-set})

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Custom HoneySQL Clause Impls                                          |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:private ^:const timestamp-with-time-zone-db-type "timestamp with time zone")

(defmethod sql.qp/->honeysql [:trino-jdbc :log]
  [driver [_ field]]
  ;; recent Trino versions have a `log10` function (not `log`)
  (hsql/call :log10 (sql.qp/->honeysql driver field)))

(defmethod sql.qp/->honeysql [:trino-jdbc :count-where]
  [driver [_ pred]]
  ;; Trino will use the precision given here in the final expression, which chops off digits
  ;; need to explicitly provide two digits after the decimal
  (sql.qp/->honeysql driver [:sum-where 1.00M pred]))

(defmethod sql.qp/->honeysql [:trino-jdbc :time]
  [_ [_ t]]
  ;; make time in UTC to avoid any interpretation by Trino in the connection (i.e. report) time zone
  (hx/cast "time with time zone" (u.date/format-sql (t/offset-time (t/local-time t) 0))))

(defmethod sql.qp/->honeysql [:trino-jdbc ZonedDateTime]
  [_ ^ZonedDateTime t]
  ;; use the Trino cast to `timestamp with time zone` operation to interpret in the correct TZ, regardless of
  ;; connection zone
  (hx/cast timestamp-with-time-zone-db-type (u.date/format-sql t)))

(defmethod sql.qp/->honeysql [:trino-jdbc OffsetDateTime]
  [_ ^OffsetDateTime t]
  ;; use the Trino cast to `timestamp with time zone` operation to interpret in the correct TZ, regardless of
  ;; connection zone
  (hx/cast timestamp-with-time-zone-db-type (u.date/format-sql t)))

(defrecord AtTimeZone
  ;; record type to support applying Trino's `AT TIME ZONE` operator to an expression
  [expr zone]
  hformat/ToSql
  (to-sql [_]
    (format "%s AT TIME ZONE %s"
      (hformat/to-sql expr)
      (hformat/to-sql (hx/literal zone)))))

(defn- in-report-zone
  "Returns a HoneySQL form to interpret the `expr` (a temporal value) in the current report time zone, via Trino's
  `AT TIME ZONE` operator. See https://trino.io/docs/current/functions/datetime.html"
  [expr]
  (let [report-zone (qp.timezone/report-timezone-id-if-supported :trino-jdbc)
        ;; if the expression itself has type info, use that, or else use a parent expression's type info if defined
        type-info   (hx/type-info expr)
        db-type     (hx/type-info->db-type type-info)]
    (if (and ;; AT TIME ZONE is only valid on these Trino types; if applied to something else (ex: `date`), then
             ;; an error will be thrown by the query analyzer
             (contains? #{"timestamp" "timestamp with time zone" "time" "time with time zone"} db-type)
             ;; if one has already been set, don't do so again
             (not (::in-report-zone? (meta expr)))
             report-zone)
      (-> (hx/with-database-type-info (->AtTimeZone expr report-zone) timestamp-with-time-zone-db-type)
        (vary-meta assoc ::in-report-zone? true))
      expr)))

;; most date extraction and bucketing functions need to account for report timezone

(defmethod sql.qp/date [:trino-jdbc :default]
  [_ _ expr]
  expr)

(defmethod sql.qp/date [:trino-jdbc :minute]
  [_ _ expr]
  (hsql/call :date_trunc (hx/literal :minute) (in-report-zone expr)))

(defmethod sql.qp/date [:trino-jdbc :minute-of-hour]
  [_ _ expr]
  (hsql/call :minute (in-report-zone expr)))

(defmethod sql.qp/date [:trino-jdbc :hour]
  [_ _ expr]
  (hsql/call :date_trunc (hx/literal :hour) (in-report-zone expr)))

(defmethod sql.qp/date [:trino-jdbc :hour-of-day]
  [_ _ expr]
  (hsql/call :hour (in-report-zone expr)))

(defmethod sql.qp/date [:trino-jdbc :day]
  [_ _ expr]
  (hsql/call :date (in-report-zone expr)))

(defmethod sql.qp/date [:trino-jdbc :day-of-week]
  [_ _ expr]
  (sql.qp/adjust-day-of-week :trino-jdbc (hsql/call :day_of_week (in-report-zone expr))))

(defmethod sql.qp/date [:trino-jdbc :day-of-month]
  [_ _ expr]
  (hsql/call :day (in-report-zone expr)))

(defmethod sql.qp/date [:trino-jdbc :day-of-year]
  [_ _ expr]
  (hsql/call :day_of_year (in-report-zone expr)))

(defmethod sql.qp/date [:trino-jdbc :week]
  [_ _ expr]
  (sql.qp/adjust-start-of-week :trino-jdbc (partial hsql/call :date_trunc (hx/literal :week)) (in-report-zone expr)))

(defmethod sql.qp/date [:trino-jdbc :month]
  [_ _ expr]
  (hsql/call :date_trunc (hx/literal :month) (in-report-zone expr)))

(defmethod sql.qp/date [:trino-jdbc :month-of-year]
  [_ _ expr]
  (hsql/call :month (in-report-zone expr)))

(defmethod sql.qp/date [:trino-jdbc :quarter]
  [_ _ expr]
  (hsql/call :date_trunc (hx/literal :quarter) (in-report-zone expr)))

(defmethod sql.qp/date [:trino-jdbc :quarter-of-year]
  [_ _ expr]
  (hsql/call :quarter (in-report-zone expr)))

(defmethod sql.qp/date [:trino-jdbc :year]
  [_ _ expr]
  (hsql/call :date_trunc (hx/literal :year) (in-report-zone expr)))

(defmethod sql.qp/unix-timestamp->honeysql [:trino-jdbc :seconds]
  [_ _ expr]
  (let [report-zone (qp.timezone/report-timezone-id-if-supported :trino-jdbc)]
    (hsql/call :from_unixtime expr (hx/literal (or report-zone "UTC")))))

(defmethod sql.qp/unix-timestamp->honeysql [:trino-jdbc :milliseconds]
  [_ _ expr]
  ;; from_unixtime doesn't support milliseconds directly, but we can add them back in
  (let [report-zone (qp.timezone/report-timezone-id-if-supported :trino-jdbc)
        millis      (hsql/call (u/qualified-name ::mod) expr 1000)]
    (hsql/call :date_add
               (hx/literal "millisecond")
               millis
               (hsql/call :from_unixtime (hsql/call :/ expr 1000) (hx/literal (or report-zone "UTC"))))))

(defmethod sql.qp/unix-timestamp->honeysql [:trino-jdbc :microseconds]
  [driver _ expr]
  ;; Trino can't even represent microseconds, so convert to millis and call that version
  (sql.qp/unix-timestamp->honeysql driver :milliseconds (hsql/call :/ expr 1000)))

(defmethod sql.qp/current-datetime-honeysql-form :trino-jdbc
  [_]
  ;; the current_timestamp in Trino returns a `timestamp with time zone`, so this needs to be overridden
  (hx/with-type-info :%now {::hx/database-type timestamp-with-time-zone-db-type}))

(defmethod hformat/fn-handler (u/qualified-name ::mod)
  [_ x y]
  ;; Trino mod is a function like mod(x, y) rather than an operator like x mod y
  (format "mod(%s, %s)" (hformat/to-sql x) (hformat/to-sql y)))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                  Connectivity                                                  |
;;; +----------------------------------------------------------------------------------------------------------------+

;;; Kerberos related definitions
(def ^:private ^:const kerb-props->url-param-names
  {:kerberos-principal "KerberosPrincipal"
   :kerberos-remote-service-name "KerberosRemoteServiceName"
   :kerberos-use-canonical-hostname "KerberosUseCanonicalHostname"
   :kerberos-credential-cache-path "KerberosCredentialCachePath"
   :kerberos-keytab-path "KerberosKeytabPath"
   :kerberos-service-principal-pattern "KerberosServicePrincipalPattern"
   :kerberos-config-path "KerberosConfigPath"})

(defn- details->kerberos-url-params [details]
  (let [remove-blank-vals (fn [m] (into {} (remove (comp str/blank? val) m)))
        ks                (keys kerb-props->url-param-names)]
    (-> (select-keys details ks)
      remove-blank-vals
      (set/rename-keys kerb-props->url-param-names))))

(defn- prepare-addl-opts [{:keys [SSL kerberos additional-options] :as details}]
  (let [det (if kerberos
              (if-not SSL
                (throw (ex-info (trs "SSL must be enabled to use Kerberos authentication")
                                {:db-details details}))
                (update details
                        :additional-options
                        str
                        ;; add separator if there are already additional-options
                        (when-not (str/blank? additional-options) "&")
                        ;; convert Kerberos options map to URL string
                        (sql-jdbc.common/additional-opts->string :url (details->kerberos-url-params details))))
              details)]
    ;; in any case, remove the standalone Kerberos properties from details map
    (apply dissoc (cons det (keys kerb-props->url-param-names)))))

(defn- db-name
  "Creates a \"DB name\" for the given catalog `c` and (optional) schema `s`.  If both are specified, a slash is
  used to separate them.  See examples at:
  https://trino.io/docs/current/installation/jdbc.html#connecting"
  [c s]
  (cond
    (str/blank? c)
    ""

    (str/blank? s)
    c

    :else
    (str c "/" s)))

(defn- jdbc-spec
  "Creates a spec for `clojure.java.jdbc` to use for connecting to Trino via JDBC, from the given `opts`."
  [{:keys [host port catalog schema]
    :or   {host "localhost", port 5432, catalog ""}
    :as   details}]
  (-> details
      (merge {:classname   "io.trino.jdbc.TrinoDriver"
              :subprotocol "trino"
              :subname     (db.spec/make-subname host port (db-name catalog schema))})
      prepare-addl-opts
      (dissoc :host :port :db :catalog :schema :tunnel-enabled :engine :kerberos)
    sql-jdbc.common/handle-additional-options))

(defn- str->bool [v]
  (if (string? v)
    (Boolean/parseBoolean v)
    v))

(defmethod sql-jdbc.conn/connection-details->spec :trino-jdbc
  [_ details-map]
  (let [props (-> details-map
                (update :port (fn [port]
                                (if (string? port)
                                  (Integer/parseInt port)
                                  port)))
                (update :ssl str->bool)
                (update :kerberos str->bool)
                (assoc :SSL (:ssl details-map))
                ;; remove any Metabase specific properties that are not recognized by the Trino JDBC driver, which is
                ;; very picky about properties (throwing an error if any are unrecognized)
                ;; all valid properties can be found in the JDBC Driver source here:
                ;; https://github.com/trinodb/trino/blob/master/client/trino-jdbc/src/main/java/io/trino/jdbc/ConnectionProperties.java
                (select-keys (concat
                              [:host :port :catalog :schema :additional-options ; needed for `jdbc-spec`
                               ;; JDBC driver specific properties
                               :kerberos ; we need our boolean property indicating if Kerberos is enabled
                                         ; but the rest of them come from `kerb-props->url-param-names` (below)
                               :user :password :socksProxy :httpProxy :applicationNamePrefix :disableCompression :SSL
                               :SSLKeyStorePath :SSLKeyStorePassword :SSLTrustStorePath :SSLTrustStorePassword
                               :accessToken :extraCredentials :sessionProperties :protocols :queryInterceptors]
                              (keys kerb-props->url-param-names))))]
    (jdbc-spec props)))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                      Sync                                                      |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmethod sql-jdbc.sync/database-type->base-type :trino-jdbc
  [_ field-type]
  (presto-common/presto-type->base-type field-type))

(defn- have-select-privilege?
  "Checks whether the connected user has permission to select from the given `table-name`, in the given `schema`.
  Adapted from the legacy Trino driver implementation."
  [driver conn schema table-name]
  (try
   (let [sql (sql-jdbc.describe-database/simple-select-probe-query driver schema table-name)]
        ;; if the query completes without throwing an Exception, we can SELECT from this table
        (jdbc/reducible-query {:connection conn} sql)
        true)
   (catch Throwable _
     false)))

(defn- describe-schema
  "Gets a set of maps for all tables in the given `catalog` and `schema`. Adapted from the legacy Trino driver
  implementation."
  [driver conn catalog schema]
  (let [sql (presto-common/describe-schema-sql driver catalog schema)]
    (log/trace (trs "Running statement in describe-schema: {0}" sql))
    (into #{} (comp (filter (fn [{table-name :table}]
                                (have-select-privilege? driver conn schema table-name)))
                    (map (fn [{table-name :table}]
                             {:name        table-name
                              :schema      schema})))
              (jdbc/reducible-query {:connection conn} sql))))

(defn- all-schemas
  "Gets a set of maps for all tables in all schemas in the given `catalog`. Adapted from the legacy Trino driver
  implementation."
  [driver conn catalog]
  (let [sql (presto-common/describe-catalog-sql driver catalog)]
    (log/trace (trs "Running statement in all-schemas: {0}" sql))
    (into []
          (map (fn [{:keys [schema] :as full}]
                 (when-not (contains? presto-common/excluded-schemas schema)
                   (describe-schema driver conn catalog schema))))
          (jdbc/reducible-query {:connection conn} sql))))

(defmethod driver/describe-database :trino-jdbc
  [driver {{:keys [catalog schema] :as details} :details :as database}]
  (with-open [conn (-> (sql-jdbc.conn/db->pooled-connection-spec database)
                       jdbc/get-connection)]
    (let [schemas (if schema #{(describe-schema driver conn catalog schema)}
                             (all-schemas driver conn catalog))]
      {:tables (reduce set/union schemas)})))

(defmethod driver/describe-table :trino-jdbc
  [driver {{:keys [catalog] :as details} :details :as database} {schema :schema, table-name :name}]
  (with-open [conn (-> (sql-jdbc.conn/db->pooled-connection-spec database)
                     jdbc/get-connection)]
    (let [sql (presto-common/describe-table-sql driver catalog schema table-name)]
      (log/trace (trs "Running statement in describe-table: {0}" sql))
      {:schema schema
       :name   table-name
       :fields (into
                 #{}
                 (map-indexed (fn [idx {:keys [column type] :as col}]
                                {:name column
                                 :database-type type
                                 :base-type         (presto-common/presto-type->base-type type)
                                 :database-position idx}))
                 (jdbc/reducible-query {:connection conn} sql))})))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                            sql-jdbc implementations                                            |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmethod sql-jdbc.execute/prepared-statement :trino-jdbc
  [driver ^Connection conn ^String sql params]
  ;; with Trino JDBC driver, result set holdability must be HOLD_CURSORS_OVER_COMMIT
  ;; defining this method simply to omit setting the holdability
  (let [stmt (.prepareStatement conn
                                sql
                                ResultSet/TYPE_FORWARD_ONLY
                                ResultSet/CONCUR_READ_ONLY)]
       (try
         (try
           (.setFetchDirection stmt ResultSet/FETCH_FORWARD)
           (catch Throwable e
             (log/debug e (trs "Error setting prepared statement fetch direction to FETCH_FORWARD"))))
         (sql-jdbc.execute/set-parameters! driver stmt params)
         stmt
         (catch Throwable e
           (.close stmt)
           (throw e)))))


(defmethod sql-jdbc.execute/statement :trino-jdbc
  [_ ^Connection conn]
  ;; and similarly for statement (do not set holdability)
  (let [stmt (.createStatement conn
                               ResultSet/TYPE_FORWARD_ONLY
                               ResultSet/CONCUR_READ_ONLY)]
    (try
      (.setFetchDirection stmt ResultSet/FETCH_FORWARD)
      (catch Throwable e
        (log/debug e (trs "Error setting statement fetch direction to FETCH_FORWARD"))))
    stmt))

(defn- ^TrinoConnection pooled-conn->trino-conn
  "Unwraps the C3P0 `pooled-conn` and returns the underlying `TrinoConnection` it holds."
  [^C3P0ProxyConnection pooled-conn]
  (.unwrap pooled-conn TrinoConnection))

(defmethod sql-jdbc.execute/connection-with-timezone :trino-jdbc
  [driver database ^String timezone-id]
  ;; Trino supports setting the session timezone via a `TrinoConnection` instance method. Under the covers,
  ;; this is equivalent to the `X-Trino-Time-Zone` header in the HTTP request (i.e. the `:trino` driver)
  (let [conn            (.getConnection (sql-jdbc.execute/datasource-with-diagnostic-info! driver database))
        underlying-conn (pooled-conn->trino-conn conn)]
    (try
      (sql-jdbc.execute/set-best-transaction-level! driver conn)
      (when-not (str/blank? timezone-id)
        ;; set session time zone if defined
        (.setTimeZoneId underlying-conn timezone-id))
      (try
        (.setReadOnly conn true)
        (catch Throwable e
          (log/debug e (trs "Error setting connection to read-only"))))
      ;; as with statement and prepared-statement, cannot set holdability on the connection level
      conn
      (catch Throwable e
        (.close conn)
        (throw e)))))

(defn- date-time->substitution [ts-str]
  (sql.params.substitution/make-stmt-subs "from_iso8601_timestamp(?)" [ts-str]))

(defmethod sql.params.substitution/->prepared-substitution [:trino-jdbc ZonedDateTime]
  [_ ^ZonedDateTime t]
  ;; for native query parameter substitution, in order to not conflict with the `TrinoConnection` session time zone
  ;; (which was set via report time zone), it is necessary to use the `from_iso8601_timestamp` function on the string
  ;; representation of the `ZonedDateTime` instance, but converted to the report time zone
  #_(date-time->substitution (.format (t/offset-date-time (t/local-date-time t) (t/zone-offset 0)) DateTimeFormatter/ISO_OFFSET_DATE_TIME))
  (let [report-zone       (qp.timezone/report-timezone-id-if-supported :trino-jdbc)
        ^ZonedDateTime ts (if (str/blank? report-zone) t (t/with-zone-same-instant t (t/zone-id report-zone)))]
    ;; the `from_iso8601_timestamp` only accepts timestamps with an offset (not a zone ID), so only format with offset
    (date-time->substitution (.format ts DateTimeFormatter/ISO_OFFSET_DATE_TIME))))

(defmethod sql.params.substitution/->prepared-substitution [:trino-jdbc LocalDateTime]
  [_ ^LocalDateTime t]
  ;; similar to above implementation, but for `LocalDateTime`
  ;; when Trino parses this, it will account for session (report) time zone
  (date-time->substitution (.format t DateTimeFormatter/ISO_LOCAL_DATE_TIME)))

(defmethod sql.params.substitution/->prepared-substitution [:trino-jdbc OffsetDateTime]
  [_ ^OffsetDateTime t]
  ;; similar to above implementation, but for `ZonedDateTime`
  ;; when Trino parses this, it will account for session (report) time zone
  (date-time->substitution (.format t DateTimeFormatter/ISO_OFFSET_DATE_TIME)))

(defn- set-time-param
  "Converts the given instance of `java.time.temporal`, assumed to be a time (either `LocalTime` or `OffsetTime`)
  into a `java.sql.Time`, including milliseconds, and sets the result as a parameter of the `PreparedStatement` `ps`
  at index `i`."
  [^PreparedStatement ps ^Integer i ^Temporal t]
  ;; for some reason, `java-time` can't handle passing millis to java.sql.Time, so this is the most straightforward way
  ;; I could find to do it
  ;; reported as https://github.com/dm3/clojure.java-time/issues/74
  (let [millis-of-day (.get t ChronoField/MILLI_OF_DAY)]
    (.setTime ps i (Time. millis-of-day))))

(defmethod sql-jdbc.execute/set-parameter [:trino-jdbc OffsetTime]
  [_ ^PreparedStatement ps ^Integer i t]
  ;; necessary because `TrinoPreparedStatement` does not implement the `setTime` overload having the final `Calendar`
  ;; param
  (let [adjusted-tz (t/with-offset-same-instant t (t/zone-offset 0))]
    (set-time-param ps i adjusted-tz)))

(defmethod sql-jdbc.execute/set-parameter [:trino-jdbc LocalTime]
  [_ ^PreparedStatement ps ^Integer i t]
  ;; same rationale as above
  (set-time-param ps i t))

(defn- ^LocalTime sql-time->local-time
  "Converts the given instance of `java.sql.Time` into a `java.time.LocalTime`, including milliseconds. Needed for
  similar reasons as `set-time-param` above."
  [^Time sql-time]
  ;; Java 11 adds a simpler `ofInstant` method, but since we need to run on JDK 8, we can't use it
  ;; https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/time/LocalTime.html#ofInstant(java.time.Instant,java.time.ZoneId)
  (let [^LocalTime lt (t/local-time sql-time)
        ^Long millis  (mod (.getTime sql-time) 1000)]
    (.with lt ChronoField/MILLI_OF_SECOND millis)))

(defmethod sql-jdbc.execute/read-column-thunk [:trino-jdbc Types/TIME]
  [_ ^ResultSet rs ^ResultSetMetaData rs-meta ^Integer i]
  (let [type-name  (.getColumnTypeName rs-meta i)
        base-type  (presto-common/presto-type->base-type type-name)
        with-tz?   (isa? base-type :type/TimeWithTZ)]
    (fn []
      (let [local-time (-> (.getTime rs i)
                           sql-time->local-time)]
        ;; for both `time` and `time with time zone`, the JDBC type reported by the driver is `Types/TIME`, hence
        ;; we also need to check the column type name to differentiate between them here
        (if with-tz?
          ;; even though this value is a `LocalTime`, the base-type is time with time zone, so we need to shift it back to
          ;; the UTC (0) offset
          (t/offset-time
            local-time
            (t/zone-offset 0))
          ;; else the base-type is time without time zone, so just return the local-time value
          local-time)))))

(defn- ^TrinoConnection rs->trino-conn
  "Returns the `TrinoConnection` associated with the given `ResultSet` `rs`."
  [^ResultSet rs]
  (-> (.. rs getStatement getConnection)
      pooled-conn->trino-conn))

(defmethod sql-jdbc.execute/read-column-thunk [:trino-jdbc Types/TIMESTAMP]
  [_ ^ResultSet rset ^ResultSetMetaData rsmeta ^Integer i]
  (let [zone     (.getTimeZoneId (rs->trino-conn rset))]
    (fn []
      (when-let [s (.getString rset i)]
        (when-let [t (u.date/parse s)]
          (cond
            (or (instance? OffsetDateTime t)
              (instance? ZonedDateTime t))
            (-> (t/offset-date-time t)
              ;; tests are expecting this to be in the UTC offset, so convert to that
              (t/with-offset-same-instant (t/zone-offset 0)))

            ;; trino "helpfully" returns local results already adjusted to session time zone offset for us, e.g.
            ;; '2021-06-15T00:00:00' becomes '2021-06-15T07:00:00' if the session timezone is US/Pacific. Undo the
            ;; madness and convert back to UTC
            zone
            (-> (t/zoned-date-time t zone)
              (u.date/with-time-zone-same-instant "UTC")
              t/local-date-time)
            :else
            t))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                           Other Driver Method Impls                                            |
;;; +----------------------------------------------------------------------------------------------------------------+

(prefer-method driver/supports? [:presto-common :set-timezone] [:sql-jdbc :set-timezone])