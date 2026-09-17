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

TEST_CONTAINERS=${TEST_CONTAINERS:-default}

if [ "$TEST_CONTAINERS" = "default" ]; then
  COMPOSE_FILE=docker-compose.yaml
else
  COMPOSE_FILE=docker-compose-trino-${TEST_CONTAINERS}.yaml
fi

if [ ! -f "$COMPOSE_FILE" ]; then
  echo "ERROR: Unsupported TEST_CONTAINERS value: $TEST_CONTAINERS (expected compose file $COMPOSE_FILE not found)."
  exit 1
fi

# The Oracle and SQL Server adapters ship in the Datastrato ext jar, which the Trino container
# loads next to the open source connector. Build it with `./gradlew :trino-connector:trino-connector-ext:copyLibs`.
case "$TEST_CONTAINERS" in
  oracle|sqlserver)
    if [ -z "$GRAVITINO_TRINO_CONNECTOR_EXT_JAR" ]; then
      ext_lib_dir=${GRAVITINO_TRINO_CONNECTOR_EXTRA_LIBS:-../../distribution/datastrato-trino-connector-ext}
      GRAVITINO_TRINO_CONNECTOR_EXT_JAR=$(ls "$ext_lib_dir"/*.jar 2>/dev/null | head -n 1)
    fi
    if [ ! -f "$GRAVITINO_TRINO_CONNECTOR_EXT_JAR" ]; then
      echo "ERROR: Trino connector ext jar not found, set GRAVITINO_TRINO_CONNECTOR_EXT_JAR or build it with :trino-connector:trino-connector-ext:copyLibs."
      exit 1
    fi
    export GRAVITINO_TRINO_CONNECTOR_EXT_JAR
    ;;
esac
