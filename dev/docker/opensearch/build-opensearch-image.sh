#!/bin/bash
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

set -euo pipefail

# Configuration
OPENSEARCH_VERSION="${OPENSEARCH_VERSION:-2.17.1}"
IMAGE_NAME="${IMAGE_NAME:-datastratosandbox/opensearch-with-analysis-ik}"
IMAGE_TAG="${IMAGE_TAG:-${OPENSEARCH_VERSION}}"

echo "Building OpenSearch IK image (UBI 9 certified)"
echo "  OpenSearch version: ${OPENSEARCH_VERSION}"
echo "  Image: ${IMAGE_NAME}:${IMAGE_TAG}"
echo "  Platform: linux/amd64,linux/arm64"

# docker login -u datastratosandbox

docker buildx build \
  --no-cache \
  --pull \
  --platform=linux/amd64,linux/arm64 \
  --build-arg OPENSEARCH_VERSION="${OPENSEARCH_VERSION}" \
  --push \
  --progress plain \
  -f Dockerfile \
  -t "docker.io/${IMAGE_NAME}:${IMAGE_TAG}" \
  .

echo "Done. Image pushed: ${IMAGE_NAME}:${IMAGE_TAG}"
