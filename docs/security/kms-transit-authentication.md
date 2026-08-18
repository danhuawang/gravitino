---
title: "Configure Transit KMS authentication"
slug: "/security/kms-transit-authentication"
keyword: "kms transit vault openbao bearer token environment variable"
license: "This software is licensed under the Apache License version 2."
---

## Overview

Gravitino can inspect key metadata in HashiCorp Vault Transit and OpenBao Transit. Each provider
authenticates with a bearer token obtained from a named process environment variable. Provider
configuration stores the variable name, not the bearer token:

```properties
gravitino.kms.source.production.credential.method=environment_variable
gravitino.kms.source.production.credential.environmentVariable=GRAVITINO_TRANSIT_TOKEN
```

The deployment supplies `GRAVITINO_TRANSIT_TOKEN` through its secret-management mechanism before
starting Gravitino. Do not place the token directly in `gravitino.conf`, a checked-in chart value,
or an ordinary deployment manifest.

## Grant Transit metadata access

Use a dedicated, non-root token with only the permissions Gravitino needs. The following policy
permits metadata reads from the default Transit mount. Replace `transit` when the source uses a
custom `endpoint.transitMount`:

```hcl
path "transit/keys/*" {
  capabilities = ["read"]
}
```

Use separate narrowly scoped credentials when Vault and OpenBao sources have different trust
boundaries. Follow the provider documentation when creating the policy and token:

- [Vault policies](https://developer.hashicorp.com/vault/docs/concepts/policies)
- [Vault tokens](https://developer.hashicorp.com/vault/docs/concepts/tokens)
- [OpenBao policies](https://openbao.org/docs/concepts/policies/)
- [OpenBao tokens](https://openbao.org/docs/concepts/tokens/)

## Supply the environment variable

For a local process, set the selected variable before starting Gravitino:

```shell
export GRAVITINO_TRANSIT_TOKEN='<bearer-token>'
```

In Kubernetes, reference a Secret rather than writing the credential into the Pod specification:

```yaml
env:
  - name: GRAVITINO_TRANSIT_TOKEN
    valueFrom:
      secretKeyRef:
        name: gravitino-transit
        key: token
```

The variable name must use letters, digits, and underscores and must not begin with a digit. The
resolved value must be present, contain no whitespace or control characters, and be no larger than
16,384 characters. Gravitino never includes the value in configuration errors or authentication
errors.

## Configure the Gravitino source

Configure a Vault Transit source as follows:

```properties
gravitino.kms.sources=production
gravitino.kms.source.production.api=vault-transit
gravitino.kms.source.production.endpoint.address=https://vault.example.com
gravitino.kms.source.production.endpoint.transitMount=transit
gravitino.kms.source.production.credential.method=environment_variable
gravitino.kms.source.production.credential.environmentVariable=GRAVITINO_TRANSIT_TOKEN
```

Use `openbao-transit` for OpenBao. `endpoint.transitMount` defaults to `transit` when omitted.

Use HTTPS with normal certificate and hostname validation in production. Add a private provider CA
to the trust store used by the Gravitino JVM when necessary. Plain HTTP is rejected by default. For
an explicitly isolated development or test endpoint only, set
`gravitino.kms.source.production.endpoint.allowInsecureHttp=true` to acknowledge that the bearer
token will cross the connection without TLS.

The packaged Gravitino logging configuration disables the shaded Apache HTTP header and wire logger
categories so general debug logging does not emit `X-Vault-Token`. Do not override those categories
in production.

## Credential lifecycle

The provider resolves the environment variable once when it creates the KMS client and retains the
token privately for that client's lifetime. It does not poll the environment, renew the token,
reauthenticate to Vault or OpenBao, or retry a rejected credential.

If the token expires, is revoked, or must be replaced:

1. update the deployment Secret or other environment source;
2. recreate the Gravitino process or KMS client; and
3. confirm that key inspection succeeds before removing the old instance.

HTTP `401` and `403` responses fail immediately as `KmsAuthenticationException`. A `403` can mean
that the token is valid but its policy does not authorize the configured Transit path.

Vault Agent, OpenBao Agent, and Vault/OpenBao Proxy can manage renewal and reauthentication, but
integration with those lifecycle mechanisms is outside this release. Revisit that design when a
deployment requires transparent credential replacement without a Gravitino restart.

## Troubleshooting

- **Credential environment variable is missing or invalid:** verify the configured variable name,
  its value in the Gravitino process, and the deployment Secret mapping. Restart after changing it.
- **HTTP 401:** verify that the credential is current and belongs to the intended provider.
- **HTTP 403:** verify the token policy and the configured Transit mount. This iteration supports
  the provider's root namespace only.
- **TLS connection fails:** verify the HTTPS endpoint, server name, certificate chain, and JVM trust
  store.
- **Transit route returns 404 with provider errors:** verify `endpoint.transitMount`; only an
  explicit empty `errors` array is treated as an authoritative missing-key response.
