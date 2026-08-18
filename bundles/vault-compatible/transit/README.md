<!--
  Copyright 2026 Datastrato Pvt Ltd.
  This software is licensed under the Apache License version 2.
-->

# Transit KMS providers

The `bundles/vault-compatible/transit` module is the internal, provider-neutral HTTP and KMS library shared by the
HashiCorp Vault and OpenBao provider modules. The clients read key metadata only; they do not
encrypt, decrypt, wrap, unwrap, or return key material.

The implementation shares one authenticated HTTP connection inside each provider client. See the
[Transit client architecture](../../design-docs/transit-client-architecture.md) for the ownership,
authentication, and future Secret API design.

## Unit tests

Unit tests use an in-process HTTP server and require no external credentials or Docker daemon.

```shell
./gradlew \
  :bundles:vault-compatible:transit:check \
  :bundles:vault:check \
  :bundles:openbao:check \
  -PskipITs
```

## Live-provider integration tests

The integration tests start disposable development-mode servers, enable Transit, and create both
an encryption key and a signing-only key. They verify factory-to-provider metadata inspection,
missing-key behavior, and authentication failure for a rejected environment-sourced token.

```shell
./gradlew \
  :bundles:vault:test \
  :bundles:openbao:test \
  -PskipTests \
  -PskipDockerTests=false
```

The default pinned images are:

| Provider | Image | Optional override |
| --- | --- | --- |
| OpenBao | `openbao/openbao:2.6.0` | `GRAVITINO_OPENBAO_DOCKER_IMAGE` |
| Vault | `hashicorp/vault:2.0.3` | `GRAVITINO_VAULT_DOCKER_IMAGE` |

Update a pin and this table together, then verify the new image on the CI runner architecture.

The tests use fixed development-only root credentials to configure each disposable server. The
Gradle test task injects a fixed valid token and a fixed invalid token into the forked test process,
and the provider factories resolve the configured variable names through the real process
environment. No production credentials are accepted or required.

The CI runner needs a Docker daemon, permission to pull both images, and
`-PskipDockerTests=false`. Ordinary unit-test jobs pass `-PskipITs`, so the live-provider package is
excluded even when Docker is available.

OpenBao is pulled as an external Mozilla Public License 2.0 test fixture. Vault is an external
interoperability fixture governed by HashiCorp's current terms. Neither server, image, nor source is
bundled or redistributed with Gravitino.
