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
FROM trinodb/trino:478
LABEL maintainer="dev@gravitino.apache.org"

USER root

# Copy configuration and connectors
COPY --chown=trino:root packages/trino/conf /etc/trino
COPY --chown=trino:root packages/gravitino-trino-connector/mysql-connector-java-8.0.27.jar /usr/lib/trino/plugin/iceberg/
COPY --chown=trino:root packages/gravitino-trino-connector/postgresql-42.7.0.jar /usr/lib/trino/plugin/iceberg/

RUN mkdir /tmp/gravitino
COPY --chown=trino:root packages/gravitino-trino-connector /tmp/gravitino

ARG TAG_NAME
RUN case "$TAG_NAME" in \
    trino-*|enterprise-trino-*) \
        mv /tmp/gravitino /usr/lib/trino/plugin/; \
        ;; \
    *) \
        echo "Copying files for other images"; \
        ;; \
    esac

# Set permissions for OpenShift compatibility:
# OpenShift runs containers with arbitrary UIDs but always uses GID 0 (root group)
RUN chgrp -R 0 /etc/trino /usr/lib/trino /data/trino && \
    chmod -R g+rwX /etc/trino /usr/lib/trino /data/trino

USER 1000

# The following ENV are needed to update configuration file before starting Trino server, you need to
# pass these ENV variables to the container when starting it like `docker run --env GRAVITINO_HOST_IP=xxx --env GRAVITINO_HOST_PORT=xxxx`
#ENV GRAVITINO_HOST_IP
#ENV GRAVITINO_HOST_PORT
#ENV GRAVITINO_METALAKE_NAME
#ENV HADOOP_USER_NAME

ENTRYPOINT ["/bin/bash", "-c", "/etc/trino/update-trino-conf.sh && /usr/lib/trino/bin/run-trino"]