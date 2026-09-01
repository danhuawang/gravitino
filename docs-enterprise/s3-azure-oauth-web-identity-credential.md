---
title: "S3 Credential Vending with Azure OAuth WebIdentity"
slug: "/s3-azure-oauth-web-identity-credential"
keywords:
- S3
- Azure Entra ID
- OAuth
- WebIdentity
- credential vending
license: "Copyright 2026 Datastrato Inc."
---

## Overview

This page describes how to configure Gravitino to vend temporary AWS S3 credentials using an OAuth2
`client_credentials` token from Azure Entra ID (formerly Azure Active Directory) and AWS STS
`AssumeRoleWithWebIdentity`.

In this setup Gravitino acts as the OAuth2 client. It authenticates to Azure Entra ID with a
registered application's client ID and client secret, receives a signed JWT, and presents that JWT
to AWS STS to assume an IAM role. The role returns scoped, short-lived S3 credentials that engines
such as Spark, Trino, or Flink use through GVFS or the Iceberg REST catalog. No long-lived AWS
access keys are stored in Gravitino.

The `OAuthClientCredentialsTokenSource` implementation performs the token fetch. It caches the token
in memory and refreshes it shortly before expiry, so a single registered application backs every
credential request.

## Credential Vending Flow

1. Gravitino calls the Azure Entra ID token endpoint with the `client_credentials` grant and
   receives a JWT (the `access_token`).
2. Gravitino calls AWS STS `AssumeRoleWithWebIdentity` with that JWT and a session policy scoped to
   the requested read and write paths.
3. AWS validates the JWT against the IAM OIDC identity provider, checks the role trust policy, and
   returns temporary credentials.
4. The engine uses the temporary credentials to read and write objects in S3.

The Azure audience (`aud`) claim ties the whole chain together. It must match across the requested
scope, the IAM OIDC identity provider audience, and the role trust policy condition.

## Prerequisites

- An Azure Entra ID tenant where you can register an application and create a client secret.
- An AWS account where you can create an IAM OIDC identity provider and an IAM role.
- An existing S3 bucket that the IAM role is allowed to access.
- A Gravitino deployment with the AWS bundle on the classpath.

## Step 1: Register an Application in Azure Entra ID

1. Sign in to the Azure portal, open **Microsoft Entra ID**, then **App registrations**, and choose
   **New registration**. Give the application a name and register it.
2. On the application **Overview** page, copy two values:
   - **Directory (tenant) ID** is your `TENANT_ID`.
   - **Application (client) ID** is your `CLIENT_ID`.
3. Open **Certificates & secrets**, choose **New client secret**, and copy the generated secret
   **Value**. This is your `CLIENT_SECRET`. The value is shown only once.
4. Open **Expose an API** and set the **Application ID URI**, for example `api://<CLIENT_ID>`. The
   `client_credentials` grant requests the scope `<Application ID URI>/.default`, and the issued
   token carries this URI in its `aud` claim.

After this step the token endpoint and scope are:

```text
endpoint = https://login.microsoftonline.com/<TENANT_ID>/oauth2/v2.0/token
scope    = api://<CLIENT_ID>/.default
```

The token issuer (the `iss` claim) is `https://sts.windows.net/<TENANT_ID>/`. Azure Entra ID issues a
v1.0 access token for the `client_credentials` grant unless the application's
`accessTokenAcceptedVersion` is set to `2`, so the issuer is the v1.0 `sts.windows.net` host even
though the token endpoint path contains `v2.0`. AWS federation must trust this issuer, so use
`sts.windows.net` consistently in the IAM OIDC provider and role trust policy below.

## Step 2: Configure AWS IAM for Azure OIDC Federation

1. Create an IAM OIDC identity provider:
   - **Provider URL**: `https://sts.windows.net/<TENANT_ID>/`
   - **Audience**: the Application ID URI, for example `api://<CLIENT_ID>`
2. Create an IAM role for web identity federation that trusts the provider. The trust policy
   audience condition must match the Azure `aud` claim:

```json
{
   "Version": "2012-10-17",
   "Statement": [
      {
         "Effect": "Allow",
         "Principal": {
            "Federated": "arn:aws:iam::<account-id>:oidc-provider/sts.windows.net/<TENANT_ID>/"
         },
         "Action": "sts:AssumeRoleWithWebIdentity",
         "Condition": {
            "StringEquals": {
               "sts.windows.net/<TENANT_ID>/:aud": "api://<CLIENT_ID>"
            }
         }
      }
   ]
}
```

3. Attach a permissions policy that allows the role to access the bucket and prefix. Gravitino also
   sends a scoped session policy for the requested read and write paths, so the role policy must be
   at least as broad as those paths:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetBucketLocation",
        "s3:ListBucket"
      ],
      "Resource": "arn:aws:s3:::<bucket>",
      "Condition": {
        "StringLike": {
          "s3:prefix": [
            "<prefix>",
            "<prefix>/*"
          ]
        }
      }
    },
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:GetObjectVersion",
        "s3:PutObject",
        "s3:DeleteObject"
      ],
      "Resource": "arn:aws:s3:::<bucket>/<prefix>/*"
    }
  ]
}
```

AWS documents the OIDC identity provider and web identity role creation flow here:
https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_providers_create_oidc.html and
https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_create_for-idp_oidc.html

## Step 3: Configure Gravitino Credential Vending

Enable the `aws-irsa` credential provider on the S3 fileset catalog and point the WebIdentity token
source at Azure Entra ID. Set `s3-web-identity-token-source` to `oauth-client-credentials` so
`OAuthClientCredentialsTokenSource` handles the token fetch:

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

Azure Entra ID returns the JWT in the `access_token` field, which is the default the token source
reads, so `s3-web-identity-token-response-token-field` does not need to be set for Azure.

## Property Reference

The following properties select and configure `OAuthClientCredentialsTokenSource`. Set them
alongside the standard S3 credential vending properties (`credential-providers`, `s3-region`,
`s3-role-arn`).

| Property                                      | Required | Default              | Description                                                                                                         |
|-----------------------------------------------|----------|----------------------|---------------------------------------------------------------------------------------------------------------------|
| `s3-web-identity-token-source`                | Yes      | `file`               | Set to `oauth-client-credentials` to fetch the token from an OAuth2 token endpoint.                                 |
| `s3-web-identity-token-endpoint`              | Yes      | (none)               | OAuth2 token endpoint. For Azure, `https://login.microsoftonline.com/<TENANT_ID>/oauth2/v2.0/token`.                |
| `s3-web-identity-token-client-id`             | Yes      | (none)               | OAuth2 client ID. The Azure application (client) ID.                                                                |
| `s3-web-identity-token-client-secret`         | Yes      | (none)               | OAuth2 client secret. The Azure client secret value.                                                                |
| `s3-web-identity-token-client-auth-method`    | No       | `client_secret_post` | How the client credentials are sent: `client_secret_post` (form body) or `client_secret_basic` (Basic auth header). |
| `s3-web-identity-token-scope`                 | No       | (none)               | OAuth2 scope. For Azure, `api://<CLIENT_ID>/.default`.                                                              |
| `s3-web-identity-token-audience`              | No       | (none)               | Optional OAuth2 `audience` request parameter. Not required for Azure.                                               |
| `s3-web-identity-token-response-token-field`  | No       | `access_token`       | Response field to read the token from. Azure returns the JWT in `access_token`.                                     |
| `s3-web-identity-token-request-timeout-in-ms` | No       | `10000`              | Token endpoint connect and read timeout in milliseconds.                                                            |
| `s3-web-identity-token-refresh-skew-in-secs`  | No       | `60`                 | Refresh the cached token this many seconds before it expires.                                                       |

## Verification

`AzureRealS3ManualIT` exercises the full path against real Azure Entra ID and real AWS S3. It is
skipped unless the required environment variables are set, so it never runs in regular CI by
accident.

Set the following variables, then run the test:

| Variable                        | Required | Description                                                                                   |
|---------------------------------|----------|-----------------------------------------------------------------------------------------------|
| `GRAVITINO_AZURE_TENANT_ID`     | Yes      | Azure directory (tenant) ID.                                                                  |
| `GRAVITINO_AZURE_CLIENT_ID`     | Yes      | Azure application (client) ID.                                                                |
| `GRAVITINO_AZURE_CLIENT_SECRET` | Yes      | Azure client secret value.                                                                    |
| `GRAVITINO_AZURE_SCOPE`         | Yes      | Token scope, usually `api://<aud>/.default`. The audience must match the IAM role OIDC trust. |
| `GRAVITINO_AWS_ROLE_ARN`        | Yes      | IAM role ARN that trusts the Azure OIDC issuer.                                               |
| `GRAVITINO_AWS_REGION`          | Yes      | AWS region for STS and S3 clients.                                                            |
| `GRAVITINO_AWS_S3_BUCKET`       | Yes      | Existing S3 bucket the role may read and write.                                               |
| `GRAVITINO_AWS_S3_PREFIX`       | No       | Key prefix for test objects. Defaults to `gravitino-it/`.                                     |

```shell
export GRAVITINO_AZURE_TENANT_ID="<TENANT_ID>"
export GRAVITINO_AZURE_CLIENT_ID="<CLIENT_ID>"
export GRAVITINO_AZURE_CLIENT_SECRET="<CLIENT_SECRET>"
export GRAVITINO_AZURE_SCOPE="api://<CLIENT_ID>/.default"
export GRAVITINO_AWS_ROLE_ARN="arn:aws:iam::<account-id>:role/<role-name>"
export GRAVITINO_AWS_REGION="us-east-1"
export GRAVITINO_AWS_S3_BUCKET="<bucket>"

./gradlew :bundles:aws:test \
  --tests org.apache.gravitino.s3.credential.webidentity.integration.test.AzureRealS3ManualIT
```

The test fetches a JWT from Azure Entra ID, calls AWS STS `AssumeRoleWithWebIdentity`, then writes,
reads, and deletes an object in the bucket using the vended credentials.

## Related

- [S3 OAuth WebIdentity Token Source](/s3-oauth-web-identity-token-source)
- [Fileset Catalog with S3](/fileset-catalog-with-s3)
- [Credential Vending](/security/credential-vending)
