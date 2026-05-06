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
gravitino_dir="$(dirname "${BASH_SOURCE-$0}")"
gravitino_dir="$(cd "${gravitino_dir}">/dev/null; pwd)"
gravitino_home="$(cd "${gravitino_dir}/../../..">/dev/null; pwd)"

MYSQL_JDBC_DRIVER_VERSION=${MYSQL_VERSION:-"8.0.26"}
MYSQL_JDBC_DRIVER_NAME="mysql-connector-java-${MYSQL_JDBC_DRIVER_VERSION}.jar"
MYSQL_JDBC_DRIVER_DOWNLOAD_URL="https://repo1.maven.org/maven2/mysql/mysql-connector-java/${MYSQL_JDBC_DRIVER_VERSION}/${MYSQL_JDBC_DRIVER_NAME}"

POSTGRESQL_JDBC_DRIVER_VERSION=${POSTGRESQL_VERSION:-"42.7.0"}
POSTGRESQL_JDBC_DRIVER_NAME="postgresql-${POSTGRESQL_JDBC_DRIVER_VERSION}.jar"
POSTGRESQL_JDBC_DRIVER_DOWNLOAD_URL="https://jdbc.postgresql.org/download/${POSTGRESQL_JDBC_DRIVER_NAME}"

GCS_CONNECTOR_VERSION=${GCS_CONNECTOR_VERSION:-"hadoop2-2.2.18"}
GCS_CONNECTOR_VERSION_SHORT=${GCS_CONNECTOR_VERSION_SHORT:-"2.2.18"}
GCS_CONNECTOR_NAME="gcs-connector-${GCS_CONNECTOR_VERSION}-shaded.jar"
GCS_CONNECTOR_DOWNLOAD_URL="https://github.com/GoogleCloudDataproc/hadoop-connectors/releases/download/v${GCS_CONNECTOR_VERSION_SHORT}/${GCS_CONNECTOR_NAME}"

SIMBA_BIGQUERY_JDBC_VERSION=${SIMBA_BIGQUERY_JDBC_VERSION:-"1.6.5.1001"}
SIMBA_BIGQUERY_ZIP_NAME="SimbaJDBCDriverforGoogleBigQuery42_${SIMBA_BIGQUERY_JDBC_VERSION}.zip"
SIMBA_BIGQUERY_DOWNLOAD_URL="https://storage.googleapis.com/simba-bq-release/jdbc/${SIMBA_BIGQUERY_ZIP_NAME}"

ODPS_JDBC_VERSION=${ODPS_JDBC_VERSION:-"3.8.8"}
ODPS_JDBC_DRIVER_NAME="odps-jdbc-${ODPS_JDBC_VERSION}-jar-with-dependencies.jar"
ODPS_JDBC_DOWNLOAD_URL="https://repo1.maven.org/maven2/com/aliyun/odps/odps-jdbc/${ODPS_JDBC_VERSION}/${ODPS_JDBC_DRIVER_NAME}"

CLICKHOUSE_JDBC_VERSION=${CLICKHOUSE_JDBC_VERSION:-"0.7.1"}
CLICKHOUSE_JDBC_DRIVER_NAME="clickhouse-jdbc-${CLICKHOUSE_JDBC_VERSION}-all.jar"
CLICKHOUSE_JDBC_DOWNLOAD_URL="https://repo1.maven.org/maven2/com/clickhouse/clickhouse-jdbc/${CLICKHOUSE_JDBC_VERSION}/${CLICKHOUSE_JDBC_DRIVER_NAME}"

# Prepare compile Gravitino packages
"${gravitino_home}"/gradlew clean
"${gravitino_home}"/gradlew compileDistribution -x test -x :docs:build -x :docs-enterprise:build -x :clients:client-python:build

# Removed old packages, Avoid multiple re-executions using the wrong file
rm -rf "${gravitino_dir}/packages"
mkdir -p "${gravitino_dir}/packages"

cp -r "${gravitino_home}/distribution/package-all" "${gravitino_dir}/packages/gravitino"

# make sure bundles are built
"${gravitino_home}"/gradlew :bundles:gcp:build :bundles:aws:build :bundles:azure:build :bundles:aliyun-bundle:build -x test

# Copy the all file system bundles to the Hadoop catalog libs
cp -r ${gravitino_home}/bundles/*-bundle/build/libs/*.jar "${gravitino_dir}/packages/gravitino/catalogs/fileset/libs"

# Copy the Aliyun, AWS, GCP and Azure bundles to the Iceberg bundles directory
iceberg_bundle_dir="${gravitino_dir}/packages/gravitino/iceberg-bundles"
mkdir -p "${iceberg_bundle_dir}"
find ${gravitino_home}/bundles/iceberg-gcp-bundle/build/libs/ -name 'gravitino-iceberg-gcp-bundle-*.jar' ! -name '*-empty.jar' -exec cp -v {} "${iceberg_bundle_dir}" \;
find ${gravitino_home}/bundles/iceberg-aws-bundle/build/libs/ -name 'gravitino-iceberg-aws-bundle-*.jar' ! -name '*-empty.jar' -exec cp -v {} "${iceberg_bundle_dir}" \;
find ${gravitino_home}/bundles/iceberg-azure-bundle/build/libs/ -name 'gravitino-iceberg-azure-bundle-*.jar' ! -name '*-empty.jar' -exec cp -v {} "${iceberg_bundle_dir}" \;
find ${gravitino_home}/bundles/iceberg-aliyun-bundle/build/libs/ -name 'gravitino-iceberg-aliyun-bundle-*.jar' ! -name '*-empty.jar' -exec cp -v {} "${iceberg_bundle_dir}" \;

jdbc_driver_dir="${gravitino_dir}/packages/gravitino/jdbc-drivers"
mkdir -p "${jdbc_driver_dir}"

gcs_connector_dir="${gravitino_dir}/packages/gravitino/gcs-connector"
mkdir -p "${gcs_connector_dir}"

# Download JDBC drivers to dedicated directory
if [ ! -f "${jdbc_driver_dir}/${MYSQL_JDBC_DRIVER_NAME}" ]; then
  curl -L -s -o "${jdbc_driver_dir}/${MYSQL_JDBC_DRIVER_NAME}" "${MYSQL_JDBC_DRIVER_DOWNLOAD_URL}"
fi

if [ ! -f "${jdbc_driver_dir}/${POSTGRESQL_JDBC_DRIVER_NAME}" ]; then
  curl -L -s -o "${jdbc_driver_dir}/${POSTGRESQL_JDBC_DRIVER_NAME}" "${POSTGRESQL_JDBC_DRIVER_DOWNLOAD_URL}"
fi

# Download GCS connector to dedicated directory
if [ ! -f "${gcs_connector_dir}/${GCS_CONNECTOR_NAME}" ]; then
  curl -L -s -o "${gcs_connector_dir}/${GCS_CONNECTOR_NAME}" "${GCS_CONNECTOR_DOWNLOAD_URL}"
fi

# Download and install BigQuery Simba JDBC driver
gravitino_bigquery_catalog_dir="${gravitino_dir}/packages/gravitino/catalogs/jdbc-bigquery/libs"
simba_tmp_dir="${gravitino_dir}/packages/simba-bigquery-tmp"
if [ ! -f "${gravitino_dir}/packages/${SIMBA_BIGQUERY_ZIP_NAME}" ]; then
  curl -L -s -o "${gravitino_dir}/packages/${SIMBA_BIGQUERY_ZIP_NAME}" "${SIMBA_BIGQUERY_DOWNLOAD_URL}"
fi
if [ -d "${simba_tmp_dir}" ]; then
  rm -rf "${simba_tmp_dir}"
fi
mkdir -p "${simba_tmp_dir}"
# Use jar to extract ZIP (available via JDK, no unzip dependency needed)
(cd "${simba_tmp_dir}" && jar xf "${gravitino_dir}/packages/${SIMBA_BIGQUERY_ZIP_NAME}")
# Copy all JARs except Jackson JARs to avoid dependency conflicts with Gravitino
find "${simba_tmp_dir}" -name '*.jar' ! -name 'jackson-*.jar' -exec cp -v {} "${gravitino_bigquery_catalog_dir}" \;
if [ -d "${simba_tmp_dir}" ]; then
  rm -rf "${simba_tmp_dir}"
fi

# Download and install MaxCompute ODPS JDBC driver
gravitino_maxcompute_catalog_dir="${gravitino_dir}/packages/gravitino/catalogs/jdbc-maxcompute/libs"
if [ ! -f "${gravitino_dir}/packages/${ODPS_JDBC_DRIVER_NAME}" ]; then
  curl -L -s -o "${gravitino_dir}/packages/${ODPS_JDBC_DRIVER_NAME}" "${ODPS_JDBC_DOWNLOAD_URL}"
fi
cp "${gravitino_dir}/packages/${ODPS_JDBC_DRIVER_NAME}" "${gravitino_maxcompute_catalog_dir}"

# Download and install ClickHouse JDBC driver
gravitino_clickhouse_catalog_dir="${gravitino_dir}/packages/gravitino/catalogs/jdbc-clickhouse/libs"
if [ ! -f "${gravitino_dir}/packages/${CLICKHOUSE_JDBC_DRIVER_NAME}" ]; then
  curl -L -s -o "${gravitino_dir}/packages/${CLICKHOUSE_JDBC_DRIVER_NAME}" "${CLICKHOUSE_JDBC_DOWNLOAD_URL}"
fi
cp "${gravitino_dir}/packages/${CLICKHOUSE_JDBC_DRIVER_NAME}" "${gravitino_clickhouse_catalog_dir}"

# Keeping the container running at all times
cat <<EOF >> "${gravitino_dir}/packages/gravitino/bin/gravitino.sh"

# Keeping a process running in the background
tail -f /dev/null
EOF