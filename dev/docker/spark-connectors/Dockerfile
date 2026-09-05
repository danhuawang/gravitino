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
# Spark Connectors — Red Hat UBI 10 Certified Image
# Contains all supported Gravitino Spark connector versions (Scala 2.12).
# Used as K8s init-container to inject connector jars into Spark pods.
#
# Spark version is selected at runtime via SPARK_VERSION env var.
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
LABEL name="gravitino-spark-connector" \
      vendor="Datastrato Inc." \
      version="${IMAGE_VERSION}" \
      release="${IMAGE_RELEASE}" \
      summary="Gravitino Spark Connectors (Spark 3.3/3.4/3.5, Scala 2.12/2.13)" \
      description="All supported Gravitino Spark connector versions for K8s init-container deployment. Supports Spark 3.3 (Scala 2.12), Spark 3.4 and 3.5 (Scala 2.12 + 2.13). Version is selected at runtime via SPARK_VERSION and SCALA_VERSION environment variables." \
      maintainer="Datastrato Inc."

# Default Spark and Scala version (can be overridden at runtime)
ENV SPARK_VERSION=3.5
ENV SCALA_VERSION=2.12

# Copy pre-built connector jars (prepared by spark-connectors-dependency.sh)
COPY packages/connectors /connectors

# Entrypoint script for version selection
COPY --chmod=755 copy-connector.sh /copy-connector.sh

# --- Red Hat certification: licenses directory ---
COPY licenses /licenses

# --- OpenShift arbitrary UID compatibility ---
RUN chgrp -R 0 /connectors && chmod -R g=u /connectors

USER 1000

ENTRYPOINT ["/copy-connector.sh"]
