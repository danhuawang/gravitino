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
FROM openjdk:17-jdk-bullseye
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

ARG MYSQL_JDBC_DRIVER_NAME
ARG POSTGRESQL_JDBC_DRIVER_NAME

WORKDIR /root/gravitino

COPY packages/gravitino /root/gravitino

# copy JDBC driver
COPY packages/${MYSQL_JDBC_DRIVER_NAME} packages/${POSTGRESQL_JDBC_DRIVER_NAME} /root/gravitino/libs/

# copy MySQL JDBC driver
RUN cp /root/gravitino/libs/${MYSQL_JDBC_DRIVER_NAME} /root/gravitino/catalogs/jdbc-mysql/libs/ && \
    cp /root/gravitino/libs/${MYSQL_JDBC_DRIVER_NAME} /root/gravitino/catalogs/jdbc-doris/libs/ && \
    cp /root/gravitino/libs/${MYSQL_JDBC_DRIVER_NAME} /root/gravitino/catalogs/jdbc-oceanbase/libs/ && \
    cp /root/gravitino/libs/${MYSQL_JDBC_DRIVER_NAME} /root/gravitino/catalogs/lakehouse-iceberg/libs/ && \
    cp /root/gravitino/libs/${MYSQL_JDBC_DRIVER_NAME} /root/gravitino/iceberg-rest-server/libs/

# copy PostgreSQL JDBC driver
RUN cp /root/gravitino/libs/${POSTGRESQL_JDBC_DRIVER_NAME} /root/gravitino/catalogs/jdbc-postgresql/libs/ && \
    cp /root/gravitino/libs/${POSTGRESQL_JDBC_DRIVER_NAME} /root/gravitino/catalogs/lakehouse-iceberg/libs/ && \
    cp /root/gravitino/libs/${POSTGRESQL_JDBC_DRIVER_NAME} /root/gravitino/iceberg-rest-server/libs/

EXPOSE 8090
EXPOSE 9001

ENTRYPOINT ["/bin/bash", "/root/gravitino/bin/gravitino.sh", "start"]
