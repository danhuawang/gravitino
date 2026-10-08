---
title: How to use SCIM provisioning
slug: /how-to-use-scim-provisioning
keyword: security authentication scim provisioning
license: "Copyright 2026 Datastrato Inc."
---

## Introduction

Apache Gravitino Enterprise can accept **SCIM 2.0** user and group provisioning from cloud
Identity Providers (IdPs) such as Microsoft Entra ID or Okta. The `scim` plugin stores synchronized
identities for the whole Gravitino instance in the relational metadata store (`scim_user_meta`,
`scim_group_meta`, and `scim_user_group_rel`). Provisioned users and groups are not tied to a
metalake.

SCIM is a **push-style synchronization** mechanism. The IdP sends create, read, update, and delete
operations to Gravitino on a schedule (for example, Entra ID typically syncs every 20–40 minutes).
It is **not** a replacement for user login authentication. Operators still configure OAuth so users
can sign in; SCIM only keeps Gravitino identity and group membership aligned with the IdP.

This guide describes how to enable SCIM, manage integration tokens, and connect an IdP connector.

:::note
**Gravitino Enterprise only.** SCIM requires a valid Enterprise license and the `scim` plugin.
See [License management](license-management) for license setup.
:::

---

## Architecture

Gravitino exposes two HTTP surfaces for SCIM:

| Traffic                   | Port | Path prefix          | Authentication                           |
|---------------------------|------|----------------------|------------------------------------------|
| IdP SCIM provisioning     | 9201 | `/scim/v2/`          | Opaque bearer token (`gravitino_scim_*`) |
| Operator token management | 8090 | `/api/scim/tokens`   | Main-server user auth + service admin    |

The IdP never calls Gravitino management APIs directly. It uses the SCIM base URL on port **9201**
with an integration token. Service admins create and rotate those tokens on port **8090**.

When SCIM is enabled, OAuth still proves **who** the caller is, and SCIM defines **which groups**
a provisioned user belongs to. For users provisioned by SCIM, group membership for authorization is
read from `scim_user_group_rel`, not from JWT group claims. Users that are not provisioned by SCIM
get their groups from the JWT claims in `gravitino.scim.jit.groupsFields`. See
[OAuth login with SCIM provisioning](#oauth-login-with-scim-provisioning).

---

## Prerequisites

Before you enable SCIM or manage SCIM tokens, ensure the following:

1. **Enterprise license** — Gravitino starts with a valid Enterprise license and the `scim` plugin
   on the classpath.
2. **Relational entity store** — SCIM persists tokens, identities, and membership in the relational
   backend.
3. **OAuth authentication** — Configure `gravitino.authenticators` with `oauth` so provisioned users
   can sign in. See [Authentication](/security/how-to-authenticate).
4. **Service admin** — Only users listed in `gravitino.authorization.serviceAdmins` can manage SCIM
   tokens.
5. **Transport security** — Prefer [HTTPS](/security/how-to-use-https) when integration tokens and
   SCIM payloads travel over the network.

---

## Configuration

Add SCIM settings to `conf/gravitino.conf`. Keep existing server, entity-store, and authenticator
settings unchanged. When the SCIM extension package is registered, Gravitino validates the required
keys at startup and **fails to start** if any are missing or incompatible.

| Configuration item                                            | Description                                                                                       | Example                                                                |
|---------------------------------------------------------------|---------------------------------------------------------------------------------------------------|------------------------------------------------------------------------|
| `gravitino.server.rest.extensionPackages`                     | Registers the SCIM token management APIs and OAuth wiring on **8090** (required)                  | `com.datastrato.gravitino.scim.web.rest.feature`                       |
| `gravitino.auxService.names`                                  | Must include `scim` to start the SCIM listener (required)                                         | `scim`                                                                 |
| `gravitino.scim.classpath`                                    | Directory with the SCIM server JARs (required)                                                    | `scim-server/libs`                                                     |
| `gravitino.authenticator.oauth.principalMapper`               | Must be the SCIM principal mapper, which loads groups from `scim_user_group_rel` (required)       | `com.datastrato.gravitino.scim.basic.oauth.ScimOAuthPrincipalMapper`   |
| `gravitino.authenticator.oauth.groupsFields`                  | Must be **empty** so JWT group claims do not override SCIM membership (required)                  | (empty)                                                                |
| `gravitino.server.webserver.customFilters`                    | Must include the filter that captures the request token for the principal mapper (required)       | `com.datastrato.gravitino.scim.basic.oauth.ScimOAuthRequestPathFilter` |
| `gravitino.authenticator.oauth.principalMapper.regex.pattern` | Regex applied to the JWT identity claim before username lookup (optional)                         | `([^@]+)@.*`                                                           |
| `gravitino.scim.jit.groupsFields`                             | JWT claims that supply groups for users not provisioned by SCIM (optional, default `groups`)      | `groups`                                                               |
| `gravitino.scim.httpPort`                                     | SCIM listener port (optional, default `9201`)                                                     | `9201`                                                                 |
| `gravitino.scim.host`                                         | SCIM listener bind address (optional)                                                             | `0.0.0.0`                                                              |
| `gravitino.scim.userMapper`                                   | Maps SCIM `userName` to the stored user name (optional, default passthrough regex)                | `regex`                                                                |
| `gravitino.scim.userMapper.regex.pattern`                     | Regex for `userMapper`; the first capture group is stored (optional, default `^(.*)$`)            | `([^@]+)@.*`                                                           |
| `gravitino.scim.groupMapper`                                  | Maps SCIM `displayName` to the stored group name (optional, default passthrough regex)            | `regex`                                                                |
| `gravitino.scim.groupMapper.regex.pattern`                    | Regex for `groupMapper`; the first capture group is stored (optional, default `^(.*)$`)           | `^/(.*)`                                                               |
| `gravitino.scim.errorHistory.retentionDays`                   | Days to retain SCIM protocol failure rows in `scim_error_history` (optional, default `30`)        | `30`                                                                   |

Example:

```properties
# Existing Gravitino server, entity store, and OAuth settings omitted
gravitino.authenticators = oauth
gravitino.authorization.serviceAdmins = admin

gravitino.server.rest.extensionPackages = com.datastrato.gravitino.scim.web.rest.feature
gravitino.auxService.names = scim
gravitino.scim.classpath = scim-server/libs

gravitino.authenticator.oauth.principalMapper = com.datastrato.gravitino.scim.basic.oauth.ScimOAuthPrincipalMapper
gravitino.authenticator.oauth.groupsFields =
gravitino.server.webserver.customFilters = com.datastrato.gravitino.scim.basic.oauth.ScimOAuthRequestPathFilter

# Optional: align JWT username normalization with SCIM userName mapping
gravitino.authenticator.oauth.principalMapper.regex.pattern = ([^@]+)@.*
gravitino.scim.userMapper = regex
gravitino.scim.userMapper.regex.pattern = ([^@]+)@.*
```

The IdP SCIM base URL is built from the listener host and `gravitino.scim.httpPort`:

```text
https://{host}:9201/scim/v2
```

---

## Manage SCIM tokens

The following sections show how to manage SCIM integration tokens with `curl`. Replace
`localhost:8090` and tokens with values that match your deployment.

Token management APIs are **public REST APIs** under `/api/scim/tokens`. They use the **same
authentication** as other main REST APIs and require a **service admin**. They do **not** accept
`gravitino_scim_*` tokens.

The Web UI also calls internal endpoints under `/api/internal/scim/tokens`. Prefer the public
`/api/scim/tokens` paths for scripts and pipelines.

| Header          | Value                                     |
|-----------------|-------------------------------------------|
| `Accept`        | `application/vnd.gravitino.v1+json`       |
| `Content-Type`  | `application/json` (for POST bodies)      |
| `Authorization` | Main-server credential of a service admin |

### Token fields and lifecycle

Integration tokens are opaque bearer secrets for the whole Gravitino instance. Token names are unique
among active tokens.

| Attribute       | Value                                                                              |
|-----------------|------------------------------------------------------------------------------------|
| `tokenName`     | Required on create                                                                 |
| `expiresInDays` | Optional on create and rotate; omit on create for no expiry (`expiresAt = 0`)      |
| `tokenValue`    | Returned **only once** on create and rotate; Gravitino stores only a SHA-256 hash  |
| Format          | Opaque bearer string with prefix `gravitino_scim_`                                 |
| Rotation        | Replaces the secret in place; the previous value fails on the next SCIM request    |
| Revocation      | Delete invalidates the token immediately                                           |
| Expiry          | SCIM requests with an expired token are rejected                                   |

### List SCIM tokens

```shell
curl -s -H "Accept: application/vnd.gravitino.v1+json" \
  -H "Authorization: Bearer <main-server-access-token>" \
  http://localhost:8090/api/scim/tokens
```

### Create a SCIM token

```shell
curl -s -X POST -H "Accept: application/vnd.gravitino.v1+json" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <main-server-access-token>" \
  -d '{"tokenName":"prod","expiresInDays":180}' \
  http://localhost:8090/api/scim/tokens
```

Example response:

```json
{
  "code": 0,
  "token": {
    "tokenName": "prod",
    "tokenValue": "gravitino_scim_<REDACTED>",
    "expiresAt": 1780000000000
  }
}
```

Copy `tokenValue` into the IdP connector.

### Rotate a SCIM token

An empty JSON body keeps the current `expiresAt` unchanged.

```shell
curl -s -X POST -H "Accept: application/vnd.gravitino.v1+json" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <main-server-access-token>" \
  -d '{"expiresInDays":180}' \
  http://localhost:8090/api/scim/tokens/prod/rotate
```

### Delete a SCIM token

```shell
curl -s -X DELETE -H "Accept: application/vnd.gravitino.v1+json" \
  -H "Authorization: Bearer <main-server-access-token>" \
  http://localhost:8090/api/scim/tokens/prod
```

---

## Configure an IdP connector

After you create a SCIM token, configure the IdP enterprise application to provision users and
groups into Gravitino.

### Connector settings

| IdP field      | Value                                               |
|----------------|-----------------------------------------------------|
| SCIM base URL  | `https://{host}:9201/scim/v2`                       |
| Authentication | Bearer token                                        |
| Secret / token | `tokenValue` from create (prefix `gravitino_scim_`) |
| Content type   | `application/scim+json`                             |

### Microsoft Entra ID (high level)

1. Create an **Enterprise application** (or use an existing non-gallery app) for Gravitino.
2. Enable **Provisioning** and set provisioning mode to **Automatic**.
3. Under **Admin Credentials**, set:
   - **Tenant URL** to the Gravitino SCIM base URL.
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

Gravitino implements a focused SCIM 2.0 subset. Bulk operations, sorting, and ETags are not
supported.

| Resource | Supported operations                                                                                                         |
|----------|------------------------------------------------------------------------------------------------------------------------------|
| Users    | `POST`, `GET` (filter `eq` on `id`, `userName`, `externalId`), `PUT`, `PATCH` (`active`, `externalId`), `DELETE`             |
| Groups   | `POST`, `GET` (filter `eq` on `id`, `displayName`, `externalId`), `PUT`, `PATCH` (`members`, `externalId`), `DELETE`         |

Key attribute mapping:

| SCIM attribute | Gravitino storage                                                           |
|----------------|-----------------------------------------------------------------------------|
| `userName`     | `scim_user_meta.user_name` (required on create, immutable after create)     |
| `displayName`  | `scim_group_meta.group_name` (required on create, immutable after create)   |
| `externalId`   | `scim_user_meta.external_id` / `scim_group_meta.external_id` (optional)     |
| `active`       | `scim_user_meta.enabled`                                                    |
| `members`      | `scim_user_group_rel`                                                       |

Entra and Okta typically deprovision users with `PATCH active: false` and remove groups with
`DELETE /Groups/{id}`.

Discovery endpoints (`ServiceProviderConfig`, `ResourceTypes`, and `Schemas`) do not require a
token. `Users` and `Groups` require the integration token:

```shell
curl -s -H "Accept: application/scim+json" \
  https://localhost:9201/scim/v2/ServiceProviderConfig
```

---

## OAuth login with SCIM provisioning

SCIM and OAuth serve different roles:

| Concern             | Mechanism                                    |
|---------------------|----------------------------------------------|
| Prove user identity | OAuth JWT on port **8090**                   |
| User/group metadata | SCIM push on port **9201**                   |
| Group membership    | `scim_user_group_rel` when SCIM is enabled   |

Configure OAuth as described in [Authentication](/security/how-to-authenticate). Map the JWT identity
claim with `gravitino.authenticator.oauth.principalFields` and
`gravitino.authenticator.oauth.principalMapper.regex.pattern` so the mapped username matches the
provisioned user name (use `gravitino.scim.userMapper` if the IdP sends email-style SCIM `userName`
values).

At runtime, `ScimOAuthRequestPathFilter` captures the request token before OAuth authentication
runs, and `ScimOAuthPrincipalMapper` maps the JWT principal to a Gravitino username:

1. **Active SCIM user** — The user's groups come from `scim_user_group_rel`.
2. **Disabled SCIM user** (`active: false`) — The user gets no groups.
3. **User not provisioned by SCIM** — Groups are read from the JWT claims in
   `gravitino.scim.jit.groupsFields`, and the identity is recorded as a just-in-time (JIT) identity.

Gravitino applies roles granted to these group names in each metalake. Grant roles to synced users
and groups through [Access control](/security/access-control).

---

## End-to-end setup

The following steps provision Gravitino so an IdP can push users and groups and OAuth users can
access metadata. The examples use SCIM token name `prod`, metalake `example`, and Gravitino at
`http://localhost:8090` with SCIM on `https://localhost:9201`.

### 1. Enable SCIM and OAuth

Add the SCIM and OAuth settings from [Configuration](#configuration) to `gravitino.conf`, including a
service admin in `gravitino.authorization.serviceAdmins`, then start Gravitino:

```shell
./bin/gravitino.sh start
```

### 2. Create a SCIM integration token

As a service admin, [create a token](#create-a-scim-token) and copy `tokenValue`.

### 3. Configure the IdP connector

Configure Entra, Okta, or another SCIM 2.0 IdP with the base URL `https://{host}:9201/scim/v2` and
the bearer token from step 2. Assign the users and groups that should exist in Gravitino.

### 4. Verify provisioning

After the IdP sync cycle, list the provisioned users and groups:

```shell
curl -s -H "Accept: application/scim+json" \
  -H "Authorization: Bearer gravitino_scim_<token>" \
  https://localhost:9201/scim/v2/Users

curl -s -H "Accept: application/scim+json" \
  -H "Authorization: Bearer gravitino_scim_<token>" \
  https://localhost:9201/scim/v2/Groups
```

### 5. Grant roles and test OAuth login

Grant roles in the `example` metalake to provisioned users or groups (see
[Access control](/security/access-control)). Sign in through OAuth and call Gravitino APIs with the
user access token. Group-based access should reflect the IdP-assigned groups synced through SCIM.

---

## Protocol error history

Eligible failed IdP-facing SCIM **Users** and **Groups** calls on port **9201** are recorded into
the enterprise table `scim_error_history` after the HTTP response is known. Recording is
**best-effort**: failures are queued asynchronously and may be dropped if the bounded recorder
queue is full under extreme load. Each row stores the HTTP method and path, status, optional
RFC 7644 `scimType`, a truncated error detail, the SCIM token name (principal), and `created_at`.

| Behavior  | Detail                                                                                                                  |
|-----------|-------------------------------------------------------------------------------------------------------------------------|
| In scope  | `/scim/v2/Users` and `/scim/v2/Groups` responses with status **≥ 400**, except **404**                                 |
| Skipped   | **404** (not-found probes), successful responses, discovery endpoints, and token management APIs on **8090**           |
| Retention | `gravitino.scim.errorHistory.retentionDays` (default **30**, must be a **positive integer**)                            |
| Cleanup   | A dedicated cleaner deletes rows older than the retention window **once per day**                                       |

There is no public REST API to list these rows in the current release; operators query
`scim_error_history` in the entity-store JDBC database when investigating provisioning failures.
File audit logs (`gravitino_audit.log`) remain available for the same traffic.

---

## Further reading

- [Authentication](/security/how-to-authenticate) — OAuth and OIDC login setup
- [Access control](/security/access-control) — roles and privileges
- [How to use HTTPS](/security/how-to-use-https) — transport security for tokens and SCIM traffic
- [License management](license-management) — Enterprise license setup
