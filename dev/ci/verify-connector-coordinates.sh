#!/usr/bin/env bash
#
# Copyright 2026 Datastrato Inc.
#
# Regression guard for the enterprise connector Maven publishing configuration.
# Installs every connector ext coordinate into a throwaway local repo and asserts
# that all 14 expected artifacts exist with the right packaging (jar for Spark/Flink,
# zip for the Trino bands). This exercises artifactId, packaging, POM stripping and
# module-metadata handling that no other CI job covers, so a future change to that
# config fails here instead of during a release.
#
# No secrets required: publishToMavenLocal skips Gradle signing.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
gravitino_home="$(cd "${script_dir}/../.." >/dev/null 2>&1 && pwd)"
cd "${gravitino_home}"

tmp_repo="$(mktemp -d)"
trap 'rm -rf "${tmp_repo}"' EXIT

version="$(grep -E '^version[[:space:]]*=' gradle.properties | head -1 | sed -E 's/.*=[[:space:]]*//' | tr -d '[:space:]')"
echo "Verifying connector coordinates for version ${version}"

# Keep GPG env (if any) out of the Gradle run; publishToMavenLocal must not sign.
gradle() {
  # shellcheck disable=SC2086
  env -u GPG_ID -u GPG_PRIVATE_KEY -u GPG_PASSPHRASE \
    ./gradlew -Dmaven.repo.local="${tmp_repo}" "$@"
}

# Spark 2.12 (3.3/3.4/3.5) + 2.13 (3.4/3.5), Flink 2.12, Trino bands.
gradle -PscalaVersion=2.12 \
  :spark-connector:spark-ext-3.3:publishToMavenLocal \
  :spark-connector:spark-ext-3.4:publishToMavenLocal \
  :spark-connector:spark-ext-3.5:publishToMavenLocal \
  :flink-connector:flink-ext-1.18:publishToMavenLocal \
  :flink-connector:flink-ext-1.19:publishToMavenLocal \
  :flink-connector:flink-ext-1.20:publishToMavenLocal \
  :trino-connector:trino-connector-ext:publishToMavenLocal \
  -x test
gradle -PscalaVersion=2.13 \
  :spark-connector:spark-ext-3.4:publishToMavenLocal \
  :spark-connector:spark-ext-3.5:publishToMavenLocal \
  -x test

jar_artifacts=(
  datastrato-flink-connector-ext-1.18_2.12
  datastrato-flink-connector-ext-1.19_2.12
  datastrato-flink-connector-ext-1.20_2.12
  datastrato-spark-connector-ext-3.3_2.12
  datastrato-spark-connector-ext-3.4_2.12
  datastrato-spark-connector-ext-3.4_2.13
  datastrato-spark-connector-ext-3.5_2.12
  datastrato-spark-connector-ext-3.5_2.13
)
zip_artifacts=(
  datastrato-trino-connector-ext-435-439
  datastrato-trino-connector-ext-440-445
  datastrato-trino-connector-ext-446-451
  datastrato-trino-connector-ext-452-468
  datastrato-trino-connector-ext-469-472
  datastrato-trino-connector-ext-473-478
)

base="${tmp_repo}/com/datastrato"
fail=0

check_artifact() {
  local id="$1" ext="$2"
  local dir="${base}/${id}/${version}"
  local artifact="${dir}/${id}-${version}.${ext}"
  local pom="${dir}/${id}-${version}.pom"
  if [[ ! -f "${artifact}" ]]; then
    echo "MISSING artifact: ${id}-${version}.${ext}" >&2
    fail=1
    return
  fi
  if [[ ! -f "${pom}" ]]; then
    echo "MISSING pom: ${id}-${version}.pom" >&2
    fail=1
    return
  fi
  # POM must not carry dependencies (runtime jar / self-contained zip, not a library).
  if grep -q "<dependencies>" "${pom}"; then
    echo "UNEXPECTED <dependencies> in ${id} POM" >&2
    fail=1
  fi
  # Maven Central rejects a release missing sources/javadoc. For the Trino bands these come
  # from a different module (trino-connector-ext), which is the wiring most likely to break.
  if [[ ! -f "${dir}/${id}-${version}-sources.jar" ]]; then
    echo "MISSING sources jar: ${id}-${version}-sources.jar" >&2
    fail=1
  fi
  if [[ ! -f "${dir}/${id}-${version}-javadoc.jar" ]]; then
    echo "MISSING javadoc jar: ${id}-${version}-javadoc.jar" >&2
    fail=1
  fi
  # Gradle Module Metadata must stay disabled: the publish script deletes .module files during
  # its prune step rather than failing, so a regression re-enabling it would slip through a
  # release. Assert none was produced here.
  if compgen -G "${dir}/*.module" >/dev/null; then
    echo "UNEXPECTED .module (Gradle Module Metadata) for ${id}" >&2
    fail=1
  fi
  echo "OK  ${id}-${version}.${ext}"
}

for id in "${jar_artifacts[@]}"; do check_artifact "${id}" jar; done
for id in "${zip_artifacts[@]}"; do
  check_artifact "${id}" zip
  # Trino coordinates must declare packaging=zip.
  pom="${base}/${id}/${version}/${id}-${version}.pom"
  if [[ -f "${pom}" ]] && ! grep -q "<packaging>zip</packaging>" "${pom}"; then
    echo "MISSING <packaging>zip</packaging> in ${id} POM" >&2
    fail=1
  fi
done

if [[ "${fail}" -ne 0 ]]; then
  echo "Connector coordinate verification FAILED." >&2
  exit 1
fi
echo "All 14 connector coordinates verified."
