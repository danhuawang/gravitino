<!--
  Copyright 2026 Datastrato Inc.
-->

# Publishing the Enterprise connectors to Maven Central

The Datastrato Enterprise engine connectors are published to Maven Central
through the Sonatype **Central Portal** (`central.sonatype.com`) under the
`com.datastrato` namespace. Publishing is driven by
[`dev/publish-connectors-maven.sh`](publish-connectors-maven.sh), invoked either
locally or from the **Publish Enterprise Connectors to Maven Central** GitHub
Actions workflow (`.github/workflows/publish-connectors-maven.yml`).

## What gets published

The version defaults to the `gradle.properties` base version, and can be
overridden per run with the workflow `version` input (or the script's
`--version`). The override must be a release form of that same base — the base
itself or a release candidate `-rc<N>` (e.g. with base `1.3.0`, only `1.3.0` or
`1.3.0-rc1` are accepted, not `1.3.1`). `groupId` is `com.datastrato` for every
artifact.

| Engine | ArtifactId | Packaging | Notes |
|--------|------------|-----------|-------|
| Spark | `datastrato-spark-connector-ext-3.3_2.12` | jar | shaded runtime jar |
| Spark | `datastrato-spark-connector-ext-3.4_2.12` | jar | shaded runtime jar |
| Spark | `datastrato-spark-connector-ext-3.4_2.13` | jar | shaded runtime jar |
| Spark | `datastrato-spark-connector-ext-3.5_2.12` | jar | shaded runtime jar |
| Spark | `datastrato-spark-connector-ext-3.5_2.13` | jar | shaded runtime jar |
| Flink | `datastrato-flink-connector-ext-1.18_2.12` | jar | shaded runtime jar (Scala 2.12 only) |
| Flink | `datastrato-flink-connector-ext-1.19_2.12` | jar | shaded runtime jar (Scala 2.12 only) |
| Flink | `datastrato-flink-connector-ext-1.20_2.12` | jar | shaded runtime jar (Scala 2.12 only) |
| Trino | `datastrato-trino-connector-ext-435-439` | zip | installable plugin, one per version band |
| Trino | `datastrato-trino-connector-ext-440-445` | zip | installable plugin |
| Trino | `datastrato-trino-connector-ext-446-451` | zip | installable plugin |
| Trino | `datastrato-trino-connector-ext-452-468` | zip | installable plugin |
| Trino | `datastrato-trino-connector-ext-469-472` | zip | installable plugin |
| Trino | `datastrato-trino-connector-ext-473-478` | zip | installable plugin |

**Functional model.** The Spark/Flink jars and each Trino zip carry only what
Enterprise adds on top of the open source connector (the Oracle and SQL Server
adapters). For Spark and Flink the customer places the open source connector
runtime jar and the `datastrato-*-ext` jar on the engine classpath together. For
Trino the zip is self-contained: it unpacks to a single plugin directory that
already includes the open source connector jar, its dependencies, and the ext
jar, ready to drop under Trino's `plugin/`.

**Notes.**
- Maven Central releases are immutable — a version can never be replaced, so use
  a new version for each release (via `--version` or `gradle.properties`). As a
  best-effort guard the script probes Maven Central for an already-released
  coordinate of each requested engine and fails fast if the version is already
  there; note this cannot catch a very recent or not-yet-published version
  (repo1 lags Central, and a USER_MANAGED deployment stays at VALIDATED until
  published). Central itself is the authoritative check — it rejects a duplicate
  released version at publish time.
- The six Trino band coordinates all attach the ext module's `sources`/`javadoc`
  jars (the zip is an installable assembly, not a library, so it has no band
  connector sources of its own). This is intentional.

## How the version matrix is produced

Scala variants are built in separate Gradle runs (a run fixes one `scalaVersion`):

- Spark: `-PscalaVersion=2.12` for 3.3/3.4/3.5, then `-PscalaVersion=2.13` for
  3.4/3.5 (Spark 3.3 has no Scala 2.13 build).
- Flink: Scala 2.12 only.
- Trino: no `-PtrinoVersion` is passed, so each band module builds its own
  supported range; the six zips are produced in one run.

## Required credentials

Set these as GitHub Actions repository secrets (Settings → Secrets and variables
→ Actions), or as environment variables when running the script locally:

| Secret / env var | Purpose |
|------------------|---------|
| `CENTRAL_TOKEN_USERNAME` | Central Portal **user token** username |
| `CENTRAL_TOKEN_PASSWORD` | Central Portal **user token** password |
| `GPG_PRIVATE_KEY` | ASCII-armored PGP private key used to sign artifacts |
| `GPG_PASSPHRASE` | passphrase for that key |
| `GPG_ID` | key id / fingerprint to sign with (optional if the keyring holds one key) |
| `PUBLISH_CONNECTORS_TOKEN` | workflow guard; the value typed into the workflow input must match this secret |

The Central Portal credentials are a **user token**, not the login
username/password — generate it at https://central.sonatype.com. The GPG public
key must be distributed to a public keyserver, or Central cannot verify the
signatures.

## Manual vs automatic publishing

Central Portal splits publishing into upload → validation → publish. The script
defaults to **manual** (`USER_MANAGED`): after upload and validation the
deployment stops at `VALIDATED`, and a human clicks **Publish** in the
[Deployments](https://central.sonatype.com/publishing/deployments) UI. A
published version is permanent and cannot be overwritten, so keep manual mode
for release candidates.

Pass `--auto-release` (or set the workflow `auto-release` input to `true`) to use
`AUTOMATIC`, which publishes to Maven Central as soon as validation passes.

## Running it

From CI, dispatch the **Publish Enterprise Connectors to Maven Central**
workflow and set `version` (e.g. `1.3.0-rc1`), `engines`, and the confirmation
`token`; leave `auto-release` off for RCs. Locally the equivalent is:

```bash
# Manual (recommended for RC): upload + validate, then publish from the UI.
dev/publish-connectors-maven.sh

# Publish a specific version (must be the gradle.properties base or a -rc<N> of it).
dev/publish-connectors-maven.sh --version 1.3.0-rc1

# Automatic: publish to Maven Central without a manual step.
dev/publish-connectors-maven.sh --auto-release

# Only a subset of engines.
dev/publish-connectors-maven.sh --engines trino

# Build, sign and assemble the bundle without uploading (inspection only).
dev/publish-connectors-maven.sh --dry-run
```

## Verification

After a release, confirm each Trino band zip resolves (adjust the version):

```bash
VERSION=1.3.0-rc1   # the version you published
for b in 435-439 440-445 446-451 452-468 469-472 473-478; do
  printf "%-10s %s\n" "$b" "$(curl -s -o /dev/null -w '%{http_code}' \
    "https://repo1.maven.org/maven2/com/datastrato/datastrato-trino-connector-ext-$b/$VERSION/datastrato-trino-connector-ext-$b-$VERSION.zip")"
done
```

`unzip -l` on any band zip should list the connector jar, its dependency jars,
the enterprise ext jar, and the license files under one top-level directory.
