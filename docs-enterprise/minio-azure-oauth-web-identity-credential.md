---
title: "MinIO Credential Vending with Azure OAuth WebIdentity"
slug: "/s3-azure-minio-web-identity-credential"
keywords:
- MinIO
- Azure Entra ID
- OAuth
- WebIdentity
- credential vending
license: "This software is licensed under the Apache License version 2."
---

## Overview

This page describes how to use an Azure Entra ID `client_credentials` token as a WebIdentity token
for MinIO STS `AssumeRoleWithWebIdentity`, then use the returned temporary access key, secret key,
and session token to access MinIO through its S3-compatible API.

The flow is:

1. Gravitino, or a manual `curl` command, requests an Azure Entra ID OAuth2 token with the
   `client_credentials` grant.
2. Azure returns a signed JWT in the `access_token` field.
3. The JWT is sent to MinIO STS `AssumeRoleWithWebIdentity`.
4. MinIO validates the token against the configured Azure OpenID provider and returns temporary S3
   credentials.
5. The temporary credentials access the MinIO bucket through the S3 API.

This is similar to the AWS S3 WebIdentity flow, but both the STS endpoint and the S3 endpoint point
to MinIO.

## Prerequisites

- A MinIO server with OpenID and STS support.
- An existing MinIO bucket, for example `bucket1`.
- An Azure Entra ID tenant where you can register an application and create a client secret.
- `mc`, `curl`, and `jq` installed locally.
- The Gravitino AWS bundle on the test classpath when running `AzureRealS3ManualIT`.

The examples below assume MinIO uses:

```shell
export MINIO_ENDPOINT="http://127.0.0.1:19000"
export MINIO_ALIAS="local"
export MINIO_BUCKET="bucket1"
export MINIO_IDP_NAME="azure_provider"
```

Use the MinIO API port, not the Console port. In the local setup used for verification, the API was
`19000` and the Console was `19001`.

## Step 1: Configure Azure Entra ID

Create an application registration in Azure Entra ID:

1. Open **Microsoft Entra ID** > **App registrations** > **New registration**.
2. Copy the **Directory (tenant) ID** as `<TENANT_ID>`.
3. Copy the **Application (client) ID** as `<CLIENT_ID>`.
4. Create a client secret under **Certificates & secrets** and save the generated value as
   `<CLIENT_SECRET>`.
5. Open **Expose an API** and set the **Application ID URI**, for example:

```text
api://<CLIENT_ID>
```

For this flow, the requested Azure scope is:

```text
api://<CLIENT_ID>/.default
```

Azure returns the JWT in the OAuth response field named `access_token`. Do not request a Microsoft
Graph token for this flow. The token audience (`aud`) must match the MinIO OpenID `client_id`
configured below. With the Application ID URI above, the MinIO OpenID `client_id` should be:

```text
api://<CLIENT_ID>
```

## Step 2: Configure MinIO OpenID and Policy

Configure `mc` with the MinIO root credentials:

```shell
mc alias set "${MINIO_ALIAS}" "${MINIO_ENDPOINT}" "<MINIO_ROOT_USER>" "<MINIO_ROOT_PASSWORD>"
```

Create the bucket if it does not already exist:

```shell
mc mb --ignore-existing "${MINIO_ALIAS}/${MINIO_BUCKET}"
```

For a quick local test, MinIO's built-in `consoleAdmin` policy works. For a narrower policy, create
a bucket-scoped policy like this:

```shell
cat > /tmp/gravitino-minio-bucket-rw.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetBucketLocation",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::${MINIO_BUCKET}"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:GetObjectVersion",
        "s3:PutObject",
        "s3:DeleteObject"
      ],
      "Resource": [
        "arn:aws:s3:::${MINIO_BUCKET}/*"
      ]
    }
  ]
}
EOF

mc admin policy create "${MINIO_ALIAS}" gravitinoBucketRW /tmp/gravitino-minio-bucket-rw.json
```

Add or update the Azure OpenID provider in MinIO:

```shell
export AZURE_TENANT_ID="<TENANT_ID>"
export AZURE_CLIENT_ID="<CLIENT_ID>"
export AZURE_AUDIENCE="api://${AZURE_CLIENT_ID}"

mc idp openid add "${MINIO_ALIAS}" "${MINIO_IDP_NAME}" \
  display_name="Azure" \
  config_url="https://login.microsoftonline.com/${AZURE_TENANT_ID}/v2.0/.well-known/openid-configuration" \
  client_id="${AZURE_AUDIENCE}" \
  scopes="openid,email,profile" \
  role_policy="gravitinoBucketRW"
```

If the provider already exists, update it instead:

```shell
mc idp openid update "${MINIO_ALIAS}" "${MINIO_IDP_NAME}" \
  client_id="${AZURE_AUDIENCE}" \
  config_url="https://login.microsoftonline.com/${AZURE_TENANT_ID}/v2.0/.well-known/openid-configuration" \
  scopes="openid,email,profile" \
  role_policy="gravitinoBucketRW"
```

For local debugging you can replace `role_policy="gravitinoBucketRW"` with
`role_policy="consoleAdmin"`.

Check the provider and capture the generated MinIO role ARN:

```shell
mc idp openid info "${MINIO_ALIAS}" "${MINIO_IDP_NAME}"

export MINIO_ROLE_ARN=$(
  mc idp openid ls "${MINIO_ALIAS}" --json \
    | jq -r ".[] | select(.name == \"${MINIO_IDP_NAME}\") | .roleARN"
)

echo "${MINIO_ROLE_ARN}"
```

MinIO generates the role ARN, for example:

```text
arn:minio:iam:::role/<generated-role-id>
```

Use the current value from `mc idp openid ls`. If the OpenID provider is recreated or materially
updated, the role ARN can change. A stale role ARN causes MinIO STS to fail with an error like:

```text
RoleARN arn:minio:iam:::role/... is not defined
```

## Step 3: Generate Temporary AK/SK with curl

Fetch an Azure token:

```shell
export GRAVITINO_AZURE_TENANT_ID="<TENANT_ID>"
export GRAVITINO_AZURE_CLIENT_ID="<CLIENT_ID>"
export GRAVITINO_AZURE_CLIENT_SECRET="<CLIENT_SECRET>"
export GRAVITINO_AZURE_SCOPE="api://<CLIENT_ID>/.default"

export AZURE_WEB_IDENTITY_TOKEN=$(
  curl -sS -X POST \
    "https://login.microsoftonline.com/${GRAVITINO_AZURE_TENANT_ID}/oauth2/v2.0/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-urlencode "client_id=${GRAVITINO_AZURE_CLIENT_ID}" \
    --data-urlencode "client_secret=${GRAVITINO_AZURE_CLIENT_SECRET}" \
    --data-urlencode "scope=${GRAVITINO_AZURE_SCOPE}" \
    --data-urlencode "grant_type=client_credentials" \
    | jq -r ".access_token"
)
```

Exchange the Azure token with MinIO STS:

```shell
export STS_XML=$(
  curl -sS -X POST "${MINIO_ENDPOINT}/" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-urlencode "Action=AssumeRoleWithWebIdentity" \
    --data-urlencode "Version=2011-06-15" \
    --data-urlencode "RoleArn=${MINIO_ROLE_ARN}" \
    --data-urlencode "DurationSeconds=3600" \
    --data-urlencode "WebIdentityToken=${AZURE_WEB_IDENTITY_TOKEN}"
)

printf "%s\n" "${STS_XML}"
```

The response contains:

```xml
<AccessKeyId>...</AccessKeyId>
<SecretAccessKey>...</SecretAccessKey>
<SessionToken>...</SessionToken>
<Expiration>...</Expiration>
```

For quick shell testing, extract the temporary credentials:

```shell
export AWS_ACCESS_KEY_ID=$(printf "%s" "${STS_XML}" \
  | sed -n "s:.*<AccessKeyId>\\([^<]*\\)</AccessKeyId>.*:\\1:p")
export AWS_SECRET_ACCESS_KEY=$(printf "%s" "${STS_XML}" \
  | sed -n "s:.*<SecretAccessKey>\\([^<]*\\)</SecretAccessKey>.*:\\1:p")
export AWS_SESSION_TOKEN=$(printf "%s" "${STS_XML}" \
  | sed -n "s:.*<SessionToken>\\([^<]*\\)</SessionToken>.*:\\1:p")
```

You can then use any S3-compatible client with:

```shell
aws --endpoint-url "${MINIO_ENDPOINT}" s3 ls "s3://${MINIO_BUCKET}/"
```

## Step 4: Verify with AzureRealS3ManualIT

`AzureRealS3ManualIT` can verify the same end-to-end path:

Azure Entra ID token -> MinIO STS -> temporary AK/SK/session token -> MinIO S3 put/get/delete.

Set the Azure variables:

```shell
export GRAVITINO_AZURE_TENANT_ID="<TENANT_ID>"
export GRAVITINO_AZURE_CLIENT_ID="<CLIENT_ID>"
export GRAVITINO_AZURE_CLIENT_SECRET="<CLIENT_SECRET>"
export GRAVITINO_AZURE_SCOPE="api://<CLIENT_ID>/.default"
```

Set the MinIO S3 and STS variables:

```shell
export GRAVITINO_AWS_ROLE_ARN="${MINIO_ROLE_ARN}"
export GRAVITINO_AWS_REGION="ap-northeast-1"
export GRAVITINO_AWS_S3_BUCKET="${MINIO_BUCKET}"
export GRAVITINO_AWS_STS_ENDPOINT="${MINIO_ENDPOINT}"
export GRAVITINO_AWS_S3_ENDPOINT="${MINIO_ENDPOINT}"
export GRAVITINO_AWS_S3_PATH_STYLE_ACCESS="true"
```

For manual verification, keep the test object:

```shell
export GRAVITINO_AWS_S3_KEEP_OBJECT="true"
```

Run the test:

```shell
./gradlew :bundles:aws:test \
  --tests org.apache.gravitino.s3.credential.webidentity.integration.test.AzureRealS3ManualIT
```

If the test passes, verify the retained object:

```shell
mc find "${MINIO_ALIAS}/${MINIO_BUCKET}" --name "vended-*.txt"
mc cat "${MINIO_ALIAS}/${MINIO_BUCKET}/gravitino-it/<vended-object-name>.txt"
```

If `GRAVITINO_AWS_S3_KEEP_OBJECT` is not set to `true`, the test deletes the object after the
successful read.

`GRAVITINO_AWS_S3_PREFIX` is optional. If it is unset or blank, the test uses the default prefix
`gravitino-it/`.

## Troubleshooting

### Azure returns `invalid_resource`

Check that:

- The token request uses the correct tenant.
- The application has an Application ID URI under **Expose an API**.
- The requested scope is `<Application ID URI>/.default`, for example
  `api://<CLIENT_ID>/.default`.
- MinIO OpenID `client_id` matches the Azure token audience, for example `api://<CLIENT_ID>`.

### MinIO returns `RoleARN ... is not defined`

The role ARN in the STS request is stale or belongs to another OpenID provider. Refresh it:

```shell
mc idp openid ls "${MINIO_ALIAS}"
mc idp openid info "${MINIO_ALIAS}" "${MINIO_IDP_NAME}"
```

Then use the current `roleARN` value.

### MinIO STS returns `AccessDenied`

The OpenID provider `role_policy` may not grant enough S3 permissions. For local debugging, use
`role_policy="consoleAdmin"`. For production, attach a narrower policy that allows at least
`s3:GetBucketLocation`, `s3:ListBucket`, `s3:GetObject`, `s3:GetObjectVersion`, `s3:PutObject`, and
`s3:DeleteObject` for the target bucket and prefix.

### The test reaches STS but S3 access fails

For MinIO, both endpoint overrides are required:

```shell
export GRAVITINO_AWS_STS_ENDPOINT="${MINIO_ENDPOINT}"
export GRAVITINO_AWS_S3_ENDPOINT="${MINIO_ENDPOINT}"
export GRAVITINO_AWS_S3_PATH_STYLE_ACCESS="true"
```

Use the MinIO API endpoint, not the Console endpoint.

## Related

- [S3 OAuth WebIdentity Token Source](/s3-oauth-web-identity-token-source)
- [S3 Credential Vending with Azure OAuth WebIdentity](/s3-azure-oauth-web-identity-credential)
- [Credential Vending](/security/credential-vending)
