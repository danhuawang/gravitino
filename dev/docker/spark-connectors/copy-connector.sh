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

# Copies the Spark connector jar matching SPARK_VERSION and SCALA_VERSION to /target/
#
# Environment variables:
#   SPARK_VERSION  - Spark major version (default: 3.5)
#   SCALA_VERSION  - Scala version (default: 2.12)
#
# Supported combinations:
#   Spark 3.3 + Scala 2.12
#   Spark 3.4 + Scala 2.12 / 2.13
#   Spark 3.5 + Scala 2.12 / 2.13

set -e

SPARK_VERSION="${SPARK_VERSION:-3.5}"
SCALA_VERSION="${SCALA_VERSION:-2.12}"
SOURCE_DIR="/connectors/spark-${SPARK_VERSION}_${SCALA_VERSION}"

if [ ! -d "$SOURCE_DIR" ]; then
  echo "ERROR: Spark ${SPARK_VERSION} with Scala ${SCALA_VERSION} not found."
  echo ""
  echo "Available combinations:"
  ls -1 /connectors/ | sed 's/spark-/  Spark /' | sed 's/_/ + Scala /'
  exit 1
fi

if [ -d "/target" ]; then
  echo "Copying Spark ${SPARK_VERSION} connector (Scala ${SCALA_VERSION}) to /target/..."
  cp "${SOURCE_DIR}"/*.jar /target/
  echo "Done. Jars copied to /target/:"
  ls -1 /target/*.jar 2>/dev/null
else
  echo "No /target volume mounted."
  echo ""
  echo "Usage: Mount /target volume and set SPARK_VERSION/SCALA_VERSION env vars."
  echo "  docker run -e SPARK_VERSION=3.5 -e SCALA_VERSION=2.12 -v /path:/target <image>"
  echo ""
  echo "Available combinations:"
  echo "  Spark 3.3 + Scala 2.12"
  echo "  Spark 3.4 + Scala 2.12 / 2.13"
  echo "  Spark 3.5 + Scala 2.12 / 2.13"
fi
