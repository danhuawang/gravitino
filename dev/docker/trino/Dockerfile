#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#  http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

# ==============================================================================
# Trino with Gravitino Connector - Red Hat UBI 10 Certified Image
#
# Multi-stage build (following datastrato/Trino fork pattern):
#   Stage 1 (jvmkill)    - Get jvmkill library for OOM protection
#   Stage 2 (jdk)        - Download Adoptium JDK 25
#   Stage 3 (packages)   - Download Trino tarball + install system packages
#   Stage 4 (runtime)    - UBI 10 micro final image
#
# Tarball source: datastrato/Trino GitHub Release (gravitino-478)
# JDK: Adoptium Temurin jdk-25+36 (same as official trinodb/trino:478)
#
# Red Hat Certification Compliance:
#   - Based on UBI 10 (registry.access.redhat.com)
#   - Required LABELs present
#   - /licenses directory with all required license files
#   - Runs as non-root user (UID 1000, GID 0)
#   - OpenShift arbitrary UID compatible
# ==============================================================================

<<<<<<< HEAD
ARG TRINO_VERSION=478
ARG JDK_VERSION=jdk-25+36
=======
COPY --chown=trino:trino packages/trino/conf /etc/trino
COPY --chown=trino:trino packages/gravitino-trino-connector/mysql-connector-java-8.0.27.jar /usr/lib/trino/plugin/iceberg/
COPY --chown=trino:trino packages/gravitino-trino-connector/postgresql-42.7.11.jar /usr/lib/trino/plugin/iceberg/
>>>>>>> upstream/branch-1.3

# ==============================================================================
# Stage 1: Get jvmkill (OOM protection — kills JVM on OutOfMemoryError)
# ==============================================================================
FROM ghcr.io/airlift/jvmkill:latest AS jvmkill

# ==============================================================================
# Stage 2: Download Adoptium JDK 25
# ==============================================================================
FROM registry.access.redhat.com/ubi10/ubi-minimal:10.1 AS jdk-download

ARG JDK_VERSION
ARG TARGETARCH
ENV JAVA_HOME="/usr/lib/jvm/${JDK_VERSION}"

RUN set -xeuo pipefail && \
    microdnf install -y --setopt=install_weak_deps=0 --nodocs tar gzip && \
    microdnf clean all && \
    mkdir -p "${JAVA_HOME}" && \
    case "${TARGETARCH:-amd64}" in \
      amd64|x86_64) ARCH="x64" ;; \
      arm64|aarch64) ARCH="aarch64" ;; \
      *) echo "Unsupported: ${TARGETARCH}" && exit 1 ;; \
    esac && \
    echo "Downloading JDK from Adoptium: ${JDK_VERSION} for ${ARCH}" && \
    curl --retry 3 --retry-delay 5 -#LfS \
      "https://api.adoptium.net/v3/binary/version/${JDK_VERSION}/linux/${ARCH}/jdk/hotspot/normal/eclipse?project=jdk" \
      | tar -zx --strip 1 -C "${JAVA_HOME}"

# ==============================================================================
# Stage 3: Prepare Trino server + system packages overlay
# Trino tarball is pre-downloaded to build context (private repo requires auth)
# ==============================================================================
FROM registry.access.redhat.com/ubi10/ubi:10.1 AS packages

ARG TRINO_VERSION

# Copy pre-downloaded Trino tarball and extract
COPY packages/trino-server-${TRINO_VERSION}.tar.gz /tmp/trino-server.tar.gz
RUN set -xeuo pipefail && \
    tar -xzf /tmp/trino-server.tar.gz -C /tmp/ && \
    rm -f /tmp/trino-server.tar.gz

# Install system packages into overlay directory (for ubi-micro final stage)
# This approach keeps the final image minimal while providing necessary binaries
RUN set -xeuo pipefail && \
    mkdir -p /tmp/overlay/usr/libexec/ && \
    touch /tmp/overlay/usr/libexec/grepconf.sh && \
    chmod +x /tmp/overlay/usr/libexec/grepconf.sh && \
    dnf update -y && \
    dnf install --installroot /tmp/overlay --setopt install_weak_deps=false --nodocs -y \
      less \
      libstdc++ \
      curl-minimal grep \
      zlib \
      shadow-utils \
      tar && \
    rm -rf /tmp/overlay/var/cache/*

# ==============================================================================
# Stage 4: Final production image (UBI 10 micro — smallest possible)
# ==============================================================================
FROM registry.access.redhat.com/ubi10/ubi-micro:10.1

ARG TRINO_VERSION
ARG JDK_VERSION

# --- Red Hat certification required LABELs ---
LABEL name="enterprise-trino" \
      vendor="Datastrato Inc." \
      version="trino-${TRINO_VERSION}" \
      release="1" \
      summary="Trino with Gravitino connector" \
      description="Trino distributed SQL query engine with Apache Gravitino connector plugin for enterprise metadata federation." \
      maintainer="Datastrato Inc." \
      io.k8s.display-name="Datastrato Trino" \
      io.k8s.description="Trino query engine with Gravitino connector" \
      io.openshift.tags="datastrato,trino,query-engine,gravitino"

# --- Environment variables ---
ENV JAVA_HOME="/usr/lib/jvm/${JDK_VERSION}"
ENV PATH="${PATH}:${JAVA_HOME}/bin"
ENV CATALOG_MANAGEMENT=dynamic

# --- Copy JDK from jdk-download stage ---
COPY --from=jdk-download ${JAVA_HOME} ${JAVA_HOME}

# --- Copy system packages overlay ---
COPY --from=packages /tmp/overlay /

# --- Create trino user (GID 0 for OpenShift arbitrary UID compatibility) ---
RUN set -xeu && \
    groupadd trino --gid 1000 && \
    useradd trino --uid 1000 --gid 0 --create-home && \
    mkdir -p /usr/lib/trino /data/trino && \
    chown -R 1000:0 /usr/lib/trino /data/trino

# --- Copy Trino server from packages stage ---
COPY --from=packages --chown=1000:0 /tmp/trino-server-${TRINO_VERSION} /usr/lib/trino

# --- Copy scripts ---
COPY --chown=1000:0 bin/run-trino /usr/lib/trino/bin/run-trino
COPY --chown=1000:0 bin/health-check /usr/lib/trino/bin/health-check
RUN chmod +x /usr/lib/trino/bin/run-trino /usr/lib/trino/bin/health-check

# --- Copy jvmkill (OOM protection) ---
COPY --chown=1000:0 --from=jvmkill /libjvmkill.so /usr/lib/trino/bin/libjvmkill.so

# --- Copy Gravitino connector and JDBC drivers ---
# Pre-built by trino-dependency.sh before docker build
COPY --chown=1000:0 packages/gravitino-trino-connector /usr/lib/trino/plugin/gravitino
COPY --chown=1000:0 packages/gravitino-trino-connector/mysql-connector-java-8.0.27.jar /usr/lib/trino/plugin/iceberg/
COPY --chown=1000:0 packages/gravitino-trino-connector/postgresql-42.7.11.jar /usr/lib/trino/plugin/iceberg/

# --- Copy configuration (includes update-trino-conf.sh + catalog templates) ---
COPY --chown=1000:0 packages/trino/conf /etc/trino

# --- Red Hat certification: licenses directory ---
COPY licenses /licenses

# --- OpenShift arbitrary UID compatibility ---
# All directories must be group-accessible by GID 0
RUN chgrp -R 0 /usr/lib/trino /etc/trino /data/trino && \
    chmod -R g=u /usr/lib/trino /etc/trino /data/trino

USER 1000

EXPOSE 8080

ENTRYPOINT ["/bin/bash", "-c", "/etc/trino/update-trino-conf.sh && /usr/lib/trino/bin/run-trino"]
HEALTHCHECK --interval=10s --timeout=5s --start-period=10s \
  CMD /usr/lib/trino/bin/health-check
