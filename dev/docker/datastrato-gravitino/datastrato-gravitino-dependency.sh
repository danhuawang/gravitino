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
gravitino_iceberg_rest_dir="${gravitino_dir}/packages/gravitino/iceberg-rest-server/libs/"
gravitino_iceberg_catalog_dir="${gravitino_dir}/packages/gravitino/catalogs/lakehouse-iceberg/libs"

MYSQL_JDBC_DRIVER_VERSION=${MYSQL_VERSION:-"8.0.26"}
MYSQL_JDBC_DRIVER_NAME="mysql-connector-java-${MYSQL_JDBC_DRIVER_VERSION}.jar"
MYSQL_JDBC_DIVER_DOWNLOAD_URL="https://repo1.maven.org/maven2/mysql/mysql-connector-java/${MYSQL_JDBC_DRIVER_VERSION}/${MYSQL_JDBC_DRIVER_NAME}"

POSTGRESQL_JDBC_DRIVER_VERSION=${POSTGRESQL_VERSION:-"42.7.0"}
POSTGRESQL_JDBC_DRIVER_NAME="postgresql-${POSTGRESQL_JDBC_DRIVER_VERSION}.jar"
POSTGRESQL_JDBC_DRIVER_DOWNLOAD_URL="https://jdbc.postgresql.org/download/${POSTGRESQL_JDBC_DRIVER_NAME}"

ICEBERG_VERSION=${ICEBERG_VERSION:-"1.9.2"}
ICEBERG_AWS_BUNDLE_NAME="iceberg-aws-bundle-${ICEBERG_VERSION}.jar"
ICEBERG_AWS_BUNDLE_DOWNLOAD_URL="https://repo1.maven.org/maven2/org/apache/iceberg/iceberg-aws-bundle/${ICEBERG_VERSION}/${ICEBERG_AWS_BUNDLE_NAME}"
ICEBERG_GCP_BUNDLE_NAME="iceberg-gcp-bundle-${ICEBERG_VERSION}.jar"
ICEBERG_GCP_BUNDLE_DOWNLOAD_URL="https://repo1.maven.org/maven2/org/apache/iceberg/iceberg-gcp-bundle/${ICEBERG_VERSION}/${ICEBERG_GCP_BUNDLE_NAME}"
ICEBERG_AZURE_BUNDLE_NAME="iceberg-azure-bundle-${ICEBERG_VERSION}.jar"
ICEBERG_AZURE_BUNDLE_DOWNLOAD_URL="https://repo1.maven.org/maven2/org/apache/iceberg/iceberg-azure-bundle/${ICEBERG_VERSION}/${ICEBERG_AZURE_BUNDLE_NAME}"

GCS_CONNECTOR_VERSION=${GCS_CONNECTOR_VERSION:-"hadoop2-2.2.18"}
GCS_CONNECTOR_VERSION_SHORT=${GCS_CONNECTOR_VERSION_SHORT:-"2.2.18"}
GCS_CONNECTOR_NAME="gcs-connector-${GCS_CONNECTOR_VERSION}-shaded.jar"
GCS_CONNECTOR_DOWNLOAD_URL="https://github.com/GoogleCloudDataproc/hadoop-connectors/releases/download/v${GCS_CONNECTOR_VERSION_SHORT}/${GCS_CONNECTOR_NAME}"

# make sure the submodule is initialized
git -C "${gravitino_home}" submodule update --init --recursive

# Prepare compile Gravitino packages
"${gravitino_home}"/gradlew clean
"${gravitino_home}"/gradlew compileDistribution -x test -x :gravitino-oss:docs:build -x :gravitino-oss:clients:client-python:build


# Removed old packages, Avoid multiple re-executions using the wrong file
rm -rf "${gravitino_dir}/packages"
mkdir -p "${gravitino_dir}/packages"

cp -r "${gravitino_home}/distribution/package" "${gravitino_dir}/packages/gravitino"

# make sure bundles are built
"${gravitino_home}"/gradlew -p gravitino-oss build -x test -x clients:client-python:build
# Copy the all file system bundles to the Hadoop catalog libs
cp -r ${gravitino_home}/gravitino-oss/bundles/*-bundle/build/libs/*.jar "${gravitino_dir}/packages/gravitino/catalogs/fileset/libs"

# Copy the all file system bundles to the Iceberg REST server libs
find ${gravitino_home}/gravitino-oss/bundles/gcp/build/libs/ -name 'gravitino-gcp-*.jar' ! -name '*-empty.jar' -exec cp -v {} "${gravitino_iceberg_rest_dir}" \;
find ${gravitino_home}/gravitino-oss/bundles/aws/build/libs/ -name 'gravitino-aws-*.jar' ! -name '*-empty.jar' -exec cp -v {} "${gravitino_iceberg_rest_dir}" \;
find ${gravitino_home}/gravitino-oss/bundles/azure/build/libs/ -name 'gravitino-azure-*.jar' ! -name '*-empty.jar' -exec cp -v {} "${gravitino_iceberg_rest_dir}" \;
find ${gravitino_home}/gravitino-oss/bundles/aliyun-bundle/build/libs/ -name 'gravitino-aliyun-bundle-*.jar' ! -name '*-empty.jar' -exec cp -v {} "${gravitino_iceberg_rest_dir}" \;

# Copy the all file system bundles to the Iceberg catalog libs
find ${gravitino_home}/gravitino-oss/bundles/gcp/build/libs/ -name 'gravitino-gcp-*.jar' ! -name '*-empty.jar' -exec cp -v {} "${gravitino_iceberg_catalog_dir}" \;
find ${gravitino_home}/gravitino-oss/bundles/aws/build/libs/ -name 'gravitino-aws-*.jar' ! -name '*-empty.jar' -exec cp -v {} "${gravitino_iceberg_catalog_dir}" \;
find ${gravitino_home}/gravitino-oss/bundles/azure/build/libs/ -name 'gravitino-azure-*.jar' ! -name '*-empty.jar' -exec cp -v {} "${gravitino_iceberg_catalog_dir}" \;
find ${gravitino_home}/gravitino-oss/bundles/aliyun-bundle/build/libs/ -name 'gravitino-aliyun-bundle-*.jar' ! -name '*-empty.jar' -exec cp -v {} "${gravitino_iceberg_catalog_dir}" \;

if [ ! -f "${gravitino_dir}/packages/${MYSQL_JDBC_DRIVER_NAME}" ]; then
  curl -L -s -o "${gravitino_dir}/packages/${MYSQL_JDBC_DRIVER_NAME}" "${MYSQL_JDBC_DIVER_DOWNLOAD_URL}"
fi

if [ ! -f "${gravitino_dir}/packages/${POSTGRESQL_JDBC_DRIVER_NAME}" ]; then
  curl -L -s -o "${gravitino_dir}/packages/${POSTGRESQL_JDBC_DRIVER_NAME}" "${POSTGRESQL_JDBC_DRIVER_DOWNLOAD_URL}"
fi

if [ ! -f "${gravitino_dir}/packages/${ICEBERG_AWS_BUNDLE_NAME}" ]; then
  curl -L -s -o "${gravitino_dir}/packages/${ICEBERG_AWS_BUNDLE_NAME}" "${ICEBERG_AWS_BUNDLE_DOWNLOAD_URL}"
fi

if [ ! -f "${gravitino_dir}/packages/${ICEBERG_GCP_BUNDLE_NAME}" ]; then
  curl -L -s -o "${gravitino_dir}/packages/${ICEBERG_GCP_BUNDLE_NAME}" "${ICEBERG_GCP_BUNDLE_DOWNLOAD_URL}"
fi

if [ ! -f "${gravitino_dir}/packages/${ICEBERG_AZURE_BUNDLE_NAME}" ]; then
  curl -L -s -o "${gravitino_dir}/packages/${ICEBERG_AZURE_BUNDLE_NAME}" "${ICEBERG_AZURE_BUNDLE_DOWNLOAD_URL}"
fi

if [ ! -f "${gravitino_dir}/packages/${GCS_CONNECTOR_NAME}" ]; then
  curl -L -s -o "${gravitino_dir}/packages/${GCS_CONNECTOR_NAME}" "${GCS_CONNECTOR_DOWNLOAD_URL}"
fi

# Keeping the container running at all times
cat <<EOF >> "${gravitino_dir}/packages/gravitino/bin/gravitino.sh"

# Keeping a process running in the background
tail -f /dev/null
EOF