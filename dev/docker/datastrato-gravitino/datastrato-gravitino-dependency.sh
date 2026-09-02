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

OCEANBASE_JDBC_VERSION=${OCEANBASE_JDBC_VERSION:-"2.4.18"}
OCEANBASE_JDBC_DRIVER_NAME="oceanbase-client-${OCEANBASE_JDBC_VERSION}.jar"
OCEANBASE_JDBC_DOWNLOAD_URL="https://repo1.maven.org/maven2/com/oceanbase/oceanbase-client/${OCEANBASE_JDBC_VERSION}/${OCEANBASE_JDBC_DRIVER_NAME}"

# Paimon filesystem jars. These are self-contained shaded jars (bundle hadoop-aws /
# hadoop-aliyun and the cloud SDKs internally). The version is read from
# gradle/libs.versions.toml so the downloaded paimon-s3 / paimon-oss jars always match
# the paimon version Gradle packages into the catalog.
PAIMON_VERSION=$(grep -E "^paimon[[:space:]]*=" "${gravitino_home}/gradle/libs.versions.toml" | head -1 | sed -E "s/.*=[[:space:]]*['\"]([^'\"]+)['\"].*/\1/")
if [ -z "${PAIMON_VERSION}" ]; then
  echo "ERROR: failed to resolve paimon version from gradle/libs.versions.toml"
  exit 1
fi
PAIMON_S3_JAR_NAME="paimon-s3-${PAIMON_VERSION}.jar"
PAIMON_S3_DOWNLOAD_URL="https://repo1.maven.org/maven2/org/apache/paimon/paimon-s3/${PAIMON_VERSION}/${PAIMON_S3_JAR_NAME}"
PAIMON_OSS_JAR_NAME="paimon-oss-${PAIMON_VERSION}.jar"
PAIMON_OSS_DOWNLOAD_URL="https://repo1.maven.org/maven2/org/apache/paimon/paimon-oss/${PAIMON_VERSION}/${PAIMON_OSS_JAR_NAME}"

# Prepare compile Gravitino packages
"${gravitino_home}"/gradlew clean
"${gravitino_home}"/gradlew compileDistribution -x test -x :docs:build -x :docs-enterprise:build -x :clients:client-python:build

# Removed old packages, Avoid multiple re-executions using the wrong file
rm -rf "${gravitino_dir}/packages"
mkdir -p "${gravitino_dir}/packages"

cp -r "${gravitino_home}/distribution/package-all" "${gravitino_dir}/packages/gravitino"

# make sure bundles are built
"${gravitino_home}"/gradlew :bundles:gcp:build :bundles:gcp-bundle:build :bundles:iceberg-gcp-bundle:build :bundles:aws:build :bundles:aws-bundle:build :bundles:iceberg-aws-bundle:build :bundles:azure:build :bundles:azure-bundle:build :bundles:iceberg-azure-bundle:build :bundles:aliyun:build :bundles:aliyun-bundle:build :bundles:iceberg-aliyun-bundle:build :bundles:tencent:build :bundles:tencent-bundle:build -x test

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

# Helper: download a file with validation. Downloads to a .tmp file first,
# then moves into place only on success. Prevents caching corrupt/partial files
# when HTTP errors (4xx/5xx) occur.
download_file() {
  local target_file="$1"
  local url="$2"
  if [ ! -f "${target_file}" ]; then
    curl -L --fail --show-error -o "${target_file}.tmp" "${url}"
    mv "${target_file}.tmp" "${target_file}"
  fi
}

# Download JDBC drivers to dedicated directory
download_file "${jdbc_driver_dir}/${MYSQL_JDBC_DRIVER_NAME}" "${MYSQL_JDBC_DRIVER_DOWNLOAD_URL}"
download_file "${jdbc_driver_dir}/${POSTGRESQL_JDBC_DRIVER_NAME}" "${POSTGRESQL_JDBC_DRIVER_DOWNLOAD_URL}"

# Download GCS connector to dedicated directory
download_file "${gcs_connector_dir}/${GCS_CONNECTOR_NAME}" "${GCS_CONNECTOR_DOWNLOAD_URL}"

# Download and install BigQuery Simba JDBC driver
gravitino_bigquery_catalog_dir="${gravitino_dir}/packages/gravitino/catalogs/jdbc-bigquery/libs"
simba_tmp_dir="${gravitino_dir}/packages/simba-bigquery-tmp"
download_file "${gravitino_dir}/packages/${SIMBA_BIGQUERY_ZIP_NAME}" "${SIMBA_BIGQUERY_DOWNLOAD_URL}"
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
download_file "${gravitino_dir}/packages/${ODPS_JDBC_DRIVER_NAME}" "${ODPS_JDBC_DOWNLOAD_URL}"
cp "${gravitino_dir}/packages/${ODPS_JDBC_DRIVER_NAME}" "${gravitino_maxcompute_catalog_dir}"

# Download and install ClickHouse JDBC driver
gravitino_clickhouse_catalog_dir="${gravitino_dir}/packages/gravitino/catalogs/jdbc-clickhouse/libs"
download_file "${gravitino_dir}/packages/${CLICKHOUSE_JDBC_DRIVER_NAME}" "${CLICKHOUSE_JDBC_DOWNLOAD_URL}"
cp "${gravitino_dir}/packages/${CLICKHOUSE_JDBC_DRIVER_NAME}" "${gravitino_clickhouse_catalog_dir}"

# Download and install OceanBase JDBC driver (supports com.oceanbase.jdbc.Driver)
gravitino_oceanbase_catalog_dir="${gravitino_dir}/packages/gravitino/catalogs/jdbc-oceanbase/libs"
download_file "${gravitino_dir}/packages/${OCEANBASE_JDBC_DRIVER_NAME}" "${OCEANBASE_JDBC_DOWNLOAD_URL}"
cp "${gravitino_dir}/packages/${OCEANBASE_JDBC_DRIVER_NAME}" "${gravitino_oceanbase_catalog_dir}"

# Download and install Paimon S3/OSS FileIO jars so the lakehouse-paimon catalog can
# access s3:// and oss:// warehouses out of the box. Paimon does not bundle these by
# default (same policy as upstream Paimon), so they are added to the image here.
paimon_lib_dir="${gravitino_dir}/packages/gravitino/catalogs/lakehouse-paimon/libs"
mkdir -p "${paimon_lib_dir}"
download_file "${gravitino_dir}/packages/${PAIMON_S3_JAR_NAME}" "${PAIMON_S3_DOWNLOAD_URL}"
cp "${gravitino_dir}/packages/${PAIMON_S3_JAR_NAME}" "${paimon_lib_dir}"
download_file "${gravitino_dir}/packages/${PAIMON_OSS_JAR_NAME}" "${PAIMON_OSS_DOWNLOAD_URL}"
cp "${gravitino_dir}/packages/${PAIMON_OSS_JAR_NAME}" "${paimon_lib_dir}"

# Copy the AWS and Aliyun bundles to the Paimon catalog libs for S3 / OSS credential vending.
# Fail the build if a required bundle is missing (find -exec cp would otherwise exit 0
# and silently ship an image without credential-vending support).
copy_required_bundle() {
  local bundle_dir="$1"
  local pattern="$2"
  local dest_dir="$3"
  local jar
  jar=$(find "${bundle_dir}" -name "${pattern}" ! -name '*-empty.jar' | head -1)
  if [ -z "${jar}" ]; then
    echo "ERROR: required bundle '${pattern}' not found in ${bundle_dir}"
    exit 1
  fi
  cp -v "${jar}" "${dest_dir}"
}
copy_required_bundle "${gravitino_home}/bundles/aws-bundle/build/libs" 'gravitino-aws-bundle-*.jar' "${paimon_lib_dir}"
copy_required_bundle "${gravitino_home}/bundles/aliyun-bundle/build/libs" 'gravitino-aliyun-bundle-*.jar' "${paimon_lib_dir}"

# Keeping the container running at all times
cat <<EOF >> "${gravitino_dir}/packages/gravitino/bin/gravitino.sh"

# Keeping a process running in the background
tail -f /dev/null
EOF
