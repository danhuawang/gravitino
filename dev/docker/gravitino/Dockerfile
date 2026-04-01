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
LABEL maintainer="dev@gravitino.apache.org"

RUN apt-get update && apt-get install -y \
    python-is-python3 && \
    rm -rf /var/lib/apt/lists/*

# Create gravitino user and group without specifying UID/GID
# This allows OpenShift to assign dynamic UIDs while maintaining compatibility with standard K8s
RUN groupadd -r gravitino && \
    useradd -r -g gravitino -m -s /bin/bash gravitino

# Use /opt instead of /root for better compatibility
WORKDIR /opt/gravitino

# Copy application files
COPY packages/gravitino /opt/gravitino

# Set permissions for OpenShift compatibility:
# - chgrp -R 0: Set group to root (GID 0) - OpenShift always uses GID 0
# - chmod -R g=u: Give group same permissions as user - allows any UID in root group to access files
RUN chgrp -R 0 /opt/gravitino && \
    chmod -R g=u /opt/gravitino && \
    chmod +x /opt/gravitino/bin/*.sh

EXPOSE 8090
EXPOSE 9001

# Switch to non-root user (OpenShift will override the UID but keep GID 0)
USER 1000

ENTRYPOINT ["/bin/bash", "/opt/gravitino/bin/start-gravitino.sh"]
