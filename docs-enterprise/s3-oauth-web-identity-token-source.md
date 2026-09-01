---
title: "S3 OAuth WebIdentity Token Source"
slug: "/s3-oauth-web-identity-token-source"
keywords:
- S3
- OAuth
- WebIdentity
- credential vending
license: "Copyright 2026 Datastrato Inc."
---

## Overview

Gravitino's S3 `aws-irsa` credential provider can use an OAuth2 `client_credentials` token as the
WebIdentity token presented to STS `AssumeRoleWithWebIdentity`. This lets Gravitino vend temporary
S3 credentials from an external OIDC provider without relying on an EKS projected service account
token.

The `oauth-client-credentials` token source is selected with
`s3-web-identity-token-source=oauth-client-credentials`. Gravitino calls the configured OAuth2 token
endpoint, caches the returned token in memory, and refreshes it before expiry.

## Token Source Selection

The WebIdentity token source is selected by `s3-web-identity-token-source`:

| Value                      | Description                                                                                         |
|----------------------------|-----------------------------------------------------------------------------------------------------|
| `file`                     | Default source. Reads the token from `s3-web-identity-token-file` or `AWS_WEB_IDENTITY_TOKEN_FILE`. |
| `oauth-client-credentials` | Fetches the token from an OAuth2 token endpoint with the `client_credentials` grant.                |

## OAuth Client Credentials Properties

Set the following properties alongside the standard S3 credential vending properties such as
`credential-providers`, `s3-role-arn`, and `s3-region`.

| Gravitino server catalog properties           | Gravitino Iceberg REST server configurations                         | Description                                                                                                  | Default value        | Required |
|-----------------------------------------------|----------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|----------------------|----------|
| `s3-web-identity-token-source`                | `gravitino.iceberg-rest.s3-web-identity-token-source`                | WebIdentity token source. Set to `oauth-client-credentials`.                                                 | `file`               | No       |
| `s3-web-identity-token-endpoint`              | `gravitino.iceberg-rest.s3-web-identity-token-endpoint`              | OAuth2 token endpoint.                                                                                       | (none)               | Yes      |
| `s3-web-identity-token-client-id`             | `gravitino.iceberg-rest.s3-web-identity-token-client-id`             | OAuth2 client ID.                                                                                            | (none)               | Yes      |
| `s3-web-identity-token-client-secret`         | `gravitino.iceberg-rest.s3-web-identity-token-client-secret`         | OAuth2 client secret.                                                                                        | (none)               | Yes      |
| `s3-web-identity-token-client-auth-method`    | `gravitino.iceberg-rest.s3-web-identity-token-client-auth-method`    | Client authentication method: `client_secret_post` or `client_secret_basic`.                                 | `client_secret_post` | No       |
| `s3-web-identity-token-scope`                 | `gravitino.iceberg-rest.s3-web-identity-token-scope`                 | Optional OAuth2 scope.                                                                                       | (none)               | No       |
| `s3-web-identity-token-audience`              | `gravitino.iceberg-rest.s3-web-identity-token-audience`              | Optional OAuth2 `audience` request parameter.                                                                | (none)               | No       |
| `s3-web-identity-token-response-token-field`  | `gravitino.iceberg-rest.s3-web-identity-token-response-token-field`  | Response field to read the WebIdentity token from.                                                           | `access_token`       | No       |
| `s3-web-identity-token-request-timeout-in-ms` | `gravitino.iceberg-rest.s3-web-identity-token-request-timeout-in-ms` | Token endpoint connect and read timeout in milliseconds.                                                     | `10000`              | No       |
| `s3-web-identity-token-refresh-skew-in-secs`  | `gravitino.iceberg-rest.s3-web-identity-token-refresh-skew-in-secs`  | Refresh the cached token this many seconds before it expires.                                                | `60`                 | No       |

## Example

```json
{
  "credential-providers": "aws-irsa",
  "s3-region": "us-east-1",
  "s3-role-arn": "arn:aws:iam::<account-id>:role/<role-name>",
  "s3-web-identity-token-source": "oauth-client-credentials",
  "s3-web-identity-token-endpoint": "https://login.microsoftonline.com/<TENANT_ID>/oauth2/v2.0/token",
  "s3-web-identity-token-client-id": "<CLIENT_ID>",
  "s3-web-identity-token-client-secret": "<CLIENT_SECRET>",
  "s3-web-identity-token-scope": "api://<CLIENT_ID>/.default"
}
```

## Provider Walkthroughs

- [S3 Credential Vending with Azure OAuth WebIdentity](/s3-azure-oauth-web-identity-credential)
- [MinIO Credential Vending with Azure OAuth WebIdentity](/s3-azure-minio-web-identity-credential)
