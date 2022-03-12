ARG METABASE_VERSION=v0.42.2

FROM clojure:openjdk-11-tools-deps-slim-buster AS stg_base

# Reequirements for building the driver
RUN apt-get update && \
    apt-get install -y \
    curl \
    make \
    unzip \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Set our base workdir
WORKDIR /build

# We need to retrieve metabase source
# Due to how ARG and FROM interact, we need to re-use the same ARG
# Ref: https://docs.docker.com/engine/reference/builder/#understand-how-arg-and-from-interact
ARG METABASE_VERSION
RUN curl -Lo - https://github.com/metabase/metabase/archive/refs/tags/${METABASE_VERSION}.tar.gz | tar -xz \
    && mv metabase-* metabase

# Driver source goes inside metabase source so we can use their build scripts
WORKDIR /build/driver

# Copy our project assets over
COPY deps.edn ./
COPY src/ ./src
COPY resources/ ./resources

# Then prep our Metabase dependencies
# We need to build java deps and then spark-sql deps
# Ref: https://github.com/metabase/metabase/wiki/Migrating-from-Leiningen-to-tools.deps#preparing-dependencies
WORKDIR /build/metabase
RUN --mount=type=cache,target=/root/.m2/repository \
    clojure -X:deps prep

WORKDIR /build/metabase/modules/drivers
RUN --mount=type=cache,target=/root/.m2/repository \
    clojure -X:deps prep
WORKDIR /build/driver

# Now build the driver
FROM stg_base as stg_build
RUN --mount=type=cache,target=/root/.m2/repository \
    clojure -X:build

# We create an export stage to make it easy to export the driver
FROM scratch as stg_export
COPY --from=stg_build /build/driver/target/trino-jdbc.metabase-driver.jar /

