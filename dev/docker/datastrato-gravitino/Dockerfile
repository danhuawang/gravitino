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
FROM eclipse-temurin:17.0.16_8-jdk-jammy
LABEL maintainer="support@datastrato.com"

RUN apt-get update && apt-get install -y \
    vim \
    jq \
    bsdmainutils \
    less \
    curl \
    wget \
    git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/gravitino

COPY packages/gravitino /opt/gravitino

# Create soft links for JDBC drivers
RUN if [ -d "/opt/gravitino/jdbc-drivers" ]; then \
      mkdir -p /opt/gravitino/libs && \
      find /opt/gravitino/jdbc-drivers -name "*.jar" -exec ln -sfv {} /opt/gravitino/libs/ ';' ; \
    fi

# Create soft links for Iceberg bundles
RUN if [ -d "/opt/gravitino/iceberg-bundles" ]; then \
      mkdir -p /opt/gravitino/catalogs/lakehouse-iceberg/libs && \
      mkdir -p /opt/gravitino/iceberg-rest-server/libs && \
      find /opt/gravitino/iceberg-bundles -name '*.jar' -exec ln -sfv {} /opt/gravitino/catalogs/lakehouse-iceberg/libs/ ';' -exec ln -sfv {} /opt/gravitino/iceberg-rest-server/libs/ ';' ; \
    fi

# Create soft links for GCS connector
RUN if [ -d "/opt/gravitino/gcs-connector" ]; then \
      mkdir -p /opt/gravitino/catalogs/lakehouse-iceberg/libs && \
      mkdir -p /opt/gravitino/iceberg-rest-server/libs && \
      find /opt/gravitino/gcs-connector -name '*.jar' -exec ln -sfv {} /opt/gravitino/catalogs/lakehouse-iceberg/libs/ ';' -exec ln -sfv {} /opt/gravitino/iceberg-rest-server/libs/ ';' ; \
    fi

# Set permissions for OpenShift compatibility:
# OpenShift runs containers with arbitrary UIDs but always uses GID 0 (root group)
# chmod g+rwX: Add read/write for group, execute only for directories and already-executable files
RUN chmod -R g+rwX /opt/gravitino \
    && chgrp -R 0 /opt/gravitino \
    && useradd -u 1000 -g 0 -M -s /sbin/nologin gravitino 

EXPOSE 8090
EXPOSE 9001

# Switch to non-root user (OpenShift will override the UID but keep GID 0)
USER 1000

ENTRYPOINT ["/bin/bash", "/opt/gravitino/bin/gravitino.sh", "start"]