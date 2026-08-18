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
# Flink Connectors — Red Hat UBI 10 Certified Image
# Contains all supported Gravitino Flink connector versions (Scala 2.12 only).
# Used as K8s init-container to inject connector jars into Flink pods.
#
# Flink version is selected at runtime via FLINK_VERSION env var.
# Note: Flink only supports Scala 2.12; all scala API will be removed
#       in a future Flink version.
#
# Red Hat Certification Compliance:
#   - Based on UBI 10 (registry.access.redhat.com)
#   - Required LABELs present
#   - /licenses directory with all required license files
#   - Runs as non-root user (UID 1000, GID 0)
#   - OpenShift arbitrary UID compatible (GID 0 group permissions)
# ==============================================================================

ARG UBI_MINIMAL_TAG=10.2
ARG IMAGE_VERSION=2.0.0-SNAPSHOT
ARG IMAGE_RELEASE=1

FROM registry.access.redhat.com/ubi10/ubi-minimal:${UBI_MINIMAL_TAG}
ARG IMAGE_VERSION
ARG IMAGE_RELEASE

# --- Red Hat certification required LABELs ---
LABEL name="gravitino-flink-connector" \
      vendor="Datastrato Inc." \
      version="${IMAGE_VERSION}" \
      release="${IMAGE_RELEASE}" \
      summary="Gravitino Flink Connectors (Flink 1.18, 1.19, 1.20 / Scala 2.12)" \
      description="All supported Gravitino Flink connector versions for K8s init-container deployment. Supports Flink 1.18, 1.19, and 1.20 with Scala 2.12. Version is selected at runtime via FLINK_VERSION environment variable." \
      maintainer="Datastrato Inc."

# Default Flink version (can be overridden at runtime)
ENV FLINK_VERSION=1.20

# Copy pre-built connector jars (prepared by flink-connectors-dependency.sh)
COPY packages/connectors /connectors

# Entrypoint script for version selection
COPY --chmod=755 copy-connector.sh /copy-connector.sh

# --- Red Hat certification: licenses directory ---
COPY licenses /licenses

# --- OpenShift arbitrary UID compatibility ---
RUN chgrp -R 0 /connectors && chmod -R g=u /connectors

USER 1000

ENTRYPOINT ["/copy-connector.sh"]
