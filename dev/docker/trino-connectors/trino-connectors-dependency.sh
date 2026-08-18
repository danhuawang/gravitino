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

# Build all 6 Gravitino Trino connector versions and prepare for Docker image.
# The connector jars support both open-source Trino and Starburst Enterprise.
#
# Output layout:
#   packages/connectors/trino-435-439/  (jar files)
#   packages/connectors/trino-440-445/
#   packages/connectors/trino-446-451/
#   packages/connectors/trino-452-468/
#   packages/connectors/trino-469-472/
#   packages/connectors/trino-473-478/
#   packages/connectors/jdbc-drivers/   (MySQL + PostgreSQL JDBC drivers)

set -ex

script_dir="$(dirname "${BASH_SOURCE-$0}")"
script_dir="$(cd "${script_dir}" >/dev/null; pwd)"
gravitino_home="$(cd "${script_dir}/../../.." >/dev/null; pwd)"

cd "${gravitino_home}"

# Build all 6 Trino connector versions
./gradlew \
  :trino-connector:trino-connector-435-439:assembleTrinoConnector \
  :trino-connector:trino-connector-440-445:assembleTrinoConnector \
  :trino-connector:trino-connector-446-451:assembleTrinoConnector \
  :trino-connector:trino-connector-452-468:assembleTrinoConnector \
  :trino-connector:trino-connector-469-472:assembleTrinoConnector \
  :trino-connector:trino-connector-473-478:assembleTrinoConnector \
  -x test

# Clean old packages
rm -rf "${script_dir}/packages"
mkdir -p "${script_dir}/packages/connectors"

# Copy connector jars from distribution output
for dir in distribution/gravitino-trino-connector-*; do
  if [ -d "$dir" ]; then
    version=$(basename "$dir" | sed 's/gravitino-trino-connector-//')
    mkdir -p "${script_dir}/packages/connectors/trino-${version}"
    cp -r "$dir"/* "${script_dir}/packages/connectors/trino-${version}/"
  fi
done

# Download shared JDBC drivers for catalog backends (Iceberg, Hive, etc.)
mkdir -p "${script_dir}/packages/connectors/jdbc-drivers"
curl -sSL -o "${script_dir}/packages/connectors/jdbc-drivers/mysql-connector-java-8.0.27.jar" \
  "https://repo1.maven.org/maven2/mysql/mysql-connector-java/8.0.27/mysql-connector-java-8.0.27.jar"
curl -sSL -o "${script_dir}/packages/connectors/jdbc-drivers/postgresql-42.7.11.jar" \
  "https://jdbc.postgresql.org/download/postgresql-42.7.11.jar"

echo ""
echo "=== Trino connectors prepared ==="
echo "Output: ${script_dir}/packages/connectors/"
find "${script_dir}/packages/connectors/" -type d | sort
echo ""
echo "JDBC drivers:"
ls -1 "${script_dir}/packages/connectors/jdbc-drivers/"
