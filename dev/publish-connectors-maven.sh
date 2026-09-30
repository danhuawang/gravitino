#!/usr/bin/env bash
#
# Copyright 2026 Datastrato Inc.
#
# Publish the Datastrato Enterprise engine connectors to Maven Central via the
# Sonatype Central Portal Publisher API (https://central.sonatype.com).
#
# What it publishes (groupId com.datastrato). The version defaults to the gradle.properties
# base version and may be overridden with --version to a release form of that same base
# (see --version below):
#   Spark  (Scala 2.12): datastrato-spark-connector-ext-3.3_2.12
#                        datastrato-spark-connector-ext-3.4_2.12
#                        datastrato-spark-connector-ext-3.5_2.12
#   Spark  (Scala 2.13): datastrato-spark-connector-ext-3.4_2.13
#                        datastrato-spark-connector-ext-3.5_2.13
#   Flink  (Scala 2.12): datastrato-flink-connector-ext-1.18_2.12
#                        datastrato-flink-connector-ext-1.19_2.12
#                        datastrato-flink-connector-ext-1.20_2.12
#   Trino  (zip/band):   datastrato-trino-connector-ext-435-439 ... -473-478 (6 bands)
#
# Spark/Flink ship a shaded runtime jar; Trino ships an installable plugin zip
# per version band. The build config lives in the *-connector/ext modules.
#
# Pipeline: publishToMavenLocal into a temp repo -> GPG-sign every file ->
# assemble a single Central Portal bundle zip (Maven layout) -> upload with a
# Bearer user token -> poll deployment status.
#
# Required environment variables (fail fast if unset):
#   CENTRAL_TOKEN_USERNAME   Central Portal user-token username
#   CENTRAL_TOKEN_PASSWORD   Central Portal user-token password
#   GPG_PRIVATE_KEY          ASCII-armored PGP private key (used to sign artifacts)
#   GPG_PASSPHRASE           passphrase for that key
#   GPG_ID                   key id / fingerprint to sign with (optional if the
#                            keyring has a single secret key)
#
# Usage:
#   dev/publish-connectors-maven.sh [--version X.Y.Z] [--auto-release] [--dry-run] [--engines spark,flink,trino]
#
#   --version X.Y.Z  version to publish, overriding gradle.properties without editing it.
#                    Must be the gradle.properties base version or a release candidate of it:
#                    e.g. with base 1.3.0, only 1.3.0 or 1.3.0-rc<N> are accepted (not 1.3.1,
#                    not v1.3.0). This keeps a published, immutable coordinate mapped to this
#                    source tree. Bump gradle.properties to change the base. Defaults to the
#                    base version when omitted.
#   --auto-release   upload with publishingType=AUTOMATIC (validate then publish
#                    to Maven Central without a manual step). Default is
#                    USER_MANAGED: the deployment stops at VALIDATED and a human
#                    clicks Publish in the Portal UI. Use manual for RCs.
#   --dry-run        build, sign and assemble the bundle but do NOT upload.
#   --engines LIST   comma-separated subset of {spark,flink,trino} to publish.
#                    Default: all three.

set -euo pipefail

# --------------------------------------------------------------------------- #
# Locations
# --------------------------------------------------------------------------- #
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
gravitino_home="$(cd "${script_dir}/.." >/dev/null 2>&1 && pwd)"
cd "${gravitino_home}"

GRADLE="./gradlew"
GROUP_ID="com.datastrato"
GROUP_PATH="com/datastrato"
CENTRAL_UPLOAD_URL="https://central.sonatype.com/api/v1/publisher/upload"
CENTRAL_STATUS_URL="https://central.sonatype.com/api/v1/publisher/status"

# --------------------------------------------------------------------------- #
# Arguments
# --------------------------------------------------------------------------- #
publishing_type="USER_MANAGED"
dry_run=0
engines="spark,flink,trino"
version_override=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --auto-release) publishing_type="AUTOMATIC"; shift ;;
    --dry-run)      dry_run=1; shift ;;
    --engines)      engines="$2"; shift 2 ;;
    --version)      version_override="$2"; shift 2 ;;
    -h|--help)
      # Print the whole header comment (lines 2 until the first `set -euo pipefail`),
      # so the flag list can't drift out of the printed range.
      awk 'NR>1 && /^set -euo pipefail/ {exit} NR>1 {sub(/^# ?/, ""); print}' "${BASH_SOURCE[0]}"
      exit 0 ;;
    *) echo "ERROR: unknown argument: $1" >&2; exit 1 ;;
  esac
done

want_engine() { [[ ",${engines}," == *",$1,"* ]]; }

# --------------------------------------------------------------------------- #
# Preconditions
# --------------------------------------------------------------------------- #
require_tool() {
  command -v "$1" >/dev/null 2>&1 || { echo "ERROR: required tool not found: $1" >&2; exit 1; }
}
require_tool curl
require_tool zip
require_tool gpg
require_tool jq

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "ERROR: required environment variable ${name} is not set." >&2
    exit 1
  fi
}
if [[ "${dry_run}" -eq 0 ]]; then
  require_env CENTRAL_TOKEN_USERNAME
  require_env CENTRAL_TOKEN_PASSWORD
fi
# Signing is always required by Maven Central, so we sign even for --dry-run to
# prove the key works before a real upload.
require_env GPG_PRIVATE_KEY
require_env GPG_PASSPHRASE

# Resolve and validate the version to publish.
#
# The base version always comes from gradle.properties (minus any -SNAPSHOT); this is the
# provenance the build carries. --version may only select a release form of that same base:
# the base itself (e.g. 1.3.0) or a release-candidate suffix (e.g. 1.3.0-rc1). It may NOT
# invent a different base (1.3.1 from a 1.3.0 tree) or contain unexpected characters, so a
# published, immutable coordinate always maps back to this source tree.
source_version="$(grep -E '^version[[:space:]]*=' gradle.properties | head -1 | sed -E 's/.*=[[:space:]]*//' | tr -d '[:space:]')"
if [[ -z "${source_version}" ]]; then
  echo "ERROR: could not read version from gradle.properties" >&2
  exit 1
fi
base_version="${source_version%-SNAPSHOT}"

if [[ -n "${version_override}" ]]; then
  version="${version_override}"
else
  version="${base_version}"
fi

# Reject anything that is not the base version or a base version + -rc<N> suffix. The dots in
# base_version are escaped so they match literally (otherwise 1.3.0 would also accept 1x3y0).
base_version_re="${base_version//./\\.}"
if [[ ! "${version}" =~ ^${base_version_re}(-rc[0-9]+)?$ ]]; then
  echo "ERROR: invalid version '${version}'." >&2
  echo "Must be the source base version '${base_version}' or a release candidate '${base_version}-rc<N>'" >&2
  echo "(source version in gradle.properties is '${source_version}'). Bump gradle.properties to change the base." >&2
  exit 1
fi

# Maven Central releases are immutable: a version can never be re-published or replaced.
# Best-effort guard against re-releasing an existing version: probe one already-released
# coordinate per requested engine on Maven Central and fail fast if it is already there.
#
# Limits (why this is best-effort, not a guarantee): repo1.maven.org lags the Central Portal
# by 10-30 minutes, and in USER_MANAGED mode a deployment sits at VALIDATED (never reaching
# repo1) until a human publishes it - so a very recent or not-yet-published version is not
# detected here. The authoritative check is Central itself, which rejects a duplicate released
# version at publish time. (Skipped in --dry-run since nothing is uploaded.)
if [[ "${dry_run}" -eq 0 ]]; then
  probe_coords=()
  want_engine spark && probe_coords+=("datastrato-spark-connector-ext-3.5_2.12")
  want_engine flink && probe_coords+=("datastrato-flink-connector-ext-1.20_2.12")
  want_engine trino && probe_coords+=("datastrato-trino-connector-ext-435-439")
  for coord in ${probe_coords[@]+"${probe_coords[@]}"}; do
    if [[ "${coord}" == *trino* ]]; then ext=zip; else ext=jar; fi
    probe_url="https://repo1.maven.org/maven2/${GROUP_PATH}/${coord}/${version}/${coord}-${version}.${ext}"
    probe_code="$(curl -s -o /dev/null -w '%{http_code}' -I "${probe_url}" || echo 000)"
    if [[ "${probe_code}" == "200" ]]; then
      echo "ERROR: version ${version} already exists on Maven Central (${probe_url})." >&2
      echo "Central releases are immutable; publish a new version instead (e.g. via --version)." >&2
      exit 1
    fi
  done
fi

echo "Publishing enterprise connectors version ${version} (engines: ${engines}, type: ${publishing_type})"

# --------------------------------------------------------------------------- #
# GPG: import the private key into a throwaway keyring
# --------------------------------------------------------------------------- #
GNUPGHOME="$(mktemp -d)"
export GNUPGHOME
chmod 700 "${GNUPGHOME}"
cleanup() { rm -rf "${GNUPGHOME}" "${tmp_repo:-}" "${bundle_root:-}" "${http_body:-}"; }
trap cleanup EXIT

printf '%s' "${GPG_PRIVATE_KEY}" | gpg --batch --import >/dev/null 2>&1 || {
  echo "ERROR: failed to import GPG_PRIVATE_KEY" >&2
  exit 1
}

gpg_sign() {
  # $1 = file to sign; produces $1.asc
  local file="$1"
  local args=(--batch --yes --pinentry-mode loopback --passphrase-fd 0 --armor --detach-sign)
  if [[ -n "${GPG_ID:-}" ]]; then
    args+=(--local-user "${GPG_ID}")
  fi
  printf '%s' "${GPG_PASSPHRASE}" | gpg "${args[@]}" --output "${file}.asc" "${file}"
}

# Portable md5 / sha1 helpers (Linux coreutils vs macOS/BSD).
md5_hex() {
  if command -v md5sum >/dev/null 2>&1; then md5sum "$1" | cut -d' ' -f1
  else md5 -q "$1"
  fi
}
sha1_hex() {
  if command -v sha1sum >/dev/null 2>&1; then sha1sum "$1" | cut -d' ' -f1
  else shasum -a 1 "$1" | cut -d' ' -f1
  fi
}

# --------------------------------------------------------------------------- #
# 1. Build + install to a temp local Maven repo across the version matrix
# --------------------------------------------------------------------------- #
tmp_repo="$(mktemp -d)"
echo "==> Installing artifacts to temporary repo: ${tmp_repo}"

# publishToMavenLocal honors -Dmaven.repo.local so we get a clean, isolated tree
# containing only what we build here.
#
# The GPG_* variables are cleared for the Gradle run: the root build wires an
# in-memory signing key from those env vars into every publication, so leaving
# them set would make Gradle sign the artifacts too (before this script's own
# gpg step), and an empty or fingerprint-form GPG_ID can break Gradle's key-id
# parsing. Signing is done solely by gpg_sign below, against the pruned tree.
gradle_install() {
  # Always pass -Pversion=${version} (the resolved, validated release version), not just for
  # an explicit --version. On a -SNAPSHOT source tree the default version is the base with
  # -SNAPSHOT stripped, so without this Gradle would build X-SNAPSHOT while the script labels,
  # signs and uploads it as the release X. Forcing -Pversion keeps the built artifacts, the
  # provenance check, the bundle and the Central coordinate all on the same version.
  # shellcheck disable=SC2086
  env -u GPG_ID -u GPG_PRIVATE_KEY -u GPG_PASSPHRASE \
    ${GRADLE} -Dmaven.repo.local="${tmp_repo}" \
    "-Pversion=${version}" \
    "$@"
}

if want_engine spark; then
  echo "==> Spark connectors (Scala 2.12)"
  gradle_install -PscalaVersion=2.12 \
    :spark-connector:spark-ext-3.3:publishToMavenLocal \
    :spark-connector:spark-ext-3.4:publishToMavenLocal \
    :spark-connector:spark-ext-3.5:publishToMavenLocal \
    -x test
  echo "==> Spark connectors (Scala 2.13; 3.4 and 3.5 only)"
  gradle_install -PscalaVersion=2.13 \
    :spark-connector:spark-ext-3.4:publishToMavenLocal \
    :spark-connector:spark-ext-3.5:publishToMavenLocal \
    -x test
fi

if want_engine flink; then
  echo "==> Flink connectors (Scala 2.12 only)"
  gradle_install \
    :flink-connector:flink-ext-1.18:publishToMavenLocal \
    :flink-connector:flink-ext-1.19:publishToMavenLocal \
    :flink-connector:flink-ext-1.20:publishToMavenLocal \
    -x test
fi

if want_engine trino; then
  echo "==> Trino connector plugin zips (6 version bands)"
  # No -PtrinoVersion here: each band module defaults to its own supported range,
  # so passing a single global version would fail the other bands' range checks.
  gradle_install \
    :trino-connector:trino-connector-ext:publishToMavenLocal \
    -x test
fi

# --------------------------------------------------------------------------- #
# 2. Prune to publishable files, then sign + checksum everything
# --------------------------------------------------------------------------- #
if [[ ! -d "${tmp_repo}/${GROUP_PATH}" ]]; then
  echo "ERROR: nothing was installed under ${tmp_repo}/${GROUP_PATH}" >&2
  exit 1
fi

# Provenance post-condition: what was actually built must carry exactly ${version}. This
# catches a -Pversion override that failed to apply to some module (leaving a different
# version directory) before an immutable, mislabelled coordinate is uploaded.
if ! find "${tmp_repo}/${GROUP_PATH}" -mindepth 2 -maxdepth 2 -type d -name "${version}" | grep -q .; then
  echo "ERROR: no artifacts with version ${version} were produced under ${tmp_repo}/${GROUP_PATH}." >&2
  exit 1
fi
unexpected="$(find "${tmp_repo}/${GROUP_PATH}" -mindepth 2 -maxdepth 2 -type d ! -name "${version}")"
if [[ -n "${unexpected}" ]]; then
  echo "ERROR: found artifacts with a version other than ${version} (the -Pversion override did not apply everywhere):" >&2
  printf '%s\n' "${unexpected}" >&2
  exit 1
fi

pushd "${tmp_repo}/${GROUP_PATH}" >/dev/null

# Keep only artifacts Central accepts: jars, the zip plugin bundles, and poms.
# Drop Gradle module metadata and maven-metadata (Central rejects/ignores them).
find . -type f \
  ! -name '*.jar' \
  ! -name '*.zip' \
  ! -name '*.pom' \
  -delete
find . -type f -name 'maven-metadata*' -delete

echo "==> Signing and checksumming artifacts"
# Sign then checksum. Do not checksum or sign the .asc files themselves.
while IFS= read -r -d '' file; do
  gpg_sign "${file}"
  md5_hex "${file}"  > "${file}.md5"
  sha1_hex "${file}" > "${file}.sha1"
done < <(find . -type f ! -name '*.asc' ! -name '*.md5' ! -name '*.sha1' -print0)

popd >/dev/null

# --------------------------------------------------------------------------- #
# 3. Assemble the Central Portal bundle (Maven-layout zip rooted at groupId dirs)
# --------------------------------------------------------------------------- #
bundle_root="$(mktemp -d)"
bundle_zip="${gravitino_home}/distribution/central-bundle-connectors-${version}.zip"
mkdir -p "$(dirname "${bundle_zip}")"
rm -f "${bundle_zip}"

# The bundle must contain the full path from the repository root (com/datastrato/...).
mkdir -p "${bundle_root}/${GROUP_PATH}"
cp -R "${tmp_repo}/${GROUP_PATH}/." "${bundle_root}/${GROUP_PATH}/"

( cd "${bundle_root}" && zip -qr "${bundle_zip}" "com" )
echo "==> Bundle assembled: ${bundle_zip}"
echo "    Coordinates included:"
find "${tmp_repo}/${GROUP_PATH}" -name '*.pom' | sed -E "s#${tmp_repo}/##; s#/[^/]+\.pom##" | sort -u | sed 's/^/      - /'

if [[ "${dry_run}" -eq 1 ]]; then
  echo "==> --dry-run set: skipping upload. Bundle left at ${bundle_zip}"
  # Keep the bundle for inspection; only clear the temp working dirs.
  trap - EXIT
  rm -rf "${GNUPGHOME}" "${tmp_repo}" "${bundle_root}"
  exit 0
fi

# --------------------------------------------------------------------------- #
# 4. Upload to the Central Portal and poll status
# --------------------------------------------------------------------------- #
auth="$(printf '%s:%s' "${CENTRAL_TOKEN_USERNAME}" "${CENTRAL_TOKEN_PASSWORD}" | base64 | tr -d '\n')"
deployment_name="datastrato-connectors-${version}"

echo "==> Uploading bundle to Central Portal (publishingType=${publishing_type})"
http_body="$(mktemp)"
http_code="$(curl -sS -o "${http_body}" -w '%{http_code}' \
  --request POST \
  --header "Authorization: Bearer ${auth}" \
  --form "bundle=@${bundle_zip}" \
  "${CENTRAL_UPLOAD_URL}?name=${deployment_name}&publishingType=${publishing_type}")"

deployment_id="$(cat "${http_body}")"
rm -f "${http_body}"

if [[ "${http_code}" != "201" || -z "${deployment_id}" ]]; then
  echo "ERROR: upload failed (HTTP ${http_code}): ${deployment_id}" >&2
  exit 1
fi
echo "    Deployment ID: ${deployment_id}"

# The state we wait for depends on the publishing type. In USER_MANAGED the
# deployment stops at VALIDATED (a human publishes it from the UI). In AUTOMATIC
# it continues VALIDATING -> PUBLISHING -> PUBLISHED, so VALIDATED is NOT terminal
# and we must wait for PUBLISHED, or the run would report success before anything
# reaches Maven Central.
if [[ "${publishing_type}" == "AUTOMATIC" ]]; then
  success_state="PUBLISHED"
else
  success_state="VALIDATED"
fi

echo "==> Polling deployment status (waiting for ${success_state} or FAILED)"
terminal_state=""
for _ in $(seq 1 180); do   # up to ~30 min at 10s intervals
  sleep 10
  # A transient status-endpoint hiccup must not kill the run right after a
  # successful upload; tolerate a failed poll and try again next iteration.
  status_json="$(curl -sS \
    --request POST \
    --header "Authorization: Bearer ${auth}" \
    "${CENTRAL_STATUS_URL}?id=${deployment_id}" 2>/dev/null || true)"
  state="$(printf '%s' "${status_json}" | jq -r '.deploymentState // empty' 2>/dev/null || true)"
  echo "    state: ${state:-<unknown>}"
  case "${state}" in
    FAILED)
      echo "ERROR: deployment FAILED:" >&2
      printf '%s\n' "${status_json}" | jq '.errors // .' >&2 2>/dev/null || printf '%s\n' "${status_json}" >&2
      exit 1 ;;
    "${success_state}")
      terminal_state="${state}"; break ;;
  esac
done

if [[ -z "${terminal_state}" ]]; then
  echo "ERROR: timed out waiting for deployment ${deployment_id} to reach ${success_state}." >&2
  echo "Check https://central.sonatype.com/publishing/deployments" >&2
  exit 1
fi

echo ""
echo "=== Done ==="
if [[ "${publishing_type}" == "USER_MANAGED" ]]; then
  echo "Deployment ${deployment_id} is ${terminal_state}."
  echo "Review and click Publish at https://central.sonatype.com/publishing/deployments"
else
  echo "Deployment ${deployment_id} is ${terminal_state} (published automatically)."
  echo "It may take 10-30 min to become downloadable from Maven Central."
fi
