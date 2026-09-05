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
# Trino/Starburst Connectors — Red Hat UBI 10 Certified Image
# Contains all 6 supported Gravitino Trino connector version ranges.
# Supports both open-source Trino and Starburst Enterprise.
# Used as K8s init-container to inject connector jars into Trino/Starburst pods.
#
# Trino version range is selected at runtime via TRINO_VERSION env var.
#
# Red Hat Certification Compliance:
#   - Based on UBI 10 (registry.access.redhat.com)
#   - Required LABELs present
#   - /licenses directory with all required license files
#   - Runs as non-root user (UID 1000, GID 0)
#   - OpenShift arbitrary UID compatible (GID 0 group permissions)
# ==============================================================================

ARG UBI_MINIMAL_TAG=10.2
ARG IMAGE_VERSION=1.3.0
ARG IMAGE_RELEASE=1

FROM registry.access.redhat.com/ubi10/ubi-minimal:${UBI_MINIMAL_TAG}
ARG IMAGE_VERSION
ARG IMAGE_RELEASE

# --- Red Hat certification required LABELs ---
LABEL name="gravitino-trino-connector" \
      vendor="Datastrato Inc." \
      version="${IMAGE_VERSION}" \
      release="${IMAGE_RELEASE}" \
      summary="Gravitino Trino/Starburst Connectors (6 version ranges)" \
      description="All supported Gravitino Trino connector versions for K8s init-container deployment. Supports both open-source Trino (435-478) and Starburst Enterprise. Includes shared JDBC drivers for catalog backends. Version is selected at runtime via TRINO_VERSION environment variable." \
      maintainer="Datastrato Inc."

# Default Trino version (can be overridden at runtime, e.g. 478, 452, 440)
ENV TRINO_VERSION=478
# Whether to copy JDBC drivers to /target/ alongside connector jars
ENV COPY_JDBC_DRIVERS=true

# Copy pre-built connector jars (prepared by trino-connectors-dependency.sh)
COPY packages/connectors /connectors

# Entrypoint script for version selection
COPY --chmod=755 copy-connector.sh /copy-connector.sh

# --- Red Hat certification: licenses directory ---
COPY licenses /licenses

# --- OpenShift arbitrary UID compatibility ---
RUN chgrp -R 0 /connectors && chmod -R g=u /connectors

USER 1000

ENTRYPOINT ["/copy-connector.sh"]
