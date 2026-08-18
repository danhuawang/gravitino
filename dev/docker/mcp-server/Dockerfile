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
# MCP Server — Red Hat UBI 10 Certified Image
# Base: ubi10/python-312-minimal (RHEL 10, Python 3.12)
#
# Red Hat Certification Compliance:
#   - Based on UBI 10 (registry.access.redhat.com)
#   - Required LABELs present
#   - /licenses directory with all required license files
#   - Runs as non-root user (UID 1000, GID 0)
#   - OpenShift arbitrary UID compatible (GID 0 group permissions)
# ==============================================================================

ARG UBI_PYTHON_TAG=10.2
ARG IMAGE_VERSION=2.0.0-SNAPSHOT
ARG IMAGE_RELEASE=1

FROM registry.access.redhat.com/ubi10/python-312-minimal:${UBI_PYTHON_TAG}

ARG IMAGE_VERSION
ARG IMAGE_RELEASE

# --- Red Hat certification required LABELs ---
LABEL name="enterprise-mcp-server" \
      vendor="Datastrato Inc." \
      version="${IMAGE_VERSION}" \
      release="${IMAGE_RELEASE}" \
      summary="Gravitino MCP Server" \
      description="Model Context Protocol server for Gravitino enterprise metadata access. Provides AI-powered tools for metadata federation via the MCP protocol." \
      maintainer="Datastrato Inc."

USER root

# Install uv package manager from official image
COPY --from=ghcr.io/astral-sh/uv:latest /uv /bin/uv

WORKDIR /opt/mcp-server

# Copy pre-built MCP server package (prepared by mcp-server-dependency.sh)
COPY --chmod=775 packages/mcp-server /opt/mcp-server
COPY --chmod=775 start-mcp-server.sh /opt/mcp-server/

# uv cache directory (writable by non-root)
ENV UV_CACHE_DIR=/opt/mcp-server/.cache/uv

# Install Python dependencies using uv
RUN uv venv /opt/mcp-server/.venv && \
    uv sync && \
    uv pip install -e . && \
    rm -rf /opt/mcp-server/.cache

# --- Red Hat certification: licenses directory ---
COPY licenses /licenses

# --- OpenShift arbitrary UID compatibility ---
# GID 0 group permissions allow any UID assigned by OpenShift SCC to write
RUN chgrp -R 0 /opt/mcp-server && \
    chmod -R g=u /opt/mcp-server

USER 1000

ENTRYPOINT ["/bin/bash", "/opt/mcp-server/start-mcp-server.sh"]
