---
title: How to use SCIM provisioning
slug: /how-to-use-scim-provisioning
keyword: security authentication scim provisioning
license: "Copyright 2026 Datastrato Inc."
---

## Introduction

Apache Gravitino Enterprise can accept **SCIM 2.0** user and group provisioning from cloud
Identity Providers (IdPs) such as Microsoft Entra ID or Okta. The `scim` plugin stores synchronized
identities in the relational metadata store (`user_meta`, `group_meta`, and `scim_user_group_rel`) scoped
by metalake.

SCIM is a **push-style synchronization** mechanism. The IdP sends create, read, update, and delete
operations to Gravitino on a schedule (for example, Entra ID typically syncs every 20–40 minutes).
It is **not** a replacement for user login authentication. Operators still configure OAuth (or another
enabled authenticator) so users can sign in; SCIM only keeps Gravitino authorization metadata aligned
with the IdP.

This guide describes how to enable SCIM, manage integration tokens, and connect an IdP connector.
For token API request and response schemas, see the
[SCIM Token Admin OpenAPI](open-api/scim/openapi.yaml).

:::note
**Gravitino Enterprise only.** SCIM requires a valid Enterprise license and the `scim` plugin.
See [License management](license-management) for license setup.
:::

---

## Architecture

Gravitino exposes two HTTP surfaces for SCIM:

| Traffic                   | Port | Path prefix                             | Authentication                                        |
|---------------------------|------|-----------------------------------------|-------------------------------------------------------|
| IdP SCIM provisioning     | 9201 | `/scim/v2/metalakes/{metalake}/...`     | Opaque bearer token (`gravitino_scim_*`)              |
| Operator token management | 8090 | `/api/metalakes/{metalake}/scim/tokens` | Main-server user auth + `METALAKE::OWNER` on metalake |

The IdP never calls Gravitino management APIs directly. It uses the SCIM base URL on port **9201**
with a metalake-scoped integration token. Metalake owners create and rotate those tokens through
the main REST API on port **8090**.

When SCIM is fully configured (`gravitino.auxService.names` includes `scim`, the token admin
extension is registered, and the OAuth SCIM settings in [Configuration](#configuration) are set),
**OAuth group membership for authorization** on metalake-scoped APIs (`/api/metalakes/{metalake}/...`)
is read from `scim_user_group_rel` in the database, not from JWT `groups` claims. The extension
registers `ScimOAuthRequestPathFilter` (parses `{metalake}` from the request path) and
`ScimOAuthPrincipalMapper` (loads groups via `listGroupNamesForUser`). OAuth still proves **who** the
caller is; SCIM defines **which groups** that user belongs to inside the metalake.

---

## Prerequisites

Before you enable SCIM or call `/api/metalakes/{metalake}/scim/tokens`, ensure the following:

1. **Enterprise license** — Gravitino starts with a valid Enterprise license and the `scim` plugin
   on the classpath.

2. **Relational entity store** — SCIM persists tokens and membership in the relational backend
   (same store as `user_meta` / `group_meta`).

3. **OAuth authentication (required for SCIM)** — Configure `gravitino.authenticators` with `oauth` so
   provisioned users can log in. SCIM also requires the OAuth SCIM settings listed in
   [Configuration](#configuration). See [Authentication](/security/how-to-authenticate).

4. **Metalake owner** — The target metalake must exist and have an owner assigned. Only the metalake
   owner can create SCIM tokens. The user who creates a metalake becomes its initial owner. See
   [Access control](/security/access-control).

5. **SCIM plugin registration** — Registering the SCIM token admin extension **requires** the full
   SCIM configuration bundle below. `ScimTokenRESTFeature` validates settings at startup and exits
   if any required entry is missing.

   In `gravitino.conf`, set **all** of the following:

   ```properties
   gravitino.server.rest.extensionPackages = com.datastrato.gravitino.scim.web.rest.feature
   gravitino.auxService.names = scim
   gravitino.scim.classpath = scim-server/libs

   gravitino.authenticator.oauth.principalMapper = com.datastrato.gravitino.scim.basic.oauth.ScimOAuthPrincipalMapper
   gravitino.authenticator.oauth.groupsFields =
   gravitino.server.webserver.customFilters = com.datastrato.gravitino.scim.basic.oauth.ScimOAuthRequestPathFilter
   ```

   `gravitino.auxService.names` must include `scim` even when you are only exercising token admin on
   **8090** today. IdP provisioning on **9201** uses the same auxiliary service and classpath settings.

6. **Transport security** — Prefer [HTTPS](/security/how-to-use-https) when integration tokens and
   SCIM payloads travel over the network.

---

## Configuration

Add SCIM settings to `conf/gravitino.conf`. Keep existing server, entity-store, and authenticator
settings unchanged. When the SCIM token admin extension package is enabled, Gravitino validates the
required keys at startup and **fails to start** if any are missing or incompatible.

| Configuration item                                            | Description                                                                                | Example                                                                          |
|---------------------------------------------------------------|--------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------|
| `gravitino.server.rest.extensionPackages`                     | Registers token admin APIs and SCIM OAuth wiring on **8090** (required)                    | `com.datastrato.gravitino.scim.web.rest.feature`                                 |
| `gravitino.auxService.names`                                  | Enables the SCIM auxiliary service (required with the extension package)                   | `scim`                                                                           |
| `gravitino.scim.classpath`                                    | Directory with SCIM JARs, for example `scim-server/libs` (required with aux service)       | `scim-server/libs`                                                               |
| `gravitino.authenticator.oauth.principalMapper`               | OAuth principal mapper that loads groups from `scim_user_group_rel` (required with SCIM)   | `com.datastrato.gravitino.scim.basic.oauth.ScimOAuthPrincipalMapper`             |
| `gravitino.authenticator.oauth.groupsFields`                  | Must be **empty** so JWT group claims do not override SCIM membership (required with SCIM) | (empty)                                                                          |
| `gravitino.server.webserver.customFilters`                    | Servlet filter that captures metalake scope from the request path (required with SCIM)     | `com.datastrato.gravitino.scim.basic.oauth.ScimOAuthRequestPathFilter`           |
| `gravitino.authenticator.oauth.principalMapper.regex.pattern` | Regex applied to the JWT identity claim before username lookup (optional)                  | `([^@]+)@.*` (align with `userMapper` when the IdP sends email-style identities) |
| `gravitino.scim.httpPort`                                     | SCIM protocol listener port (optional)                                                     | `9201`                                                                           |
| `gravitino.scim.host`                                         | SCIM listener bind address (optional)                                                      | `0.0.0.0`                                                                        |
| `gravitino.scim.userMapper`                                   | Map SCIM `userName` to `user_meta.user_name` before create/filter (optional)               | `regex`                                                                          |
| `gravitino.scim.userMapper.regex.pattern`                     | Regex pattern when `userMapper=regex`; first capture group is stored (optional)            | `([^@]+)@.*`                                                                     |
| `gravitino.scim.groupMapper`                                  | Map SCIM `displayName` to `group_meta.group_name` before create/filter (optional)          | `regex`                                                                          |
| `gravitino.scim.groupMapper.regex.pattern`                    | Regex pattern when `groupMapper=regex`; first capture group is stored (optional)           | `^/(.*)`                                                                         |
| `gravitino.scim.errorHistory.retentionDays`                   | Days to retain IdP-facing SCIM protocol failure rows in `scim_error_history` (optional)    | `30` (must be a positive integer)                                                |

Example:

```properties
# Existing Gravitino server, entity store, and OAuth settings omitted

gravitino.authenticators = oauth
gravitino.authorization.enable = true

gravitino.server.rest.extensionPackages = com.datastrato.gravitino.scim.web.rest.feature
gravitino.auxService.names = scim
gravitino.scim.classpath = scim-server/libs

gravitino.authenticator.oauth.principalMapper = com.datastrato.gravitino.scim.basic.oauth.ScimOAuthPrincipalMapper
gravitino.authenticator.oauth.groupsFields =
gravitino.server.webserver.customFilters = com.datastrato.gravitino.scim.basic.oauth.ScimOAuthRequestPathFilter

# Optional: align JWT username normalization with SCIM userName mapping
gravitino.authenticator.oauth.principalMapper.regex.pattern = ([^@]+)@.*

# Optional name normalization for SCIM push (omit for passthrough)
gravitino.scim.userMapper = regex
gravitino.scim.userMapper.regex.pattern = ([^@]+)@.*

gravitino.scim.groupMapper = regex
gravitino.scim.groupMapper.regex.pattern = ^/(.*)

# Optional: how long to keep protocol failure rows (default 30 days)
# gravitino.scim.errorHistory.retentionDays = 30
```

Build the IdP SCIM base URL from your listener host, `gravitino.scim.httpPort` (default
`9201`), and target metalake:

```text
https://{host}:9201/scim/v2/metalakes/{metalake}
```

---

## Operations

The following sections show how to manage SCIM integration tokens with `curl`. Replace
`localhost:8090`, metalake names, and tokens with values that match your deployment.

Token management APIs use the **same authentication** as other main REST APIs (`oauth` Bearer token,
Basic, `simple`, or another enabled authenticator). They do **not** accept `gravitino_scim_*` tokens.

**Base URL** — `http://<host>:<port>/api/metalakes/{metalake}/scim/tokens`

**Common headers**

| Header         | Value                                |
|----------------|--------------------------------------|
| `Accept`       | `application/vnd.gravitino.v1+json`  |
| `Content-Type` | `application/json` (for POST bodies) |

Example:

```shell
curl -s -H "Accept: application/vnd.gravitino.v1+json" \
  -H "Authorization: Bearer <main-server-access-token>" \
  http://localhost:8090/api/metalakes/example/scim/tokens
```

### Request and response fields

Create and rotate requests accept the fields below. Responses include `tokenValue` only on create and
rotate.

| Field           | Value                                                                 |
|-----------------|-----------------------------------------------------------------------|
| `tokenName`     | Required; unique per metalake among active tokens                     |
| `expiresInDays` | Optional on create/rotate; omit for no fixed expiry (`expiresAt = 0`) |
| `tokenValue`    | Returned **only once** on create and rotate; store it securely        |

### Token format and lifecycle

Integration tokens are opaque bearer secrets scoped to one metalake.

| Attribute  | Value                                                                  |
|------------|------------------------------------------------------------------------|
| Format     | Opaque bearer string with prefix `gravitino_scim_`                     |
| IdP usage  | Sent as `Authorization: Bearer` on SCIM requests to port **9201**      |
| Rotation   | In-place rotate replaces the secret; previous bearer fails immediately |
| Revocation | Delete invalidates the bearer immediately                              |

### Authorization

Token management APIs on port **8090** use the same authentication as other main REST APIs. They do
**not** accept `gravitino_scim_*` integration tokens.

| Requirement | Value                                            |
|-------------|--------------------------------------------------|
| Caller      | Must be the **metalake owner** of `{metalake}`   |
| IdP traffic | Uses the integration token on port **9201** only |

### Create a SCIM token

`POST /api/metalakes/{metalake}/scim/tokens`

```shell
curl -s -X POST -H "Accept: application/vnd.gravitino.v1+json" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <main-server-access-token>" \
  -d '{"tokenName":"prod","expiresInDays":180}' \
  http://localhost:8090/api/metalakes/example/scim/tokens
```

Example response:

```json
{
  "code": 0,
  "token": {
    "metalake": "example",
    "tokenName": "prod",
    "tokenValue": "gravitino_scim_<REDACTED>",
    "expiresAt": 1780000000000
  }
}
```

Copy `tokenValue` into the IdP connector and set the SCIM base URL as shown in
[Configuration](#configuration).

### Rotate a SCIM token

`POST /api/metalakes/{metalake}/scim/tokens/{tokenName}/rotate`

Replaces the bearer secret for an existing token name. The previous bearer fails on the next SCIM
request. An empty JSON body keeps the current `expiresAt` unchanged.

```shell
curl -s -X POST -H "Accept: application/vnd.gravitino.v1+json" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <main-server-access-token>" \
  -d '{"expiresInDays":180}' \
  http://localhost:8090/api/metalakes/example/scim/tokens/prod/rotate
```

### Delete a SCIM token

`DELETE /api/metalakes/{metalake}/scim/tokens/{tokenName}`

```shell
curl -s -X DELETE -H "Accept: application/vnd.gravitino.v1+json" \
  -H "Authorization: Bearer <main-server-access-token>" \
  http://localhost:8090/api/metalakes/example/scim/tokens/prod
```

Revocation takes effect immediately. The IdP connector using the deleted token fails authentication
on the next SCIM request.

For full request and response definitions, see the
[SCIM Token Admin OpenAPI](open-api/scim/openapi.yaml).

---

## Configure an IdP connector

After you create a SCIM token, configure the IdP enterprise application to provision users and
groups into Gravitino.

### Connector settings

| IdP field      | Value                                               |
|----------------|-----------------------------------------------------|
| SCIM base URL  | `https://{host}:9201/scim/v2/metalakes/{metalake}`  |
| Authentication | Bearer token                                        |
| Secret / token | `tokenValue` from create (prefix `gravitino_scim_`) |
| Content type   | `application/scim+json`                             |

### Microsoft Entra ID (high level)

1. Create an **Enterprise application** (or use an existing non-gallery app) for Gravitino.
2. Enable **Provisioning** and set provisioning mode to **Automatic**.
3. Under **Admin Credentials**, set:
   - **Tenant URL** to the Gravitino SCIM base URL for the target metalake.
   - **Secret Token** to the `tokenValue` from Gravitino.
4. Under **Mappings**, assign users and groups to the application.
5. Save and start provisioning. Entra syncs on an interval (often 20–40 minutes on first cycles).

### Okta (high level)

1. Create a **SCIM 2.0** application integration for Gravitino.
2. On the **Provisioning** tab, enable SCIM and set:
   - **SCIM connector base URL** to the Gravitino SCIM base URL.
   - **Authentication** to HTTP Header (Bearer token) with the Gravitino `tokenValue`.
3. Assign users and groups to the application.
4. Run provisioning or wait for the scheduled sync.

### Supported provisioning operations

Gravitino implements a focused SCIM 2.0 subset:

| Resource | Supported operations                                                        |
|----------|-----------------------------------------------------------------------------|
| Users    | `POST` create (idempotent by `externalId`), `GET`, `PATCH active`, `DELETE` |
| Groups   | `POST` create, `GET`, `PATCH members`, `DELETE`                             |

Key attribute mapping:

| SCIM attribute | Gravitino storage                                                       |
|----------------|-------------------------------------------------------------------------|
| `externalId`   | `user_meta.external_id` / `group_meta.external_id` (required on create) |
| `userName`     | `user_meta.user_name` (immutable after create)                          |
| `displayName`  | `group_meta.group_name` (immutable after create)                        |
| `active`       | `user_meta.enabled` (`PATCH` on Users only)                             |
| `members`      | `scim_user_group_rel` (`POST` / `PATCH` on Groups)                      |

Entra and Okta typically deprovision users with `PATCH active: false` and remove groups with
`DELETE /Groups/{id}`.

Test discovery after the SCIM listener is running (optional):

```shell
curl -s -H "Accept: application/scim+json" \
  -H "Authorization: Bearer gravitino_scim_<token>" \
  https://localhost:9201/scim/v2/metalakes/example/ServiceProviderConfig
```

---

## OAuth login with SCIM provisioning

SCIM and OAuth serve different roles:

| Concern             | Mechanism                                                                |
|---------------------|--------------------------------------------------------------------------|
| Prove user identity | OAuth JWT on port **8090**                                               |
| User/group metadata | SCIM push on port **9201**                                               |
| Group authorization | `scim_user_group_rel` on `/api/metalakes/{metalake}/...` when SCIM is on |

Configure OAuth as described in [Authentication](/security/how-to-authenticate). Map the JWT identity
claim with `gravitino.authenticator.oauth.principalFields` and
`gravitino.authenticator.oauth.principalMapper.regex.pattern` so the mapped username matches the
provisioned `user_meta.user_name` (use `gravitino.scim.userMapper` if the IdP sends
email-style SCIM `userName` values).

When SCIM is enabled, Gravitino **requires** `gravitino.authenticator.oauth.principalMapper` to be
`com.datastrato.gravitino.scim.basic.oauth.ScimOAuthPrincipalMapper` and
`gravitino.authenticator.oauth.groupsFields` to be empty. At runtime:

1. `ScimOAuthRequestPathFilter` (via `gravitino.server.webserver.customFilters`) records the
   request path before OAuth authentication runs.
2. `ScimOAuthPrincipalMapper` maps the JWT principal to a Gravitino username, then — for paths under
   `/api/metalakes/{metalake}/` — replaces `UserPrincipal` groups with metalake-scoped membership from
   `scim_user_group_rel` via `listGroupNamesForUser`.
3. Requests that are not metalake-scoped keep the username only (no SCIM group lookup).

JWT `groups` / `groupsFields` are **not** used for Gravitino RBAC when SCIM is on. Assign roles to
synced users and groups through [Access control](/security/access-control) APIs instead.

---

## End-to-end setup

The following steps provision Gravitino so an IdP can push users and groups and OAuth users can
access metadata. The examples use metalake `example`, SCIM token name `prod`, and Gravitino at
`http://localhost:8090` with SCIM on `https://localhost:9201`.

### 1. Enable SCIM and OAuth

Append SCIM and OAuth settings to `gravitino.conf` (see [Configuration](#configuration) and
[Authentication](/security/how-to-authenticate)):

```properties
gravitino.authenticators = oauth
gravitino.authorization.enable = true

gravitino.server.rest.extensionPackages = com.datastrato.gravitino.scim.web.rest.feature
gravitino.auxService.names = scim
gravitino.scim.classpath = scim-server/libs

gravitino.authenticator.oauth.principalMapper = com.datastrato.gravitino.scim.basic.oauth.ScimOAuthPrincipalMapper
gravitino.authenticator.oauth.groupsFields =
gravitino.server.webserver.customFilters = com.datastrato.gravitino.scim.basic.oauth.ScimOAuthRequestPathFilter
```

Start Gravitino:

```shell
./bin/gravitino.sh start
```

### 2. Create a metalake with an owner

Create a metalake with the account that will manage SCIM tokens. The creator becomes the metalake
owner. See [Manage metalakes](/manage-metalake-using-gravitino#create-a-metalake) and
[Access control](/security/access-control).

```shell
curl -s -X POST -H "Accept: application/vnd.gravitino.v1+json" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <main-server-access-token>" \
  -d '{"name":"example","comment":"SCIM example","properties":{}}' \
  http://localhost:8090/api/metalakes
```

### 3. Create a SCIM integration token

As the metalake owner, create a token (see [Create a SCIM token](#create-a-scim-token)):

```shell
curl -s -X POST -H "Accept: application/vnd.gravitino.v1+json" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <main-server-access-token>" \
  -d '{"tokenName":"prod","expiresInDays":180}' \
  http://localhost:8090/api/metalakes/example/scim/tokens
```

Copy `tokenValue` and configure the IdP SCIM base URL
`https://{host}:9201/scim/v2/metalakes/example`.

### 4. Configure the IdP connector

Configure Entra, Okta, or another SCIM 2.0 IdP with the base URL and bearer token from step 3.
Assign users and groups that should exist in the `example` metalake.

### 5. Verify provisioning

After the IdP sync cycle, list metalake users and groups:

```shell
curl -s -H "Accept: application/vnd.gravitino.v1+json" \
  -H "Authorization: Bearer <main-server-access-token>" \
  http://localhost:8090/api/metalakes/example/users

curl -s -H "Accept: application/vnd.gravitino.v1+json" \
  -H "Authorization: Bearer <main-server-access-token>" \
  http://localhost:8090/api/metalakes/example/groups
```

### 6. Grant roles and test OAuth login

Grant roles to provisioned users or groups (see [Access control](/security/access-control)).
Sign in through OAuth and call Gravitino APIs with the user access token. Group membership for
authorization should reflect the IdP-assigned groups synced through SCIM.

---

## Protocol error history

Eligible failed IdP-facing SCIM **Users** and **Groups** calls on port **9201** are recorded into
the enterprise table `scim_error_history` after the HTTP response is known. Recording is
**best-effort**: failures are queued asynchronously and may be dropped if the bounded recorder
queue is full under extreme load. Each persisted row stores the metalake, HTTP method and path,
status, optional RFC 7644 `scimType`, a truncated error detail, the SCIM token name (principal),
and `created_at`.

| Behavior  | Detail                                                                                                                                               |
|-----------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| In scope  | Metalake-scoped `/Users` and `/Groups` responses with status **≥ 400**, except **404** (best-effort persistence)                                     |
| Skipped   | **404** (user/group/metalake not found probes), successful responses, ServiceProviderConfig/Schemas/metadata paths, and token admin APIs on **8090** |
| Retention | `gravitino.scim.errorHistory.retentionDays` (default **30**, must be a **positive integer**)                                                         |
| Cleanup   | A dedicated cleaner deletes rows older than the retention window **once per day**                                                                    |

There is no public REST API to list these rows in the current release; operators query
`scim_error_history` in the entity-store JDBC database when investigating provisioning failures.
File audit logs (`gravitino_audit.log`) remain available for the same traffic.

---

## Further reading

- [SCIM Token Admin OpenAPI](open-api/scim/openapi.yaml) — token API paths, bodies, and schemas
- [Authentication](/security/how-to-authenticate) — OAuth and OIDC login setup
- [Access control](/security/access-control) — roles, privileges, and metalake owners
- [How to use HTTPS](/security/how-to-use-https) — transport security for tokens and SCIM traffic
- [License management](license-management) — Enterprise license setup
