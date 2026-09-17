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

# Build all Gravitino Spark connector runtime shadow jars
# Produces both Scala 2.12 and 2.13 variants where supported:
#   - Spark 3.3: Scala 2.12 only
#   - Spark 3.4: Scala 2.12 + 2.13
#   - Spark 3.5: Scala 2.12 + 2.13

set -ex

script_dir="$(dirname "${BASH_SOURCE-$0}")"
script_dir="$(cd "${script_dir}" >/dev/null; pwd)"
gravitino_home="$(cd "${script_dir}/../../.." >/dev/null; pwd)"

cd "${gravitino_home}"

# --- Build Scala 2.12 (all 3 versions) ---
./gradlew \
  :spark-connector:spark-runtime-3.3:shadowJar \
  :spark-connector:spark-runtime-3.4:shadowJar \
  :spark-connector:spark-runtime-3.5:shadowJar \
  -PscalaVersion=2.12 \
  -x test

# --- Build Scala 2.13 (Spark 3.4 + 3.5 only; Spark 3.3 does not support 2.13) ---
./gradlew \
  :spark-connector:spark-runtime-3.4:shadowJar \
  :spark-connector:spark-runtime-3.5:shadowJar \
  -PscalaVersion=2.13 \
  -x test

# Clean old packages
rm -rf "${script_dir}/packages"
mkdir -p "${script_dir}/packages/connectors/spark-3.3_2.12" \
         "${script_dir}/packages/connectors/spark-3.4_2.12" \
         "${script_dir}/packages/connectors/spark-3.4_2.13" \
         "${script_dir}/packages/connectors/spark-3.5_2.12" \
         "${script_dir}/packages/connectors/spark-3.5_2.13"

# Copy shadow jars (exclude *-empty.jar artifacts)
find spark-connector/v3.3/spark-runtime/build/libs/ -name "*_2.12-*.jar" ! -name "*-empty*" \
  -exec cp {} "${script_dir}/packages/connectors/spark-3.3_2.12/" \;
find spark-connector/v3.4/spark-runtime/build/libs/ -name "*_2.12-*.jar" ! -name "*-empty*" \
  -exec cp {} "${script_dir}/packages/connectors/spark-3.4_2.12/" \;
find spark-connector/v3.4/spark-runtime/build/libs/ -name "*_2.13-*.jar" ! -name "*-empty*" \
  -exec cp {} "${script_dir}/packages/connectors/spark-3.4_2.13/" \;
find spark-connector/v3.5/spark-runtime/build/libs/ -name "*_2.12-*.jar" ! -name "*-empty*" \
  -exec cp {} "${script_dir}/packages/connectors/spark-3.5_2.12/" \;
find spark-connector/v3.5/spark-runtime/build/libs/ -name "*_2.13-*.jar" ! -name "*-empty*" \
  -exec cp {} "${script_dir}/packages/connectors/spark-3.5_2.13/" \;

echo ""
echo "=== Spark connectors prepared ==="
echo "Output: ${script_dir}/packages/connectors/"
find "${script_dir}/packages/connectors/" -name "*.jar" | sort
