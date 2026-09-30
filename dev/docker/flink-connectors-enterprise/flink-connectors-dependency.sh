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

# Build all Gravitino Flink connector runtime shadow jars.
# Flink only supports Scala 2.12 (hardcoded in build scripts).
#
# Output:
#   packages/connectors/flink-1.18/gravitino-flink-connector-runtime-1.18_2.12-*.jar
#   packages/connectors/flink-1.19/gravitino-flink-connector-runtime-1.19_2.12-*.jar
#   packages/connectors/flink-1.20/gravitino-flink-connector-runtime-1.20_2.12-*.jar

set -ex

script_dir="$(dirname "${BASH_SOURCE-$0}")"
script_dir="$(cd "${script_dir}" >/dev/null; pwd)"
gravitino_home="$(cd "${script_dir}/../../.." >/dev/null; pwd)"

# Refresh LICENSE.bin, NOTICE.bin, and image /licenses from the verified CSV.
bash "${gravitino_home}/dev/docker/licenses-catalog/apply.sh"

cd "${gravitino_home}"

# Build all 3 Flink connector runtime shadow jars (Scala 2.12 only)
./gradlew \
  :flink-connector:flink-runtime-1.18:shadowJar \
  :flink-connector:flink-runtime-1.19:shadowJar \
  :flink-connector:flink-runtime-1.20:shadowJar \
  :flink-connector:flink-ext-1.18:shadowJar \
  :flink-connector:flink-ext-1.19:shadowJar \
  :flink-connector:flink-ext-1.20:shadowJar \
  -x test

# Clean old packages
rm -rf "${script_dir}/packages"
mkdir -p "${script_dir}/packages/connectors/flink-1.18" \
         "${script_dir}/packages/connectors/flink-1.19" \
         "${script_dir}/packages/connectors/flink-1.20"

# Copy shadow jars (exclude *-empty.jar artifacts)
find flink-connector/v1.18/flink-runtime/build/libs/ -name "*.jar" ! -name "*-empty*" \
  -exec cp {} "${script_dir}/packages/connectors/flink-1.18/" \;
find flink-connector/v1.19/flink-runtime/build/libs/ -name "*.jar" ! -name "*-empty*" \
  -exec cp {} "${script_dir}/packages/connectors/flink-1.19/" \;
find flink-connector/v1.20/flink-runtime/build/libs/ -name "*.jar" ! -name "*-empty*" \
  -exec cp {} "${script_dir}/packages/connectors/flink-1.20/" \;

# Enterprise extension jars (Oracle / SQL Server) are shipped next to the open source connector
for v in 1.18 1.19 1.20; do
  find "flink-connector/ext/v${v}/flink/build/libs/" -name "datastrato-*.jar" ! -name "*-thin*" \
    -exec cp {} "${script_dir}/packages/connectors/flink-${v}/" \;
  if ! ls "${script_dir}/packages/connectors/flink-${v}"/datastrato-flink-connector-ext-*.jar >/dev/null 2>&1; then
    echo "ERROR: Enterprise extension jar missing in ${script_dir}/packages/connectors/flink-${v}"
    exit 1
  fi
done

echo ""
echo "=== Flink connectors prepared ==="
echo "Output: ${script_dir}/packages/connectors/"
find "${script_dir}/packages/connectors/" -name "*.jar" | sort
