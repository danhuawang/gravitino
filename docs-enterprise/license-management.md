<!--
  Copyright 2024 Datastrato Pvt Ltd.
  This software is licensed under the Apache License version 2.
-->

# Gravitino Enterprise License Management

This document describes how to manage the Gravitino Enterprise license key lifecycle:
key storage, signing new licenses, verifying existing ones, building Gravitino with the
correct public key, and configuring a Gravitino server to start with a valid license.

---

## 1. Key management: private key, public key, test vs. prod

There are two independent key pairs: a **test key pair** used in CI builds and integration
tests, and a **production key pair** used exclusively for signing real customer licenses.
They are stored in separate GCP KMS key rings and separate paths in the private key
repository. **Never mix them.**

### Where the keys live

**Private keys — GCP KMS**

Both private keys live in GCP KMS and are never exported or stored outside of it.
Signing operations are performed remotely via the KMS API.

| Environment | GCP KMS key ring         | GCP KMS key name        |
|-------------|--------------------------|-------------------------|
| Test / CI   | `gravitino-license-test` | `gravitino-master-test` |
| Production  | `gravitino-license-prod` | `gravitino-master-prod` |

Access to each KMS key is controlled by GCP IAM. Only the Datastrato ops team and the CI
service account have permission to sign with the test key. Only the Datastrato ops team
has permission to sign with the production key.

**Public keys — `datastrato/enterprise-license-keys`**

The corresponding public keys are stored in the private GitHub repository
`datastrato/enterprise-license-keys`, organized by environment:

```
enterprise-license-keys/
├── test/
│   └── gravitino-master.pub   ← embedded in CI / test builds
└── prod/
    └── gravitino-master.pub   ← embedded in customer-facing release builds
```

At build time, the Gradle `downloadPublicKey` task fetches the appropriate public key from
this repo (controlled by the `LICENSE_PUBLIC_KEY_URL` environment variable) and embeds it
in the `license-client` jar as `gravitino-master.pub`. Every Gravitino server uses this
embedded public key to verify license keys at startup.

### Test key pair

The test key pair (`gravitino-license-test` / `gravitino-master-test`) is used in:
- All CI builds (regular PRs, integration tests)
- Any non-production Gravitino build

The test public key URL is stored as the GitHub Actions secret
`ENTERPRISE_TEST_PUBLIC_KEY_URI`. The test license key used in CI is stored as
`ENTERPRISE_TEST_LICENSE_KEY`. Access to the `enterprise-license-keys` repo from CI uses
the `PRIVATE_REPO_ACCESS_TOKEN` secret.

### Production key pair

The production key pair (`gravitino-license-prod` / `gravitino-master-prod`) is used
only when:
- Building a Gravitino release artifact for customer distribution
- Issuing a real customer license via `license-tools sign`

The production `LICENSE_PUBLIC_KEY_URL` points to
`prod/gravitino-master.pub` in `enterprise-license-keys` and must only be set in the
production release pipeline — never in regular CI workflows.

---

## 2. Building Gravitino: embedding the public key

The public key is **not committed** to this repository. At build time, the Gradle task
`downloadPublicKey` (which runs automatically before `processResources`) fetches
`gravitino-master.pub` from `datastrato/enterprise-license-keys` and writes it to
`licensing/license-client/src/main/resources/gravitino-master.pub`, where it gets packaged
into the `license-client` jar. **Which key is embedded depends entirely on the
`LICENSE_PUBLIC_KEY_URL` environment variable.**

### Environment variables

| Variable                 | Description                                                                                                                                                                           |
|--------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `LICENSE_PUBLIC_KEY_URL` | Raw GitHub URL of the public key to embed. Points to `test/gravitino-master.pub` for CI builds, `prod/gravitino-master.pub` for release builds.                                       |
| `GITHUB_TOKEN`           | PAT or GitHub Actions token with `contents:read` on `datastrato/enterprise-license-keys`. In GitHub Actions this is auto-injected; for local builds use `PRIVATE_REPO_ACCESS_TOKEN`.  |

If either variable is unset, the download step in the gradle build file is skipped and whatever
previous key that is already at `src/main/resources/gravitino-master.pub` is used. This is fine for
local development and unit tests, that will generate their own in-memory keys and do not rely on
the classpath key at all. But for the integration tests and any workflow that starts a real
Gravitino server, you must ensure the correct key is embedded by setting these variables.

### CI builds (regular PRs and integration tests)

All CI workflows inject the test key automatically via GitHub Actions secrets:

```yaml
env:
  LICENSE_PUBLIC_KEY_URL: ${{ secrets.ENTERPRISE_TEST_PUBLIC_KEY_URI }}
  GITHUB_TOKEN: ${{ secrets.PRIVATE_REPO_ACCESS_TOKEN }}
```

No manual setup is needed. The test key (`test/gravitino-master.pub`) is embedded in every
CI-built jar.

### Customer release builds

Customer-facing Docker image builds use the manual workflow
`.github/workflows/docker-image-enterprise.yml`, which is triggered via `workflow_dispatch`
with a required `public-key-url` input. The release manager supplies the raw GitHub URL for
the key to embed:

```yaml
on:
  workflow_dispatch:
    inputs:
      public-key-url:
        description: "Raw GitHub URL of the public key to embed (test or prod)"
        required: true
```

The workflow sets `LICENSE_PUBLIC_KEY_URL` from that input and authenticates to
`enterprise-license-keys` using `PRIVATE_REPO_ACCESS_TOKEN`:

```yaml
env:
  LICENSE_PUBLIC_KEY_URL: ${{ github.event.inputs.public-key-url }}
  GITHUB_TOKEN: ${{ secrets.PRIVATE_REPO_ACCESS_TOKEN }}
```

Before building, the workflow logs the key environment (test vs prod) and its SHA-256
fingerprint so the release manager can confirm the correct key is being used.

For a customer release, set `public-key-url` to:
```
https://raw.githubusercontent.com/datastrato/enterprise-license-keys/main/prod/gravitino-master.pub
```

For a test or staging build, set it to:
```
https://raw.githubusercontent.com/datastrato/enterprise-license-keys/main/test/gravitino-master.pub
```

### Verifying which key is embedded in a jar

After building, confirm the correct public key is embedded:

```bash
# Extract the license-client JAR from the distribution tarball
tar -xzf distribution/package/gravitino-enterprise-*.tar.gz \
  --wildcards "*/libs/gravitino-license-client-*.jar" -C /tmp

# Verify the embedded public key
JAR=$(find /tmp -name "gravitino-license-client-*.jar" | head -1)
jar tf "$JAR" | grep gravitino-master
# Expected: gravitino-master.pub
```

To confirm it matches the expected fingerprint:

```bash
# Extract the public key from the JAR and compute its SHA-256 fingerprint
unzip -p "$JAR" gravitino-master.pub \
  | openssl ec -pubin -outform DER 2>/dev/null | sha256sum

# Compare against the known fingerprint of the corresponding gravitino-master.pub
# in enterprise-license-keys (test/ or prod/ depending on your build)
```

---

## 3. Signing a new license key

Customer licenses are always signed with the **production** KMS key
(`gravitino-license-prod` / `gravitino-master-prod`). Never use the test key to sign a
license intended for a customer.

### Build the CLI

```bash
./gradlew :licensing:license-tools:shadowJar
alias license-tools='java -jar $(ls licensing/license-tools/build/libs/gravitino-license-tools-*.jar | grep -v -- -empty)'
```

### Authenticate to GCP

```bash
# Option A: interactive login (developer workstation)
gcloud auth application-default login

# Option B: service account
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/sa-key.json
```

### `sign` command

```bash
license-tools sign \
  --kms-key-version projects/{{GCP_PROJECT}}/locations/us-central1/keyRings/{{KEY_RING}}/cryptoKeys/{{KEY_NAME}}/cryptoKeyVersions/{{KEY_VERSION}} \
  --issued-to "{{CUSTOMER_NAME}}" \
  --expires {{YYYY-MM-DD}} \
  --grace-days 30 \
  --max-nodes 10
```

Replace the `{{}}` placeholders with the actual values for your environment:

| Placeholder         | Test / CI                | Production               |
|---------------------|--------------------------|--------------------------|
| `{{GCP_PROJECT}}`   | your GCP project ID      | your GCP project ID      |
| `{{KEY_RING}}`      | `gravitino-license-test` | `gravitino-license-prod` |
| `{{KEY_NAME}}`      | `gravitino-master-test`  | `gravitino-master-prod`  |
| `{{KEY_VERSION}}`   | `1` (or current version) | `1` (or current version) |
| `{{CUSTOMER_NAME}}` | e.g. `Acme Corp`         | actual customer name     |
| `{{YYYY-MM-DD}}`    | e.g. `2026-12-31`        | actual expiry date       |

On success the command prints a single `GRAV-...` key string to stdout. Run `inspect` (see
section 4) to verify it before delivering it to the customer. Treat the key string as a
secret.

**Options:**

| Option              | Required | Default | Description                                                         |
|---------------------|----------|---------|---------------------------------------------------------------------|
| `--kms-key-version` | Yes      | —       | Full GCP KMS key version resource name                              |
| `--issued-to`       | Yes      | —       | Customer name, max 255 bytes UTF-8                                  |
| `--expires`         | Yes      | —       | Expiry date in `YYYY-MM-DD` format; must be in the future           |
| `--grace-days`      | No       | `30`    | Grace period after expiry before hard shutdown (0–255)              |
| `--max-nodes`       | No       | `-1`    | Max Gravitino server nodes; `-1` = unlimited (valid: 1–65534 or -1) |

---

## 4. Inspecting an existing license key

Use the same `license-tools` CLI from section 3 (no GCP authentication required):

```bash
license-tools inspect --key GRAV-<...>
```

Example output:

```
License ID  : 3fa85f64-5717-4562-b3fc-2c963f66afa6
Issued to   : Acme Corp
Issued at   : 2025-01-01
Expires at  : 2026-12-31
Grace ends  : 2027-01-30
Grace days  : 30
Max nodes   : 10
Status      : VALID (615 days remaining)
Signature   : VALID
```

The `inspect` command decodes the key and verifies its ECDSA signature using the public key
embedded in the `license-tools` jar at build time. The embedded key must match the key pair
used to sign — a key signed with the production KMS key must be inspected with a jar built
against `prod/gravitino-master.pub`, and vice versa for test keys.

Use `inspect` to confirm a key is well-formed and not expired before delivering it to a
customer or configuring it on a server.

---

## 5. Configuring the Gravitino server

Set the license key in `gravitino.conf` (or via environment variable):

```properties
# Option A: config file
gravitino.datastrato.license.key = GRAV-<...>
```

```bash
# Option B: environment variable (takes precedence over config file)
export GRAVITINO_LICENSE_KEY=GRAV-<...>
```

The environment variable takes precedence over the config file if both are set. At startup,
Gravitino verifies the key and refuses to start if the key is missing, has an invalid
signature, or is already past its grace period.

**All license configuration properties:**

| Property                                                    | Default                       | Description                                                                                                  |
|-------------------------------------------------------------|-------------------------------|--------------------------------------------------------------------------------------------------------------|
| `gravitino.datastrato.license.key`                          | —                             | The `GRAV-...` license key string                                                                            |
| `gravitino.datastrato.license.checkIntervalHours`           | `24`                          | How often the periodic expiry check runs (hours)                                                             |
| `gravitino.datastrato.license.nodeHeartbeatIntervalMinutes` | `5`                           | How often each node upserts its heartbeat and enforces `maxNodes` (minutes)                                  |
| `gravitino.datastrato.license.nodeStaleMinutes`             | `15`                          | Minutes without a heartbeat before a node row is pruned; must be greater than `nodeHeartbeatIntervalMinutes` |
| `gravitino.datastrato.license.warnDaysBeforeExpiry`         | `30`                          | Days before expiry when warning logs begin                                                                   |
| `gravitino.datastrato.license.renewalContactUrl`            | `https://datastrato.ai/renew` | URL shown in expiry warning logs                                                                             |

Gravitino validates at startup that `nodeStaleMinutes` > `nodeHeartbeatIntervalMinutes` and
will refuse to start if violated. A safe ratio is `nodeStaleMinutes` ≥ 3 ×
`nodeHeartbeatIntervalMinutes`.

### License status lifecycle

```
VALID ──(within warnDaysBeforeExpiry)──► EXPIRING_SOON
                                                │
                                         (past expiresAt)
                                                │
                                                ▼
                                        IN_GRACE_PERIOD
                                                │
                                       (past grace period)
                                                │
                                                ▼
                                           EXPIRED → server shuts down
```

In `EXPIRING_SOON` and `IN_GRACE_PERIOD` states the server logs a warning on every periodic
check. Once `EXPIRED`, the server calls `System.exit` immediately.

---

## 6. Key things to remember

### Never export the private key from GCP KMS

The private key must never leave KMS. If you suspect it has been compromised, rotate it
immediately in GCP KMS and re-issue all active customer licenses signed with the new key
version.

### Never use the test key for customer licenses

License keys signed with the test KMS key (`gravitino-license-test`) will fail signature
verification on any Gravitino server built for customer distribution, because those builds
embed the production public key. Always use the production KMS key (`gravitino-license-prod`)
when issuing real customer licenses.

### Never use the production key for development or testing

Running `license-tools sign` against the production KMS key for local testing wastes a
real KMS signing operation and risks accidentally distributing a production-signed key to a
non-production environment. Use the test KMS key for all non-customer work.

### Keep `enterprise-license-keys` repo access restricted

The private GitHub repo holding the public keys should have minimal read access. Rotate
the `PRIVATE_REPO_ACCESS_TOKEN` credentials on a regular schedule.

### Watch for a stale `GRAVITINO_LICENSE_KEY` env var

The env var takes precedence over `gravitino.datastrato.license.key` in the config file.
A stale env var left in deployment scripts or Docker images will silently override the
config file value.

### Configure a license key for integration tests and local server startup

Any workflow that starts a real Gravitino server — integration tests or manual local runs —
requires a valid license key. In CI, the test license key is injected automatically via the
`ENTERPRISE_TEST_LICENSE_KEY` secret. For local development, set the env var before starting
the server:

The test license key is signed with the test KMS key, so it only works with a Gravitino
build that has the test public key embedded (i.e. any standard CI or local build).
