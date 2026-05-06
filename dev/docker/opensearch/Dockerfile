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
FROM opensearchproject/opensearch:2.17.1
LABEL maintainer="support@datastrato.com"

USER root

RUN /usr/share/opensearch/bin/opensearch-plugin install --batch https://get.infini.cloud/opensearch/analysis-ik/2.17.1

# Make the image OpenShift-compatible by setting proper permissions for arbitrary user IDs
# OpenShift runs containers with random UIDs but always uses GID 0 (root group)
RUN chgrp -R 0 /usr/share/opensearch && \
    chmod -R g+rwX /usr/share/opensearch

# Switch back to non-root user (OpenShift will override the UID but keep GID 0)
USER 1000