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

# Copies the Trino/Starburst connector jars matching TRINO_VERSION to /target/
# Also copies shared JDBC drivers to the same /target/ directory.
#
# Environment variables:
#   TRINO_VERSION      - Specific Trino version number (default: 478)
#                        The script automatically resolves to the correct version range.
#   COPY_JDBC_DRIVERS  - Whether to copy JDBC drivers to /target/ (default: true)
#
# Supported Trino versions: 435-439, 440-445, 446-451, 452-468, 469-472, 473-478
# The connector jars support both open-source Trino and Starburst Enterprise.

set -e

TRINO_VERSION="${TRINO_VERSION:-478}"
COPY_JDBC_DRIVERS="${COPY_JDBC_DRIVERS:-true}"

# Resolve specific Trino version number to the correct connector version range directory.
resolve_version_range() {
  local version=$1
  if [ "$version" -ge 435 ] && [ "$version" -le 439 ] 2>/dev/null; then
    echo "435-439"
  elif [ "$version" -ge 440 ] && [ "$version" -le 445 ] 2>/dev/null; then
    echo "440-445"
  elif [ "$version" -ge 446 ] && [ "$version" -le 451 ] 2>/dev/null; then
    echo "446-451"
  elif [ "$version" -ge 452 ] && [ "$version" -le 468 ] 2>/dev/null; then
    echo "452-468"
  elif [ "$version" -ge 469 ] && [ "$version" -le 472 ] 2>/dev/null; then
    echo "469-472"
  elif [ "$version" -ge 473 ] && [ "$version" -le 478 ] 2>/dev/null; then
    echo "473-478"
  else
    # If input is already a range (e.g. "473-478"), check if directory exists directly
    if [ -d "/connectors/trino-${version}" ]; then
      echo "$version"
    else
      echo ""
    fi
  fi
}

VERSION_RANGE=$(resolve_version_range "$TRINO_VERSION")

if [ -z "$VERSION_RANGE" ]; then
  echo "ERROR: Trino version ${TRINO_VERSION} is not supported."
  echo ""
  echo "Supported Trino versions: 435-478"
  echo "Available connector ranges:"
  ls -1 /connectors/ | grep "^trino-" | sed 's/trino-/  - /'
  exit 1
fi

SOURCE_DIR="/connectors/trino-${VERSION_RANGE}"

if [ ! -d "$SOURCE_DIR" ]; then
  echo "ERROR: Connector directory not found: ${SOURCE_DIR}"
  exit 1
fi

if [ -d "/target" ]; then
  echo "Trino version ${TRINO_VERSION} resolved to connector range: ${VERSION_RANGE}"
  echo "Copying connector jars to /target/..."
  cp -r "${SOURCE_DIR}"/* /target/

  if [ "${COPY_JDBC_DRIVERS}" = "true" ] && [ -d "/connectors/jdbc-drivers" ]; then
    cp /connectors/jdbc-drivers/*.jar /target/
    echo "JDBC drivers copied to /target/."
  fi

  echo ""
  echo "Done. Files in /target/:"
  find /target -name "*.jar" | sort
else
  echo "Gravitino Trino/Starburst connector jars available at /connectors/"
  echo ""
  echo "Supported Trino versions: 435-478"
  echo "Available connector ranges:"
  ls -1 /connectors/ | grep "^trino-" | sed 's/trino-/  Trino /'
  echo ""
  echo "JDBC drivers: /connectors/jdbc-drivers/"
  echo ""
  echo "Usage: Set TRINO_VERSION env var (specific version number) and mount /target volume."
  echo "Example: TRINO_VERSION=478"
fi
