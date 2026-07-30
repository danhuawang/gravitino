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
set -ex

trino_dir="$(dirname "${BASH_SOURCE-$0}")"
trino_dir="$(cd "${trino_dir}">/dev/null; pwd)"
gravitino_home="$(cd "${trino_dir}/../../..">/dev/null; pwd)"

# Clean packages
rm -rf "${trino_dir}/packages"
mkdir -p "${trino_dir}/packages"

cd ${gravitino_home}
${gravitino_home}/gradlew :trino-connector:trino-connector-473-478:assembleTrinoConnector -x test
cp -r "${gravitino_home}/distribution/gravitino-trino-connector-473-478" "${trino_dir}/packages/gravitino-trino-connector"

MYSQL_VERSION="8.0.27"
PG_VERSION="42.7.11"
MYSQL_JAVA_CONNECTOR_URL="https://repo1.maven.org/maven2/mysql/mysql-connector-java/${MYSQL_VERSION}/mysql-connector-java-${MYSQL_VERSION}.jar"
PG_JAVA_CONNECTOR_URL="https://jdbc.postgresql.org/download/postgresql-${PG_VERSION}.jar"

# Download MySQL jdbc driver if it does not exist.
if [ ! -f "${trino_dir}/packages/gravitino-trino-connector/mysql-connector-java-${MYSQL_VERSION}.jar" ]; then
  cd "${trino_dir}/packages/gravitino-trino-connector/" && curl -O "${MYSQL_JAVA_CONNECTOR_URL}" && cd -
fi

# Download PostgreSQL jdbc driver if it does not exist.
if [ ! -f "${trino_dir}/packages/gravitino-trino-connector/postgresql-${PG_VERSION}.jar" ]; then
  cd "${trino_dir}/packages/gravitino-trino-connector/" && curl -O "$PG_JAVA_CONNECTOR_URL" && cd -
fi

mkdir -p "${trino_dir}/packages/trino"
cp -r -p "${trino_dir}/conf" "${trino_dir}/packages/trino/conf"

# Download Trino server tarball from datastrato/Trino private repo release
TRINO_VERSION="478"
TRINO_TARBALL="${trino_dir}/packages/trino-server-${TRINO_VERSION}.tar.gz"
TRINO_RELEASE_URL="https://github.com/datastrato/Trino/releases/download/gravitino-${TRINO_VERSION}/trino-server-${TRINO_VERSION}.tar.gz"

if [ ! -f "${TRINO_TARBALL}" ]; then
  echo "Downloading Trino server tarball from datastrato/Trino release..."
  # Requires GH_TOKEN or gh CLI auth for private repo access
  if command -v gh &> /dev/null; then
    gh release download "gravitino-${TRINO_VERSION}" \
      --repo datastrato/Trino \
      --pattern "trino-server-${TRINO_VERSION}.tar.gz" \
      --dir "${trino_dir}/packages"
  elif [ -n "${GH_TOKEN:-}" ]; then
    curl -L --retry 3 --retry-delay 5 -fS \
      -H "Authorization: token ${GH_TOKEN}" \
      -H "Accept: application/octet-stream" \
      "${TRINO_RELEASE_URL}" \
      -o "${TRINO_TARBALL}"
  else
    echo "ERROR: Cannot download Trino tarball. Either install 'gh' CLI and authenticate, or set GH_TOKEN env var."
    echo "  Option 1: gh auth login && gh release download gravitino-${TRINO_VERSION} --repo datastrato/Trino --pattern 'trino-server-${TRINO_VERSION}.tar.gz' --dir ${trino_dir}/packages"
    echo "  Option 2: export GH_TOKEN=<your-github-token>"
    echo "  Option 3: Manually download from https://github.com/datastrato/Trino/releases/tag/gravitino-${TRINO_VERSION} and place at ${TRINO_TARBALL}"
    exit 1
  fi
fi

echo "Trino tarball ready: ${TRINO_TARBALL}"
