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
# OpenSearch with IK Analysis Plugin - Red Hat UBI 9 Certified Image
# Base: ubi10/ubi-minimal (RHEL 10)
#
# Multi-stage build:
#   Stage 1 (downloader) - Download OpenSearch tarball + install IK plugin
#   Stage 2 (runtime)    - Minimal UBI 9 production runtime
#
# Key design decisions:
#   - UBI 9 minimal for consistent CVE baseline across all enterprise images
#   - OpenSearch tarball bundles JDK 21, no separate JDK install needed
#   - Official entrypoint script handles DISABLE_SECURITY_PLUGIN env var
#   - No shadow-utils; user created via /etc/passwd append
#   - JVM tuning via OPENSEARCH_JAVA_OPTS env at deployment, not hardcoded
#
# Red Hat Certification Compliance:
#   - Based on UBI 9 (registry.access.redhat.com)
#   - Required LABELs present
#   - /licenses directory with all required license files
#   - Runs as non-root user (UID 1000, GID 0)
#   - OpenShift arbitrary UID compatible (GID 0 group permissions)
# ==============================================================================

ARG UBI_MINIMAL_TAG=10.2
ARG OPENSEARCH_VERSION=2.17.1

# ==============================================================================
# Stage 1: Download artifacts
# Use ubi10/ubi (full) instead of ubi-minimal for the downloader stage because:
#   - tar, gzip, curl are pre-installed (no need for microdnf install)
#   - Avoids QEMU + microdnf repo resolution issues on cross-arch buildx
#   - This stage is discarded in the final image, so size doesn't matter
# ==============================================================================
FROM registry.access.redhat.com/ubi10/ubi:10.1 AS downloader

ARG OPENSEARCH_VERSION
ARG TARGETARCH

# Download OpenSearch tarball (includes bundled JDK 21)
RUN set -eux; \
    case "${TARGETARCH:-amd64}" in \
      amd64|x86_64) OS_ARCH="x64" ;; \
      arm64|aarch64) OS_ARCH="arm64" ;; \
      *) echo "Unsupported arch: ${TARGETARCH}" && exit 1 ;; \
    esac; \
    curl --retry 3 --retry-delay 5 -fsSL \
      "https://artifacts.opensearch.org/releases/bundle/opensearch/${OPENSEARCH_VERSION}/opensearch-${OPENSEARCH_VERSION}-linux-${OS_ARCH}.tar.gz" \
      -o /tmp/opensearch.tar.gz && \
    tar -xzf /tmp/opensearch.tar.gz -C /usr/share/ && \
    mv /usr/share/opensearch-${OPENSEARCH_VERSION} /usr/share/opensearch && \
    rm -f /tmp/opensearch.tar.gz

# Install IK Analysis plugin
RUN /usr/share/opensearch/bin/opensearch-plugin install --batch \
      https://get.infini.cloud/opensearch/analysis-ik/${OPENSEARCH_VERSION}

# ==============================================================================
# Stage 2: Production runtime
# ==============================================================================
FROM registry.access.redhat.com/ubi10/ubi-minimal:${UBI_MINIMAL_TAG}

ARG OPENSEARCH_VERSION

# --- Red Hat certification required LABELs ---
LABEL name="enterprise-opensearch-ik" \
      vendor="Datastrato Inc." \
      version="${OPENSEARCH_VERSION}" \
      release="1" \
      summary="OpenSearch with IK Analysis plugin" \
      description="OpenSearch search engine bundled with IK Chinese text analysis plugin for enterprise metadata search." \
      maintainer="Datastrato Inc." \
      io.k8s.display-name="Datastrato OpenSearch IK" \
      io.k8s.description="OpenSearch with IK Analysis plugin for Chinese text analysis" \
      io.openshift.tags="datastrato,opensearch,search,ik-analysis"

# --- Install runtime dependencies ---
# bash: required by entrypoint script and OpenSearch internal scripts
# curl-minimal: already pre-installed in ubi-minimal (healthcheck probe)
# procps-ng: provides ps/jps for process management
# findutils: provides find/xargs used by OpenSearch scripts
# which: required by securityadmin.sh
# Note: Do NOT install full 'curl' — it conflicts with pre-installed curl-minimal
# Note: --releasever=9 ensures correct repo resolution on cross-arch buildx (QEMU)
RUN microdnf update -y --nodocs \
    && microdnf install -y --setopt=install_weak_deps=0 --nodocs \
      bash \
      hostname \
      procps-ng \
      findutils \
      which \
    && microdnf clean all

# --- Create opensearch user (no shadow-utils needed) ---
# UBI minimal does not include shadow-utils; append directly to /etc/passwd
# UID 1000, GID 0 (root group) for OpenShift compatibility
RUN echo 'opensearch:x:1000:0:OpenSearch User:/usr/share/opensearch:/sbin/nologin' >> /etc/passwd

# --- Copy OpenSearch from downloader stage ---
COPY --from=downloader /usr/share/opensearch /usr/share/opensearch

# --- Create runtime writable directories ---
RUN mkdir -p /usr/share/opensearch/data /usr/share/opensearch/logs

# --- Copy entrypoint script (from opensearch-build official repo) ---
COPY opensearch-docker-entrypoint.sh /usr/share/opensearch/opensearch-docker-entrypoint.sh
RUN chmod +x /usr/share/opensearch/opensearch-docker-entrypoint.sh

# --- Red Hat certification: licenses directory ---
COPY licenses /licenses

# --- OpenShift arbitrary UID compatibility ---
# OpenShift restricted-v2 SCC runs containers with random UID but GID=0
# All files must be group-accessible by root group (GID 0)
# data/logs directories need explicit ownership for write access
RUN chgrp -R 0 /usr/share/opensearch && \
    chmod -R g=u /usr/share/opensearch && \
    chown -R 1000:0 /usr/share/opensearch/data /usr/share/opensearch/logs

# --- Environment variables ---
ENV OPENSEARCH_HOME=/usr/share/opensearch
ENV JAVA_HOME=/usr/share/opensearch/jdk
ENV PATH="${OPENSEARCH_HOME}/bin:${JAVA_HOME}/bin:${PATH}"
# k-NN plugin native library path
ENV LD_LIBRARY_PATH="${OPENSEARCH_HOME}/plugins/opensearch-knn/lib"

WORKDIR /usr/share/opensearch

USER 1000

EXPOSE 9200 9300 9600 9650

ENTRYPOINT ["./opensearch-docker-entrypoint.sh"]
CMD ["opensearch"]
