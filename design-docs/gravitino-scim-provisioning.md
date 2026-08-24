<!--
  Copyright 2026 Datastrato Pvt Ltd.
  This software is licensed under the Apache License version 2.
-->

# Design of SCIM 2.0 User and Group Provisioning in Gravitino

## 1. Background

Enterprises typically manage users and groups in cloud Identity Providers (IdPs) such as Azure AD
(Microsoft Entra ID), Okta, or Google Workspace. Gravitino stores user and group metadata in
`user_meta` and `group_meta`, which are scoped by `metalake_id`. Operators need a
standard way to push identity changes from an external IdP into Gravitino without manually calling
Gravitino management APIs.

The System for Cross-domain Identity Management (SCIM) 2.0 protocol is the industry-standard
mechanism for this push-style identity provisioning. Products such as Databricks and Snowflake expose
SCIM endpoints and let the cloud IdP push user and group lifecycle events to them.

In the common deployment model:

1. The operator configures the SCIM endpoint URL and bearer token in the IdP.
2. The IdP pushes create, read, update, and delete operations for User and Group resources (users:
   **`PATCH active`**; groups: **`PATCH members`** and **`DELETE`** — RFC 7643 Group has no `active`).
3. The target system persists the synchronized identity data locally.

Provisioning is interval-based rather than real-time. For example, Microsoft Entra ID synchronizes
assigned users and groups every 20–40 minutes by default.

Gravitino should act as the **SCIM interface service** in this model. That requires:

- HTTP endpoints that conform to SCIM 2.0,
- persistent storage for synchronized users and groups,
- and a secure way to scope each SCIM request to a target metalake via the URL path.

---

## 2. Goals

1. **Implement SCIM 2.0 provisioning**: support create, read, **`PATCH`**, and delete for User and Group
   resources — **`PATCH active`** on Users only; Groups use **`PATCH members`** (RFC 7643 has no Group
   `active`; IdPs deprovision groups via **`DELETE`**).

2. **Support auxiliary deployment**: run SCIM via **Apache SCIMple** as a `GravitinoAuxiliaryService`
   in the **same JVM** as the Gravitino server.

3. **Deliver token management as a required plugin**: expose `/api/metalakes/{metalake}/scim/tokens` on the main REST
   port (**8090**) through `gravitino.server.rest.extensionPackages`. Enabling SCIM requires both
   the auxiliary service and this extension package (see **Configuration**).

4. **Ensure data consistency**: keep Gravitino `user_meta`, `group_meta`, and **`scim_user_group_rel`**
   aligned with the IdP over repeated provisioning cycles.

5. **Support multi-metalake deployments**: expose metalake scope in the SCIM URL path
   (`/scim/v2/metalakes/{metalake}`) so each IdP connector targets one metalake explicitly.

6. **Protect administrative interfaces**: restrict SCIM token management APIs to the **metalake
   owner** of the target metalake while exposing only the SCIM protocol endpoints to the IdP.

7. **Use database group membership for OAuth authorization when SCIM is enabled**: for user login
   (OAuth/JWT on **8090**), resolve group names from **`scim_user_group_rel`** per metalake — not from JWT
   `groups` claims (see **OAuth login group membership**).

---

## 3. Non-Goals

1. **Full SCIM attribute coverage**: only basic User and Group attributes required for Gravitino
   user and group metadata are synchronized. Extended profile fields such as locale, timezone, title,
   phone numbers, and addresses are out of scope.

2. **Password or credential provisioning**: SCIM is used for identity metadata synchronization, not
   for storing or rotating login passwords.

3. **Replacing login authentication**: SCIM provisioning complements Gravitino's existing OAuth and
   local authentication flows; it does not replace them. When SCIM is enabled, **OAuth still proves
   identity** (username), but **group membership for authorization** comes from **`scim_user_group_rel`**
   (see **OAuth login group membership**), not from JWT `groups` claims.

4. **SCIM HTTP on the main Gravitino port (8090)**: SCIMple **1.0.0-M1** depends on **Jersey 3 /
   Jakarta EE** (`jakarta.ws.rs`); the main Gravitino server uses **Jersey 2 / `javax.ws.rs`**. SCIM
   therefore runs in an isolated **`scim-server`** stack (Jersey 3) on a **dedicated port** (default
   **9201**), not on port **8090**.

---

## 4. SCIM Token Design

Gravitino issues **opaque bearer tokens** for IdP SCIM integration. These are integration tokens issued
and managed by Gravitino administrators; they are not OAuth JWTs, user passwords, or personal access
tokens used for direct user login.

The sections below compare common enterprise SCIM token models, document the **JWT vs opaque**
decision for Gravitino, define Gravitino's token approach, then cover persistence, creation, and
validation rules.

### 4.1 Comparison of Enterprise SCIM Token Approaches

| Topic                                | Databricks                                                  | Snowflake (Legacy)                                                      | Snowflake (PAT)                                                                                |
|--------------------------------------|-------------------------------------------------------------|-------------------------------------------------------------------------|------------------------------------------------------------------------------------------------|
| Integration mode                     | IdP push (SCIM 2.0)                                         | IdP push (SCIM 2.0)                                                     | IdP push (SCIM 2.0)                                                                            |
| Credential model                     | Account SCIM Token                                          | SCIM access token                                                       | SERVICE-user programmatic access token (PAT)                                                   |
| Token format (documented)            | Not publicly disclosed (opaque bearer)                      | Not publicly disclosed (opaque bearer)                                  | JWT (`ES256` `token_secret`)                                                                   |
| Plaintext shown once                 | Yes (on create / regenerate)                                | Yes (on create / regenerate)                                            | Yes (on create / regenerate)                                                                   |
| API scope restriction                | SCIM API only; token cannot call other Databricks REST APIs | SCIM API only                                                           | SCIM API when PAT is role-restricted to SCIM role                                              |
| Token lifetime                       | Long-lived until regenerated; no fixed expiry documented    | **6 months** per generated token                                        | Configurable `DAYS_TO_EXPIRY` (up to 365 days)                                                 |
| Named tokens                         | No (single account-level token)                             | No (one token per SCIM security integration)                            | Yes (`token_name` on PAT object)                                                               |
| Multiple concurrent tokens per scope | No (regenerate replaces account token)                      | No (regenerate per integration)                                         | Yes (multiple PATs per SERVICE user)                                                           |
| Rotation grace period                | Fixed **24 hours** for previous token                       | Not documented; operator replaces IdP secret after generating new token | Configurable `EXPIRE_ROTATED_TOKEN_AFTER_HOURS`; default 24h; **0** for immediate invalidation |
| Token management permissions         | Account admin                                               | ACCOUNTADMIN                                                            | ACCOUNTADMIN or role with privilege                                                            |

#### Summary

All three systems use IdP push with bearer tokens. Databricks and Snowflake (Legacy) use **opaque** credentials whose generation
algorithms are not publicly documented; Snowflake (PAT) issues a **JWT** (`token_secret`). They
differ mainly in **operational simplicity**, **token lifecycle flexibility**, and **blast radius
when a secret leaks**.

| Approach           | Limitations                                                                                                                                                                                                   |
|--------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Databricks         | 1. No named or parallel tokens — **regenerate replaces** the only token.<br>2. One secret shared across all IdP connectors for the account.                                                                   |
| Snowflake (Legacy) | 1. **6-month** token expiry adds recurring rotation work.<br>2. No named or parallel tokens — **regenerate replaces** the only token.<br>3. Rotation grace period not documented — IdP secret swap is manual. |
| Snowflake (PAT)    | 1. No non-expiring tokens — `DAYS_TO_EXPIRY` is mandatory (maximum 365 days).                                                                                                                                 |

### 4.2 JWT vs Opaque Token

Gravitino SCIM integration requires a long-lived **shared secret** between the IdP and the Gravitino
server. Two common patterns exist: **opaque bearer tokens** (random string + server-side lookup) and
**JWTs** (self-contained, signed payloads). Both can appear in `Authorization: Bearer ...`; the
difference is what the bearer string encodes and how the server validates it.

#### Comparison

| Topic                   | Opaque bearer token (chosen)                                                                         | JWT bearer token                                              |
|-------------------------|------------------------------------------------------------------------------------------------------|---------------------------------------------------------------|
| Token shape             | Random string                                                                                        | `header.payload.signature` (Base64url segments)               |
| Validation              | Hash presented token; **lookup** `scim_token`; enforce `metalake_id` matches URL `{metalake}`        | Verify signature with public key; optionally read `exp` claim |
| Server state            | **Required** (hash, name, `expires_at`, revoke)                                                      | Can be stateless if trust is signature-only                   |
| Expiry                  | Server-side `expires_at`; **`now >= expires_at` → 419** at SCIM auth (see **Token Validation Flow**) | Embedded `exp` claim; valid until expiry unless blocklisted   |
| Immediate revoke        | Soft-delete → **instant** 401                                                                        | Token may remain valid until `exp` without a denylist         |
| Plaintext shown once    | Natural fit (API key model)                                                                          | Possible, but JWT is often re-derived or re-signed            |
| SCIM industry precedent | Databricks; Snowflake (Legacy)                                                                       | Snowflake (PAT) — unified with programmatic PAT platform      |

#### Why opaque fits Gravitino SCIM

1. **Integration secret, not user session.** SCIM credentials are configured once in the IdP and reused
   for interval-based push. This matches an **API key** model more closely than a short-lived access
   token.
2. **Revoke and expiry are server-authoritative.** Operators need create, delete, and optional
   `expires_at` to take effect **immediately** on the next request. Opaque tokens enforce that with a
   single DB lookup; JWTs require short TTLs, blocklists, or accepting lag until `exp`.
3. **Clear separation from login auth.** Gravitino continues to use OAuth/JWT for direct user login.
   SCIM opaque tokens avoid operators confusing integration secrets with user access tokens.

#### Why JWT was not chosen for SCIM

JWT remains appropriate for **user authentication** and **short-lived authorization** where claims
and signature verification add value. For SCIM, JWT would introduce:

- Key management and JWKS rotation overhead unrelated to provisioning semantics,
- Weaker **immediate revocation** unless paired with short expiry and operational rotation anyway,
- Overlap with Snowflake PAT's JWT model without requiring Gravitino to unify SCIM and PAT platforms.

Snowflake PAT demonstrates that JWT **can** work for SCIM when a product already centers on JWT
PATs; Gravitino has no such constraint and prioritizes simplicity and instant revoke.

#### Decision

**Gravitino SCIM tokens are opaque bearer tokens.** Validation uses prefix check, SHA-256,
DB lookup, optional `expires_at`). User login and OAuth flows continue to use Gravitino's existing
JWT/OAuth mechanisms and must not accept SCIM opaque tokens.

### 4.3 Gravitino SCIM Token Model

Gravitino follows the same overall pattern as the systems above: the IdP configures a **metalake-scoped
SCIM base URL** and a bearer token. Gravitino resolves the target metalake from `{metalake}` in the
URL path and stores **`metalake_id` on each `scim_token` row**. A SCIM token is valid **only for the
metalake it was created under**; it cannot provision users or groups in other metalakes even if the
URL path is changed.

| Topic                                | Gravitino                                                                                                                                                                                                                                                                                                             |
|--------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Integration mode                     | IdP push (SCIM 2.0)                                                                                                                                                                                                                                                                                                   |
| Credential model                     | Opaque SCIM token (metalake-scoped)                                                                                                                                                                                                                                                                                   |
| Plaintext shown once                 | Yes (on create and rotate only)                                                                                                                                                                                                                                                                                       |
| API scope restriction                | SCIM API only under `/scim/v2/metalakes/{metalake}/*` for the token's metalake                                                                                                                                                                                                                                        |
| Token lifetime                       | Optional fixed expiry via server-side `expires_at`; omit on create for no time limit                                                                                                                                                                                                                                  |
| Named tokens                         | Yes (`token_name` unique per metalake)                                                                                                                                                                                                                                                                                |
| Multiple concurrent tokens per scope | Yes (multiple tokens per metalake)                                                                                                                                                                                                                                                                                    |
| Rotation model                       | **In-place rotate** (`POST .../scim/tokens/{tokenName}/rotate`): new secret for an existing `tokenName`; old bearer invalid immediately after success. **Cutover rotate**: create a new named token, update the IdP, verify, then `DELETE` the old token (both valid until delete); **no server-side grace deadline** |
| Token management permissions         | **Metalake owner** of `{metalake}` (`METALAKE::OWNER`; same as other metalake admin APIs)                                                                                                                                                                                                                             |

### 4.4 Storage Model and Token Format

#### Token Format

Each SCIM bearer token is an **opaque** string assembled at creation time:

```text
gravitino_scim_{encoded_secret}
```

| Component         | Rule                                                                                                        |
|-------------------|-------------------------------------------------------------------------------------------------------------|
| Prefix            | Fixed `gravitino_scim_` for fast rejection and secret-scanning tools                                        |
| Entropy source    | OS CSPRNG via `SecureRandom.nextBytes(32)` (256 bits)                                                       |
| Encoding          | Base64url (URL-safe Base64, no padding) over the 32 raw bytes via `Base64.getUrlEncoder().withoutPadding()` |
| Plaintext storage | Never persisted, logged, or returned after the create or rotate response                                    |

There is no IETF standard for opaque integration token **string format** (Bearer transport is defined
in [RFC 6750](https://www.rfc-editor.org/rfc/rfc6750.html)). Vendors such as
[GitHub](https://github.blog/engineering/platform-security/behind-githubs-new-authentication-token-formats/)
(`ghp_`, `github_pat_`), [Stripe](https://docs.stripe.com/keys) (`sk_live_`), and
[npm](https://github.blog/security/announcing-npms-new-access-token-format/) (`npm_`) commonly use a
**`{prefix}_{high-entropy-random}`** pattern for identification and secret scanning; Gravitino follows
the same convention with `gravitino_scim_`.

The prefix is **not** secret; security comes from the high-entropy suffix. SHA-256 is computed over
the **full** bearer string including the prefix.

Example shape (illustrative only; `<REDACTED>` is a placeholder, not a real token):

```text
gravitino_scim_<REDACTED>
```

#### Storage Model

Gravitino never stores token plaintext. Token metadata is stored in `scim_token`:

| Column        | Usage                                                                                                      |
|---------------|------------------------------------------------------------------------------------------------------------|
| `metalake_id` | Foreign scope: token is valid only for this metalake (same pattern as `user_meta.metalake_id`)             |
| `token_name`  | Operator-readable name (unique per metalake)                                                               |
| `token_hash`  | SHA-256 hex digest of the full bearer token                                                                |
| `expires_at`  | Absolute expiry time in epoch milliseconds; **`0` means no fixed expiry (never expires)**                  |
| `audit_info`  | Serialized Gravitino `AuditInfo` JSON (creator and timestamps); same pattern as `user_meta` / `group_meta` |

Expiry is **server-side metadata**, not embedded in the opaque bearer string (unlike a JWT `exp`
claim). Operators see `expiresAt` in list APIs; IdP configurations do not need to parse the token
to learn when it expires.

SHA-256 is used because the secret is high-entropy and server-generated, matching common integration
token practice. Argon2id is reserved for low-entropy user passwords. The hash is **one-way**; a
leaked hash cannot be reversed to recover the token.

### 4.5 Token Creation Flow

Token creation is triggered by the **metalake owner** through
`POST /api/metalakes/{metalake}/scim/tokens` on the **main REST port (8090)**. Multiple named tokens
may coexist per metalake. Operators **rotate in place** with `POST .../scim/tokens/{tokenName}/rotate`
(same name, new secret) or run a **cutover** by creating a new named token and deleting the old one
after IdP update (see **Token Rotation Flow** and operational notes below). Metalake resolution uses the same JAX-RS `{metalake}` path
parameter pattern as `UserOperations` — **not** `ScimURLScopeResolver` (which runs only on the SCIM aux
listener at **9201**).

```text
metalake owner → POST /api/metalakes/{metalake}/scim/tokens
  │
  ▼
Authorize METALAKE::OWNER for {metalake} (after main-server user authentication)
  │
  ▼
Resolve {metalake} → metalake_id (404 if metalake does not exist)
  │
  ▼
Validate tokenName (unique per metalake among non-deleted tokens)
  │
  ▼
SecureRandom.nextBytes(32)  ← OS CSPRNG
  │
  ▼
Base64url-encode → assemble gravitino_scim_{encoded}
  │
  ▼
SHA-256(full token) → token_hash
  │
  ▼
Compute expires_at from expiresInDays (optional)
  │
  ▼
INSERT scim_token (metalake_id, token_name, token_hash, expires_at, audit_info, deleted_at = 0)
  │
  ▼
Return tokenValue and expiresAt once in response body
```

Steps:

1. **Authenticate caller**: validate user credentials via `gravitino.authenticators` (same as main
   REST API); reject unauthenticated callers with **401 Unauthorized**.
2. **Authorize caller**: reject authenticated users who are not the **metalake owner** of `{metalake}`
   (direct owner or member of an owner group, per `METALAKE::OWNER`) with **403 Forbidden**
   before any token material is generated.
3. **Resolve metalake**: parse `{metalake}` from the request path and resolve to `metalake_id`; return
   **404 Not Found** if the metalake does not exist.
4. **Validate `tokenName`**: required, unique among rows with the same `metalake_id` and `deleted_at = 0`.
5. **Generate entropy**: fill a 32-byte array with `SecureRandom.nextBytes`; do not use
   `java.util.Random`, UUIDs, or other predictable sources.
6. **Encode and assemble**: Base64url-encode the bytes (no padding) and prefix with `gravitino_scim_` to form
   the plaintext bearer token.
7. **Hash for storage**: compute `SHA-256(UTF-8 bytes of full token)` and persist the lowercase
   hex digest in `scim_token.token_hash`.
8. **Set expiry**: if the request includes `expiresInDays`, set
   `expires_at = now + expiresInDays × 24h` in epoch milliseconds; otherwise set `expires_at = 0`
   (no fixed expiry).
9. **Persist metadata**: insert `metalake_id`, `token_name`, `token_hash`, `expires_at`, `audit_info`
   and `deleted_at = 0`.
10. **Return once**: include `tokenValue` and `expiresAt` in the HTTP response; subsequent list APIs
    never return plaintext.

Error handling:

| Condition                                    | Result                                        |
|----------------------------------------------|-----------------------------------------------|
| Missing or invalid user auth                 | **401 Unauthorized**                          |
| Caller is not metalake owner of `{metalake}` | **403 Forbidden**                             |
| Metalake not found                           | **404 Not Found**                             |
| Missing or empty `tokenName`                 | **400 Bad Request**                           |
| Duplicate `tokenName`                        | **409 Conflict** (within the same metalake)   |
| CSPRNG / persistence failure                 | **500 Internal Server Error**; no partial row |

Operational notes:

- Operators copy `tokenValue` into the IdP SCIM connector secret field and configure the **same**
  metalake in the SCIM base URL (`.../metalakes/{metalake}`).
- When `expiresInDays` is set, plan rotation before `expiresAt`; expired tokens fail SCIM
  authentication with **419** (not 401) until replaced.
- **In-place rotation**: `POST /api/metalakes/{metalake}/scim/tokens/{tokenName}/rotate`, copy the new
  `tokenValue` into the IdP secret, verify provisioning. The previous bearer for that `tokenName` fails
  on the **next** SCIM request (same immediate revoke semantics as **DELETE**).
- **Cutover rotation** (new name or parallel tokens): `POST /api/metalakes/{metalake}/scim/tokens` with
  a new `tokenName`, update the IdP secret, verify provisioning, then
  `DELETE /api/metalakes/{metalake}/scim/tokens/{oldTokenName}`. Both tokens authenticate for
  **that metalake** until the old row is soft-deleted.
- Application logs must not record `tokenValue` or full bearer strings.

### 4.5.1 Token Rotation Flow

In-place rotation is triggered by the **metalake owner** through
`POST /api/metalakes/{metalake}/scim/tokens/{tokenName}/rotate` on port **8090**. The `tokenName` is
unchanged; only `token_hash` (and optionally `expires_at`) is replaced. `ScimTokenService` exposes
`rotateScimToken(String metalake, String tokenName, @Nullable Integer expiresInDays)`.

```text
metalake owner → POST /api/metalakes/{metalake}/scim/tokens/{tokenName}/rotate
  │
  ▼
Authorize METALAKE::OWNER for {metalake} (after main-server user authentication)
  │
  ▼
Resolve {metalake} → metalake_id (404 if metalake does not exist)
  │
  ▼
Load scim_token row by (metalake_id, tokenName, deleted_at = 0) — 404 if missing
  │
  ▼
SecureRandom.nextBytes(32)  ← OS CSPRNG
  │
  ▼
Base64url-encode → assemble gravitino_scim_{encoded}
  │
  ▼
SHA-256(full token) → new token_hash
  │
  ▼
Apply expiresInDays from body if present; else keep existing expires_at
  │
  ▼
UPDATE scim_token SET token_hash, expires_at (if changed), audit_info
  │
  ▼
Return tokenValue and expiresAt once in response body (same tokenName)
```

Steps mirror **Token Creation Flow** except the row already exists: validate metalake owner, resolve
`metalake_id`, load the active row, generate new opaque material, atomically update `token_hash` (and
`expires_at` when `expiresInDays` is supplied), update `audit_info`, return plaintext once.

| Condition                         | Result                                       |
|-----------------------------------|----------------------------------------------|
| Missing or invalid user auth      | **401 Unauthorized**                         |
| Caller is not metalake owner      | **403 Forbidden**                            |
| Metalake not found                | **404 Not Found**                            |
| `tokenName` not found or deleted  | **404 Not Found**                            |
| Invalid `expiresInDays` (if sent) | **400 Bad Request**                          |
| CSPRNG / persistence failure      | **500 Internal Server Error**; row unchanged |

There is **no grace period** for the previous bearer after rotate; validation uses only the updated
`token_hash`.

### 4.6 Token Validation Flow (`ScimBearerAuthFilter`)

`ScimBearerAuthFilter` runs on the **SCIM auxiliary listener (9201)** only. It performs **two
checks** before `ScimURLScopeResolver`:

1. **Authentication**: validate the opaque bearer credential (format, hash lookup, expiry).
2. **Authorization**: confirm the token is **scoped to the URL metalake** — i.e. the matched
   `scim_token.metalake_id` equals the `{metalake}` in the request path. A token created for
   `metalake_a` must not provision under `.../metalakes/metalake_b/...`.

It does **not** attach metalake context for SCIMple — that is `ScimURLScopeResolver`'s
responsibility (see **What the SCIM auxiliary service implements**). Request pipeline on IdP paths:

```text
ScimBearerAuthFilter     →  ScimURLScopeResolver     →  SCIMple
 authentication +           URL scope → metalake_id
 metalake authorization     (request context)
 (port 9201)
```

Every IdP-facing request under `/scim/v2/metalakes/{metalake}/*` passes through **authentication
and metalake authorization** before `ScimURLScopeResolver` and SCIMple handler logic run.

```text
IdP → Authorization: Bearer gravitino_scim_...
  │
  ▼
Path under /scim/v2/metalakes/{metalake}/ ?
  │ no                         │ yes
  ▼                            ▼
(not SCIM bearer auth)     ┌──────────────────────────┐
                           │ Authentication           │
                           └──────────────────────────┘
                                      │
                                      ▼
                            Extract Bearer token
                                      │
                                      ▼
                            Starts with gravitino_scim_ ?
                            │ no                   │ yes
                            ▼                      ▼
                       401 Unauthorized    Parse {metalake} from path
                                                      │
                                                      ▼
                                        Resolve metalake_id (404 if metalake missing)
                                                      │
                                                      ▼
                                            SHA-256(presented token)
                                                      │
                                                      ▼
                              Lookup scim_token by token_hash (deleted_at = 0)
                                                      │
                                        ┌─────────────┴─────────────┐
                                        ▼                           ▼
                                  row found                    no row
                                        │                           │
                                        ▼                           ▼
                            expires_at > 0 and              401 Unauthorized
                            now >= expires_at ?
                            │ yes                 │ no
                            ▼                     ▼
                      419 TokenExpired      ┌──────────────────────────┐
                                            │ Authorization            │
                                            │ (metalake scope)         │
                                            └──────────────────────────┘
                                                      │
                                                      ▼
                            row.metalake_id == URL metalake_id ?
                            │ no                              │ yes
                            ▼                                 ▼
                     401 Unauthorized              Attach token auth context
                                                      │
                                                      ▼
                            Continue to ScimURLScopeResolver, then SCIMple
```

Steps:

1. **Scope the request**: apply `ScimBearerAuthFilter` only to paths under
   `/scim/v2/metalakes/{metalake}/` on port **9201**.
2. **Extract bearer credential** *(authentication)*: parse `Authorization: Bearer <token>`; reject
   missing or malformed headers with **401 Unauthorized**.
3. **Prefix check** *(authentication)*: reject tokens that do not start with `gravitino_scim_`
   without hitting the database.
4. **Resolve URL metalake** *(authentication prerequisite)*: parse `{metalake}` from the path and
   resolve to `metalake_id`; return **404 Not Found** if the metalake does not exist.
5. **Hash presented token** *(authentication)*: compute `SHA-256(full presented token)` using the
   same encoding rules as creation.
6. **Database lookup** *(authentication)*: find a row where `token_hash` matches and `deleted_at = 0`.
7. **Check expiry** *(authentication)*: if `expires_at > 0` and the current time is **greater than or
   equal to** `expires_at`, return **419** immediately with `TokenExpiredException` (message such as
   `SCIM token has expired`) — independent of whether the expiry background task has set `deleted_at`.
   If `expires_at = 0`, skip this check (token does not expire by time).
8. **Authorize metalake scope** *(authorization)*: if `scim_token.metalake_id` does not equal the
   URL `metalake_id`, return **401 Unauthorized** — the token is not permitted to call SCIM APIs for
   this metalake (same response as invalid token; do not leak cross-metalake hints).
9. **Fail closed** *(authentication)*: if no row matches, return **401 Unauthorized**; do not fall
   back to user OAuth credentials or anonymous access. Expired tokens use **419** (step 7), not 401.
10. **Authorize path only** *(authorization)*: a valid SCIM token grants access to SCIM protocol
    endpoints on **9201** only; it must not authorize unrelated Gravitino REST APIs on **8090** or
    `/api/metalakes/{metalake}/scim/tokens` (those require user auth + **metalake owner** of `{metalake}`).
11. **Attach token context**: on success, attach request-scoped SCIM token metadata (at minimum
    `token_name` and `metalake_id` from the matched `scim_token` row) for bearer authorization.
12. **Hand off**: pass the request to `ScimURLScopeResolver`, then SCIMple invokes
    `ScimUserRepositoryAdapter` / `ScimGroupRepositoryAdapter` — not HTTP to Gravitino management REST.

## 5. Proposal

### 5.0 Open-Source SCIM Server Library Comparison

Gravitino does not implement the SCIM 2.0 HTTP protocol layer from scratch. The candidates below are
Java libraries that provide Users/Groups HTTP resources and delegate persistence to application code.
Gravitino-owned concerns — opaque `gravitino_scim_*` tokens, `/api/metalakes/{metalake}/scim/tokens`,
metalake URL scope, and writes to `user_meta` / `group_meta` — are the same regardless of library.

| Dimension                        | [SAP SCIMono](https://github.com/SAP-archive/scimono)                | [Apache SCIMple](https://github.com/apache/directory-scimple) |
|----------------------------------|----------------------------------------------------------------------|---------------------------------------------------------------|
| **License**                      | Apache-2.0                                                           | Apache-2.0                                                    |
| **Language**                     | Java                                                                 | Java                                                          |
| **GitHub stars**                 | 30                                                                   | 99                                                            |
| **Maintenance**                  | Repository **archived** (read-only); artifacts on Maven Central only | **Active** Apache project                                     |
| **Maven artifact**               | `com.sap.scimono:scimono-server:0.1.4`                               | `org.apache.directory.scimple:scim-server:1.0.0-M1`           |
| **/Users**                       | Yes                                                                  | Yes                                                           |
| **/Groups**                      | Yes                                                                  | Yes                                                           |
| **/Me**                          | Yes                                                                  | Yes                                                           |
| **/ServiceProviderConfig**       | Yes                                                                  | Yes                                                           |
| **/ResourceTypes**               | Yes                                                                  | Yes                                                           |
| **/Schemas**                     | Yes                                                                  | Yes                                                           |
| **/Bulk**                        | No                                                                   | Yes                                                           |
| **filter**                       | Yes                                                                  | Yes                                                           |
| **pagination**                   | Yes                                                                  | Yes                                                           |
| **PATCH**                        | Yes                                                                  | Yes                                                           |
| **Jersey**                       | Jersey 3 + `jakarta.ws.rs`                                           | Jersey 3 + `jakarta.ws.rs`                                    |
| **Auxiliary `:9201` deployment** | Yes                                                                  | Yes                                                           |
| **Industry usage**               | SAP                                                                  | community IdP (Entra/Okta) integrations                       |

**Decision:** adopt **Apache SCIMple** as the SCIM server foundation. **SCIMono** is archived and pinned at
`0.1.4` with no upstream maintenance; SCIMple provides the same built-in HTTP resources with an
actively maintained Apache release line and a **`Repository` adapter** integration model that maps cleanly to
Gravitino's in-process `AccessControlDispatcher` calls. Both libraries require the same auxiliary
deployment pattern (isolated `scim-server` classpath and dedicated port **9201**).

### 5.1 Implementation Foundation (Apache SCIMple)

Gravitino **does not** implement the SCIM 2.0 HTTP protocol layer from scratch. The design adopts
**[Apache SCIMple](https://github.com/apache/directory-scimple)** (`org.apache.directory.scimple:scim-server`, pin
**1.0.0-M1** on Maven Central) as the SCIM server foundation. SCIMple is an **Apache License 2.0**
open-source Java SCIM 2.0 implementation maintained by the Apache Directory project.

SCIMple **1.0.0-M1** is built for **Jersey 3** and **`jakarta.ws.rs`** (parent POM pins
`jersey-bom` **3.1.5**). The main Gravitino server uses **Jersey 2.41** and **`javax.ws.rs`**. SCIM
therefore runs in a dedicated **`scim-server`**
module with its own Jetty listener and **Jersey 3** classpath (packaged under `scim-server/libs`),
loaded via `gravitino.scim.classpath` as a **`GravitinoAuxiliaryService`** with an isolated
classpath via `AuxiliaryServiceManager`.

SCIMple **does not generate or persist** IdP integration tokens. It only exposes SCIM resources and
declares supported authentication schemes in `ServiceProviderConfig`. Token creation, hashing,
validation, and `/api/metalakes/{metalake}/scim/tokens` admin APIs remain **Gravitino-owned** (token design and admin APIs in this document).

`ScimTokenRESTFeature` registers JAX-RS resources, an HK2 binder, and **`METALAKE::OWNER`**
authorization on `ScimTokenOperations`. On startup, validate that SCIM auxiliary service and the token admin extension package are
configured together when SCIM is enabled (fail fast if one is missing).

#### What SCIMple provides (reuse)

| Area                        | SCIMple capability                                                                                                | Gravitino usage                                                                                                     |
|-----------------------------|-------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------|
| HTTP resources              | Users, Groups, discovery (`ServiceProviderConfig`, `ResourceTypes`, `Schemas`) via `ScimResourceHelper`           | `GravitinoScimApplication` on the SCIM Jetty/Jersey 3 listener                                                      |
| Filtering                   | SCIM filter grammar (`Filter`, `FilterExpressions`)                                                               | Repository adapters implement `Repository.find()` and apply filter when listing users/groups (filter support below) |
| Pagination                  | `startIndex` / `count` list responses via `ScimRequestContext`                                                    | Reuse as-is                                                                                                         |
| Error / list response shape | SCIM JSON envelopes, exception mappers, status codes                                                              | Reuse; constrain via repository adapters and `ServerConfiguration`                                                  |
| Extension model             | `Repository<ScimUser>`, `Repository<ScimGroup>` registered through CDI `RepositoryRegistry` (`ScimpleComponents`) | `ScimUserRepositoryAdapter` / `ScimGroupRepositoryAdapter` call `AccessControlDispatcher` in-process                |

#### What the SCIM auxiliary service implements

| Area                   | Rationale                                                                                                                                                                                                                                                                                                                                                                                                                               |
|------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ScimConfig`           | Parse `gravitino.scim.*` from the `AuxiliaryServiceManager` `serviceInit` map                                                                                                                                                                                                                                                                                                                                                           |
| `ScimBearerAuthFilter` | On port **9201**: **authentication** (opaque `gravitino_scim_*` validation; **401** invalid/revoked, **419** expired) + **authorization** (`scim_token.metalake_id` must match URL `{metalake}`); delegates to `ScimTokenService`                                                                                                                                                                                                       |
| `ScimURLScopeResolver` | On port **9201** only: after bearer auth, parse `{metalake}` from `/scim/v2/metalakes/{metalake}/`, resolve to `metalake_id`, attach request-scoped metalake context for repository adapters; **404** if the metalake does not exist; does **not** validate tokens or perform authorization. Implemented as a Servlet `Filter`, but named by role (URL scope resolution), not `*Filter`, to distinguish it from `ScimBearerAuthFilter`. |
| `ServerConfiguration`  | Advertise `patch.supported=true`, `bulk.supported=false`, `sort.supported=false`, `filter.supported=true`, etc. (`ServiceProviderConfig` capabilities)                                                                                                                                                                                                                                                                                  |
| `MetalakeScimRoot`     | Sub-resource locator under `/scim/v2/metalakes/{metalake}`; removes top-level `/Users` duplication                                                                                                                                                                                                                                                                                                                                      |
| PUT / Bulk / Me        | Repository adapters throw **405** or routes return **405** (unsupported endpoints). **PATCH**: Users — **`active` only**; Groups — **`members` only** (see **PATCH support**).                                                                                                                                                                                                                                                          |

#### What the token admin plugin implements

| Area                         | Rationale                                                                                                                                                                                                                                                                           |
|------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ScimTokenRESTFeature`       | Jersey 2 `Feature` on **8090** via `gravitino.server.rest.extensionPackages`; wires `/api/metalakes/{metalake}/scim/tokens`, `AuthenticationFilter`, **`METALAKE::OWNER`** authorization, and **startup validation** for SCIM OAuth settings; initializes `ScimUserGroupRelManager` |
| `ScimTokenOperations`        | JAX-RS resource at `@Path("/metalakes/{metalake}/scim/tokens")`; `@AuthorizationExpression(expression = "METALAKE::OWNER")` on create/rotate/delete; resolves `{metalake}` in the REST layer (same pattern as `MetalakeOperations`); delegates to `ScimTokenManager`                |
| `ScimOAuthRequestPathFilter` | Servlet filter registered through `gravitino.server.webserver.customFilters`; captures request path before OAuth runs so metalake scope is available to `ScimOAuthPrincipalMapper`                                                                                                  |
| `ScimOAuthPrincipalMapper`   | `PrincipalMapper` registered as `gravitino.authenticator.oauth.principalMapper`; maps JWT identity to username and loads metalake-scoped groups from `scim_user_group_rel` via `ScimUserGroupRelManager.listGroupNamesForUser`                                                      |

#### Request path (not HTTP forwarding)

IdP traffic does **not** go through SCIMple to Gravitino REST APIs:

```text
IdP ──HTTPS──► SCIM Jetty (Jersey 3 + SCIMple)
                  ├── ScimBearerAuthFilter (gravitino_scim_*)
                  ├── ScimURLScopeResolver (URL {metalake} → metalake_id)
                  └── SCIMple UserResourceImpl / GroupResourceImpl
                       └── ScimUserRepositoryAdapter / ScimGroupRepositoryAdapter
                            └── AccessControlDispatcher (in-process)
                                 └── user_meta / group_meta
```

**Phases 3–4** deliver opaque token generation, persistence, bearer validation, and admin **service**
logic (unit-testable without HTTP). **Phase 5** wires the Jersey 3 SCIM auxiliary listener,
`ScimConfig` (read `gravitino.scim.*` from `AuxiliaryServiceManager`), and token admin REST
(`ScimTokenRESTFeature` via `extensionPackages`). **Phase 6** implements SCIM repository adapters
and end-to-end User/Group provisioning.

### 5.2 Architecture

SCIM is delivered as a **`GravitinoAuxiliaryService`**: Gravitino and `scim-server` run in the
**same JVM**, managed by `AuxiliaryServiceManager`.

#### Why `GravitinoAuxiliaryService` (not main REST on 8090)

SCIM runs on a dedicated auxiliary listener, not the main Gravitino JAX-RS app on port **8090**:

| Reason                        | Explanation                                                                                                                                                               |
|-------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Jersey / Jakarta mismatch** | SCIMple **1.0.0-M1** requires **Jersey 3** and `jakarta.ws.rs`; the main server uses **Jersey 2** and `javax.ws.rs`. Mixing both on one JAX-RS application is not viable. |
| **Isolated classpath**        | `AuxiliaryServiceManager` loads `scim-server/libs` in an `IsolatedClassLoader`, so SCIMple dependencies do not conflict with the main server.                             |
| **Dedicated listener**        | IdP traffic uses `gravitino.scim.httpPort` (default **9201**), enabling network policies that expose SCIM without opening management APIs on **8090**.                    |

Repository adapters initialize **`GravitinoEnv`** in-process and call `AccessControlDispatcher`
directly — there is no HTTP proxy to `/api/metalakes/...`.

#### 5.2.1 Auxiliary service deployment

Add `scim` to `gravitino.auxService.names` and register the token admin plugin via
`gravitino.server.rest.extensionPackages` in `gravitino.conf`. `AuxiliaryServiceManager` loads
`ScimRESTService` from `gravitino.scim.classpath` with an **isolated classloader** (SCIMple +
Jersey 3). The SCIM HTTP listener uses **`gravitino.scim.httpPort`** (default **9201**). The main
Gravitino REST API on port **8090** serves `/api/metalakes/{metalake}/scim/tokens` via the required
token admin plugin (`gravitino.server.rest.extensionPackages`). Token admin APIs do **not** run on
port **9201** and do **not** use `ScimURLScopeResolver`.

The auxiliary service shares the Gravitino JVM **`GravitinoEnv`** (entity store, JDBC) so repository
adapters can write `user_meta` / `group_meta` in-process.

All **`gravitino.scim.*`** settings (including `userMapper` / `groupMapper`) are in **`conf/gravitino.conf`**.
`AuxiliaryServiceManager` collects `gravitino.scim.*` keys for the `scim` auxiliary service, strips the
`gravitino.` prefix, and passes the result to `ScimRESTService.serviceInit()` → `ScimConfig` (short keys
such as `classpath`, `httpPort`, `userMapper`). `gravitino.scim.classpath` is
**`scim-server/libs` only** (isolated jars; no application `.conf` under `scim-server/conf`).

```text
conf/gravitino.conf  (gravitino.scim.*)
  │
  ▼
AuxiliaryServiceManager
  │
  ▼
ScimRESTService.serviceInit()  →  ScimConfig
  │
  ▼
ScimUserRepositoryAdapter / ScimGroupRepositoryAdapter (Phase 6)
```

```text
Cloud IdP (Azure AD / Okta / ...)
  │
  │  SCIM 2.0 push (Bearer gravitino_scim_*)
  ▼
Gravitino Server JVM
  └── SCIM aux :9201   (/scim/v2/metalakes/{metalake}/*)
        │
        ▼
      Relational metadata store (shared JDBC in this JVM)
        ├── user_meta
        └── group_meta
```

### 5.3 Metalake Scope and SCIM Base URL

Gravitino stores users and groups in metalake-scoped tables (`user_meta.metalake_id`,
`group_meta.metalake_id`). Metalake scope is carried in the **SCIM URL path** and enforced on each
**`scim_token` row** via `metalake_id` (aligned with `user_meta` / `group_meta`).

All IdP-facing SCIM resources are served under:

```text
/scim/v2/metalakes/{metalake}/
```

Examples:

```text
/scim/v2/metalakes/my_metalake/Users
/scim/v2/metalakes/my_metalake/Groups
/scim/v2/metalakes/my_metalake/ServiceProviderConfig
```

IdPs such as Okta and Microsoft Entra ID configure a single **Tenant URL / SCIM base URL** and append
standard SCIM resource paths to it. If the operator sets the base URL to
`https://{gravitino-host}:9201/scim/v2/metalakes/my_metalake`, the IdP calls:

```text
GET https://{gravitino-host}:9201/scim/v2/metalakes/my_metalake/ServiceProviderConfig
GET https://{gravitino-host}:9201/scim/v2/metalakes/my_metalake/Users
```

Discovery endpoints therefore use the **same** `{metalake}` URL prefix as provisioning endpoints.
This matches Databricks, where `/ServiceProviderConfig` also lives under the account-scoped SCIM
base URL.

**Provisioning endpoints** (`/Users`, `/Groups`, etc.) read or write metalake user/group metadata.

**Discovery endpoints** (`/ServiceProviderConfig`, `/ResourceTypes`, `/Schemas`) also sit under the
same URL prefix for IdP compatibility, but they return **metalake-agnostic** capability documents.
Gravitino does not read or write `user_meta` / `group_meta` when serving discovery responses; the
response body is identical regardless of `{metalake}`.

Binding rules for **all IdP-facing SCIM paths** under `/scim/v2/metalakes/{metalake}/`:

- `{metalake}` is the existing Gravitino metalake name configured by the operator.
- `ScimURLScopeResolver` resolves `{metalake}` to `metalake_id` and attaches request-scoped
  metalake context for repository adapters.
- If the metalake does not exist, `ScimURLScopeResolver` returns **404 Not Found** (before SCIMple runs).
- **Authorization** at bearer time: `ScimBearerAuthFilter` rejects tokens whose `scim_token.metalake_id`
  does not match the URL `{metalake}` (**401**).

This matches industry practice: Databricks embeds `{account_id}` in the URL; Snowflake embeds account
and optionally `{integration_uuid}` in the URL. Gravitino embeds `{metalake}` in the IdP base URL
for all SCIM resources, including discovery. **Metalake scope is enforced by the URL path and by
`scim_token.metalake_id` at bearer authorization time (`ScimBearerAuthFilter` on **9201**).

IdP operators configure **one SCIM connector per metalake**, each with:

- **SCIM base URL**: `https://{gravitino-host}:9201/scim/v2/metalakes/{metalake}` (port from
  `gravitino.scim.httpPort`)
- **Bearer token**: a SCIM token created for **that same metalake** via
  `POST /api/metalakes/{metalake}/scim/tokens`

A token created for `metalake_a` cannot authenticate SCIM requests under `.../metalakes/metalake_b/...`.

### 5.4 Push Mode and Operator Workflow

Gravitino uses **IdP push mode**: the IdP drives create, read, **PATCH**, and delete on its own schedule;
Gravitino does not poll the IdP.

**Operator workflow** (end-to-end):

1. Create a metalake in Gravitino.
2. Create a SCIM token for that metalake (`POST /api/metalakes/{metalake}/scim/tokens`; one or more
   per metalake; optional `expiresInDays`) — see **Create SCIM Token** under token management APIs.
3. Configure the IdP connector with SCIM base URL (`.../metalakes/{metalake}`) and bearer token —
   URL rules in **Metalake Scope and SCIM Base URL** above.
4. The IdP starts interval-based provisioning (for example Entra ID every 20–40 minutes).
5. Gravitino creates, updates, or deletes rows in `user_meta`, `group_meta`, and **`scim_user_group_rel`**
   per incoming SCIM requests (see **User `enabled`**, **`external_id`**, and **Group Membership (Users in Groups)**).

Steps 4–5 repeat on the IdP schedule.

### 5.7 Network Exposure

Recommended deployment split:

| Interface                  | Exposure                             | Examples                                                              |
|----------------------------|--------------------------------------|-----------------------------------------------------------------------|
| SCIM protocol endpoints    | Public or IdP-reachable network      | `/scim/v2/metalakes/{metalake}/Users`, `/ServiceProviderConfig`, etc. |
| SCIM token management APIs | Internal/administrative network only | `/api/metalakes/{metalake}/scim/tokens`                               |

An ingress proxy such as Nginx can expose only the SCIM listener port (for example **9201**) and
path prefix `/scim/v2/metalakes/*` to IdPs.

---

## 6. Gravitino Core Changes

> **Implementation note:** Implement all changes in this chapter in the Apache Gravitino OSS repository
> (`apache/gravitino`), then cherry-pick the commits into the enterprise distribution
> (`datastrato/gravitino-enterprise`).

SCIM repository adapters call **`AccessControlDispatcher` in-process** (not HTTP **8090**). Before Phase 6
adapters can implement SCIM list pagination, **User `PATCH active`**, and **Group `PATCH members`**, Gravitino core must add **paginated list**
and **`enabled`** on **`user_meta`**. This chapter is the single reference for those
prerequisites; see **`external_id` on `user_meta` and `group_meta`** for stable IdP identity and schema DDL; see **User `enabled`** for user disable semantics; see **OAuth login group membership** for how **`scim_user_group_rel`** feeds OAuth authorization; see **SCIM Protocol HTTP Interface** for HTTP mapping.

### 6.1 Current State

| Area                     | Today                                                      | SCIM gap                                                                                                                      |
|--------------------------|------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| List users / groups      | `listUsers` / `listGroups` return **full** metalake arrays | IdPs paginate `GET /Users` / `GET /Groups` with `startIndex` / `count`; full-table load is too slow                           |
| List sort                | No `ORDER BY` contract on list SQL                         | Okta requires **stable ordering** across pages (RFC 7644 §3.4.2.4)                                                            |
| User lifecycle           | `add*` / `get*` / `remove*` only; no disabled state        | IdP **`PATCH active: false`** must not call `removeUser` (that drops role bindings)                                           |
| Group lifecycle          | `add*` / `get*` / `remove*` only                           | IdPs deprovision via **`DELETE /Groups/{id}`** (RFC 7643 Group has no `active`)                                               |
| `user_meta`              | Soft delete via `deleted_at` only                          | Need persisted **`enabled`** column (see **User `enabled`**)                                                                  |
| `group_meta`             | Soft delete via `deleted_at` only                          | No `enabled` column — SCIM Group has no `active` attribute                                                                    |
| Group membership         | Not stored                                                 | Need **`scim_user_group_rel`** (see **Group Membership (Users in Groups)**)                                                   |
| OAuth login group source | JWT `groups` claim → `UserPrincipal` → JCasbin             | SCIM enabled → **`listGroupNamesForUser`** from **`scim_user_group_rel`** per metalake (see **OAuth login group membership**) |

**Non-goals for this section:** paginating `listUserNames` / `listGroupNames`, exposing sort parameters
on **8090** REST, or adding **8090** `PATCH` for `enabled` (SCIM uses core APIs only).

### 6.2 List Pagination

#### Motivation

Enterprise IdPs (Okta, Microsoft Entra ID, OneLogin) call SCIM list endpoints with **`startIndex`** and
**`count`**, and expect a **`ListResponse`** envelope including **`totalResults`**. Implementing pagination
by loading all rows into the adapter and slicing in memory is **not acceptable** — pagination must be
**pushed down to JDBC** (`LIMIT` / `OFFSET` plus `COUNT`).

SCIM advertises **`sort.supported = false`** (see **ServiceProviderConfig Capabilities**). Callers do **not** pass a sort parameter; the
core layer uses a **fixed, deterministic `ORDER BY`** so pages are stable within a sync pass.

#### New Core APIs

Add to **`AccessControlDispatcher`** / **`AccessControlManager`** / **`UserGroupManager`** (keep existing
`listUsers` / `listGroups` for backward compatibility):

| Method                                                                  | Description                                       |
|-------------------------------------------------------------------------|---------------------------------------------------|
| `PagedResult<User> listUsers(String metalake, int offset, int limit)`   | One page of users in the metalake                 |
| `long countUsers(String metalake)`                                      | Total non-deleted users (for SCIM `totalResults`) |
| `PagedResult<Group> listGroups(String metalake, int offset, int limit)` | One page of groups in the metalake                |
| `long countGroups(String metalake)`                                     | Total non-deleted groups                          |

**`PagedResult<T>`** (new type in `common` or `api`): `{ long totalCount; List<T> items; }` — or return
`totalCount` only from `count*` and items from `list*` (two SQL round-trips per SCIM page).

**Parameter mapping (SCIM adapter):**

| SCIM query param       | Core param                   | Notes                                              |
|------------------------|------------------------------|----------------------------------------------------|
| `startIndex` (1-based) | `offset = startIndex - 1`    | RFC 7644 default `startIndex` is `1`               |
| `count`                | `limit`                      | Cap at **100** in the adapter (see **Pagination**) |
| `totalResults`         | `countUsers` / `countGroups` | Must reflect full match set, not page size         |

**Filter list (no extra pagination API):** `filter=externalId eq "..."` uses **`getUserByExternalId`** /
**`getGroupByExternalId`** (0 or 1 row). Optional `filter=userName eq "..."` / `displayName eq "..."`
uses existing **`getUser`** / **`getGroup`**. The adapter wraps the result in a `ListResponse` with
`totalResults` `0` or `1`; no paged list call.

#### Fixed Sort Order (implementation requirement)

Pagination SQL **must** include a stable sort. Fixed order (not configurable by callers); sort by
primary key so pages are deterministic and index-friendly:

| Resource | `ORDER BY`     |
|----------|----------------|
| User     | `user_id ASC`  |
| Group    | `group_id ASC` |

For unfiltered metalake scope, `COUNT(*)` does not need `ORDER BY`.

### 6.3 User `enabled`

#### Schema

Add **`enabled`** to **`user_meta`** only (see **`enabled` on `user_meta`**). Existing rows default to **`enabled = 1`**
on upgrade. **`group_meta`** has no **`enabled`** column — RFC 7643 Group schema defines no `active` attribute;
Entra, Okta, and OneLogin deprovision groups via **`DELETE /Groups/{id}`**, not `PATCH active`.

#### New Core APIs

| Method                                           | Behavior                                                                                    |
|--------------------------------------------------|---------------------------------------------------------------------------------------------|
| `void disableUser(String metalake, String user)` | Set `user_meta.enabled = 0`; row must exist (`deleted_at = 0`) or **`NoSuchUserException`** |
| `void enableUser(String metalake, String user)`  | Set `user_meta.enabled = 1`; row must exist (`deleted_at = 0`) or **`NoSuchUserException`** |

Add matching methods on **`AccessControlDispatcher`**, implemented in **`UserGroupManager`** via
`UserMetaService` `UPDATE` on `enabled` only.

### 6.5 Group Membership (Users in Groups)

#### Schema

Table **`scim_user_group_rel`** (DDL in **scim_user_group_rel**): one row = one user belongs to one group within a metalake
(scope is implicit via `user_id` / `group_id` referencing `user_meta` / `group_meta` in that
metalake).

#### Core APIs

Add **`UserGroupRelService`**, exposed through **`AccessControlDispatcher`** / **`UserGroupManager`**:

| Method                                                                         | Behavior                                                                              |
|--------------------------------------------------------------------------------|---------------------------------------------------------------------------------------|
| `void addUsersToGroup(String metalake, String group, List<String> users)`      | Insert rows; idempotent when relation already active                                  |
| `void removeUsersFromGroup(String metalake, String group, List<String> users)` | Soft-delete matching rows                                                             |
| `void replaceUsersInGroup(String metalake, String group, List<String> users)`  | Replace the group's user membership set in one transaction (SCIM full `members` sync) |
| `String[] listUsernamesForGroup(String metalake, String group)`                | Active member usernames                                                               |
| `String[] listGroupNamesForUser(String metalake, String user)`                 | Active parent group names                                                             |

Physical purge uses **`RelationalGarbageCollector`** (same as `user_role_rel`).

### 6.6 OAuth login group membership (when SCIM is enabled)

SCIM and OAuth serve different purposes on different ports:

| Traffic               | Port | Credential           | Purpose                                     |
|-----------------------|------|----------------------|---------------------------------------------|
| IdP SCIM provisioning | 9201 | `gravitino_scim_*`   | Push users, groups, and **`members`**       |
| User login / REST API | 8090 | OAuth JWT (or other) | Prove **who** the caller is; authorize APIs |

When SCIM is **fully configured** — `gravitino.auxService.names` includes **`scim`** and
`gravitino.server.rest.extensionPackages` includes the SCIM token admin plugin — **group membership for
OAuth authorization is read only from the database** (`listGroupNamesForUser` → **`scim_user_group_rel`**),
not from JWT claims. When SCIM is **not** configured, behavior is unchanged (JWT `groupsFields` /
**`groupMapper`**).

#### Runtime wiring (extension module on **8090**)

`ScimTokenRESTFeature` validates configuration at startup and **exits** if the extension package is
enabled without the required companion settings:

| Key                                             | Requirement                                                                         |
|-------------------------------------------------|-------------------------------------------------------------------------------------|
| `gravitino.auxService.names`                    | Must include `scim`                                                                 |
| `gravitino.authenticator.oauth.principalMapper` | Must be `com.datastrato.gravitino.scim.basic.oauth.ScimOAuthPrincipalMapper`        |
| `gravitino.authenticator.oauth.groupsFields`    | Must be empty (JWT group claims must not override SCIM membership)                  |
| `gravitino.server.webserver.customFilters`      | Must include `com.datastrato.gravitino.scim.basic.oauth.ScimOAuthRequestPathFilter` |

Request flow for metalake-scoped main REST APIs:

```text
HTTP /api/metalakes/{metalake}/...
  → ScimOAuthRequestPathFilter (capture path, parse {metalake})
  → OAuth authentication (JWT proves identity)
  → ScimOAuthPrincipalMapper.map(principal)
       → regex/normalize username from JWT
       → listGroupNamesForUser(metalake, username) from scim_user_group_rel
       → UserPrincipal(username, groups)
  → JCasbin authorization (unchanged; reads groups from UserPrincipal)
```

Non-metalake paths (for example `/api/metalakes` or `/api/version`) do not trigger SCIM group lookup;
`ScimOAuthPrincipalMapper` returns a username-only `UserPrincipal`.

**OAuth independence:** `gravitino.authenticator.oauth.principalFields` / `principalMapper.regex.pattern`
still apply to JWT **identity** mapping. `groupsFields` / `groupMapper` are **not** used for **8090**
authorization when SCIM is enabled.

## 7. Data Model

This section defines **SCIM-related schema changes**: new tables `scim_token` and **`scim_user_group_rel`**,
plus **`external_id`** on `user_meta` and `group_meta` and **`enabled`** on **`user_meta`** only. Core APIs
are in **Gravitino Core Changes**; HTTP mapping is in **SCIM Protocol HTTP Interface**.

### 7.1 Schema Change Policy

| Table                 | Change type    | Notes                                                    |
|-----------------------|----------------|----------------------------------------------------------|
| `scim_token`          | **New table**  | Stores SCIM bearer token metadata                        |
| `scim_user_group_rel` | **New table**  | Which users belong to which group                        |
| `user_meta`           | **Add column** | `external_id` — stable SCIM `externalId` correlation key |
| `group_meta`          | **Add column** | `external_id` — stable SCIM `externalId` correlation key |
| `user_meta`           | **Add column** | `enabled` — maps User SCIM `active`; `false` disables    |

### 7.2 New Table: `scim_token`

Stores hashed SCIM bearer tokens. Each row is **scoped to one metalake** via `metalake_id` (same
isolation model as `user_meta` / `group_meta`).

```sql
CREATE TABLE IF NOT EXISTS `scim_token` (
    `id` BIGINT(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'auto increment id',
    `metalake_id` BIGINT(20) NOT NULL COMMENT 'metalake id',
    `token_name` VARCHAR(256) NOT NULL COMMENT 'scim token name',
    `token_hash` VARCHAR(64) NOT NULL COMMENT 'SHA-256 hex digest of scim token value',
    `expires_at` BIGINT(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'token expiry time in ms; 0 = never expires',
    `audit_info` MEDIUMTEXT NOT NULL COMMENT 'scim token audit info',
    `deleted_at` BIGINT(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'token deleted at',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_scim_token_metalake_name` (`metalake_id`, `token_name`, `deleted_at`),
    KEY `idx_scim_token_hash` (`token_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT 'scim token metadata';
```

Design notes:

- Token plaintext is returned **once** at creation time and never stored.
- `metalake_id` binds the token to a single metalake; **authorization** on **9201** rejects tokens
  used against a different `{metalake}` in the URL.
- `token_hash` stores the SHA-256 hex digest of the full bearer token.
- `audit_info` stores serialized `AuditInfo` (creator on create; last modifier updated on soft
  delete), consistent with other Gravitino metadata tables such as `user_meta` and `group_meta`.
- `expires_at = 0` means the token has **no fixed expiry**; otherwise it is an absolute epoch-millis
  deadline enforced at validation time (bearer validation).
- Soft deletion supports **immediate revoke**; physical purge follows the entity-store retention
  policy (soft delete and GC below).
- Multiple concurrent tokens may exist **per metalake**.
- `token_name` is unique per `(metalake_id, deleted_at)` — the same name may be reused in different
  metalakes.

### 7.3 `external_id` on `user_meta` and `group_meta`

SCIM **`externalId`** (for example Entra `objectId`, Okta `id`) is stored in **`external_id`**. It is
returned as the SCIM resource `{id}` and used for GET/PATCH/DELETE and filter correlation.

```sql
ALTER TABLE `user_meta`
    ADD COLUMN `external_id` VARCHAR(256) NULL;

ALTER TABLE `group_meta`
    ADD COLUMN `external_id` VARCHAR(256) NULL;

CREATE UNIQUE INDEX `uk_user_meta_metalake_external_del`
    ON `user_meta` (`metalake_id`, `external_id`, `deleted_at`);

CREATE UNIQUE INDEX `uk_group_meta_metalake_external_del`
    ON `group_meta` (`metalake_id`, `external_id`, `deleted_at`);
```

(PostgreSQL and H2 upgrade scripts use the equivalent `VARCHAR` type and unique indexes for the release.)

### 7.4 `enabled` on `user_meta`

Gravitino today has no **disabled** state for users — only **soft delete** via
`removeUser`, which also soft-deletes **`user_role_rel`**. Mapping SCIM **`PATCH active: false`** directly to
`removeUser` therefore **drops role bindings**; a later **`PATCH active: true`** only runs `addUser` and
**does not restore** prior grants.

To support IdP **deactivate / reactivate** for users (Entra and Okta commonly send `PATCH active:false`, not
`DELETE`), add a persisted **`enabled`** flag on **`user_meta`**. Core APIs and semantics are in **User `enabled`**.

Groups have no SCIM `active` attribute (RFC 7643 §4.2); **`group_meta`** does not get an **`enabled`** column.
Group deprovision is **`DELETE /Groups/{id}`** → `removeGroup`.

```sql
ALTER TABLE `user_meta`
    ADD COLUMN `enabled` TINYINT(1) NOT NULL DEFAULT 1;
```

(PostgreSQL and H2 upgrade scripts use the equivalent `BOOLEAN` / `TINYINT` type for the release.)

### 7.5 `scim_user_group_rel`

```sql
CREATE TABLE IF NOT EXISTS `scim_user_group_rel` (
    `id` BIGINT(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'auto increment id',
    `metalake_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'metalake id',
    `user_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'user id',
    `group_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'group id',
    `audit_info` MEDIUMTEXT NOT NULL COMMENT 'relation audit info',
    `current_version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'relation current version',
    `last_version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'relation last version',
    `deleted_at` BIGINT(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'relation deleted at',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sugr_mid_ui_gi_del` (`metalake_id`, `user_id`, `group_id`, `deleted_at`),
    KEY `idx_sugr_mid` (`metalake_id`),
    KEY `idx_sugr_uid` (`user_id`),
    KEY `idx_sugr_gid` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT 'scim user group relation';
```

(PostgreSQL and H2 enterprise schema scripts use equivalent types and unique indexes for the release.)

Design notes:

- **Uniqueness**: at most one active row per `(metalake_id, user_id, group_id)`; re-adding a member
  after soft delete inserts a new row with a new `deleted_at` tombstone on the old row (same pattern as
  `user_role_rel`).
- **`metalake_id` column**: scopes membership lookups per metalake without joining `user_meta` /
  `group_meta` on every OAuth group-resolution query.

### 7.6 Token Deletion and Garbage Collection

SCIM token deletion follows the same **two-phase** pattern as `user_meta` / `group_meta`: **soft
delete** (mark `deleted_at`), then **physical delete** after a retention window. Triggers for soft
delete are **manual DELETE** or **automatic expiry** (`expires_at`); physical purge is always the same GC
task.

#### Soft delete (admin API)

`DELETE /api/metalakes/{metalake}/scim/tokens/{tokenName}` sets `deleted_at` to the current epoch milliseconds on the
active row (`deleted_at = 0`) and updates `audit_info` with the deleting caller and time.
Bearer validation looks up only rows with `deleted_at = 0`, so
revocation takes effect on the **next** SCIM request — GC is **not** required for revoke latency.

The unique key `(metalake_id, token_name, deleted_at)` allows a new row with the same `token_name` and
`deleted_at = 0` immediately after soft delete, without waiting for physical purge.

#### Expiry (automatic soft delete)

Rows with a fixed lifetime (`expires_at > 0`) are handled as follows:

| Concern             | Behavior                                                                                                            |
|---------------------|---------------------------------------------------------------------------------------------------------------------|
| SCIM auth at expiry | **Immediate 419** (`TokenExpiredException`) when `now >= expires_at`; does **not** wait for background tasks        |
| Row state at expiry | `deleted_at` remains `0` until the expiry task runs (brief window)                                                  |
| Expiry task         | Scheduled background task sets `deleted_at = now` where `expires_at > 0 AND expires_at <= now() AND deleted_at = 0` |
| List API            | While `deleted_at = 0` but past `expiresAt`, `status = expired`; omitted after expiry task sets `deleted_at`        |
| Physical purge      | Same GC as manual delete; **not** direct hard delete on expiry                                                      |
| `expires_at = 0`    | No time-based expiry; only manual DELETE changes lifecycle                                                          |

Expiry uses the **same soft-delete + GC pipeline** as `DELETE /api/metalakes/{metalake}/scim/tokens/{tokenName}`. The
expiry task aligns database state with validation behavior; it does **not** gate when SCIM requests
start failing.

#### Physical purge (background GC)

After soft delete, rows remain in `scim_token` until physically removed. A **dedicated SCIM token
garbage collector** periodically deletes rows where:

```text
deleted_at > 0 AND deleted_at < (now - gravitino.entity.store.deleteAfterTimeMs)
```

This reuses the existing entity-store retention setting (`gravitino.entity.store.deleteAfterTimeMs`,
same as `RelationalGarbageCollector` for `user_meta` / `group_meta`). `scim_token` is **not** part of
the core `Entity.EntityType` model, so it is **not** swept by `RelationalGarbageCollector` directly;
the SCIM token GC task runs a small scheduled task with the same retention semantics.

Physical purge is for **storage hygiene and audit-window alignment**, not for making revoke effective.

---

## 8. SCIM Protocol HTTP Interface

### 8.1 Authentication and Base Information

IdP SCIM traffic uses **opaque bearer tokens** (`gravitino_scim_*`) only. Authentication and metalake
authorization are handled by `ScimBearerAuthFilter` on port **9201**; request-scoped metalake context
for SCIMple is attached by `ScimURLScopeResolver`. SCIM endpoints do not accept
Gravitino user OAuth JWTs or passwords.

| Item                  | Value                                                                                             |
|-----------------------|---------------------------------------------------------------------------------------------------|
| Base URL              | `https://{your-domain}:{scim-port}/scim/v2/metalakes/{metalake}` (default `{scim-port}` = `9201`) |
| Request content type  | `application/scim+json`                                                                           |
| Response content type | `application/scim+json`                                                                           |
| Accept                | `application/scim+json`                                                                           |
| Authentication        | `Authorization: Bearer gravitino_scim_*`                                                          |
| Versioning            | URL includes `/v2/`; capabilities at `.../metalakes/{metalake}/ServiceProviderConfig`             |

Specification reference: [RFC 7644 – SCIM Protocol](https://www.rfc-editor.org/rfc/rfc7644.html)

All SCIM protocol endpoints under `/scim/v2/metalakes/{metalake}/` use **`application/scim+json`**
per RFC 7644 for request and response bodies. IdPs must send `Content-Type: application/scim+json`
on write operations (for example `POST /Users`). Gravitino sets `Content-Type: application/scim+json`
on SCIM responses.

Gravitino exposes a **subset** of SCIM 2.0 through **SCIMple**. SCIMple serves the HTTP
resources; repository adapters implement persistence semantics below.

### 8.2 Endpoint Support Matrix

Base URL prefix: `/scim/v2/metalakes/{metalake}`.

The operator configures this prefix as the IdP **Tenant URL**. The IdP appends resource paths such
as `/Users` or `/ServiceProviderConfig` to form the full request URL.

Paths below are relative to that prefix. For example, `POST /Users` means
`POST /scim/v2/metalakes/{metalake}/Users`.

#### 8.2.1 Supported Endpoints

| RFC 7644 § | Method | Endpoint                 | Behavior (SCIMple + repository adapter)                                                                         |
|------------|--------|--------------------------|-----------------------------------------------------------------------------------------------------------------|
| §3.3       | POST   | `/Users`                 | `ScimUserRepositoryAdapter.create` → `createScimUser` by `externalId` → `user_meta` (idempotent)                |
| §3.4.1     | GET    | `/Users`                 | SCIMple filter/pagination → `ScimUserRepositoryAdapter.find` → `user_meta`                                      |
| §3.4.3     | POST   | `/Users/.search`         | Same as GET `/Users` (SCIMple)                                                                                  |
| §3.4.1     | GET    | `/Users/{id}`            | `ScimUserRepositoryAdapter.get` by `external_id` → `user_meta`                                                  |
| §3.5.2     | PATCH  | `/Users/{id}`            | **`active` only** → `enableUser` / `disableUser` (see **PATCH support**)                                        |
| §3.6       | DELETE | `/Users/{id}`            | `ScimUserRepositoryAdapter.delete` → `removeUser` (soft delete)                                                 |
| §3.3       | POST   | `/Groups`                | `ScimGroupRepositoryAdapter.create` → `createScimGroup` by `externalId`; sync `members` → `scim_user_group_rel` |
| §3.4.1     | GET    | `/Groups`                | SCIMple filter/pagination → `ScimGroupRepositoryAdapter.find` → `group_meta`                                    |
| §3.4.3     | POST   | `/Groups/.search`        | Same as GET `/Groups` (SCIMple)                                                                                 |
| §3.4.1     | GET    | `/Groups/{id}`           | `ScimGroupRepositoryAdapter.get` by `external_id`; `members` from `scim_user_group_rel`                         |
| §3.5.2     | PATCH  | `/Groups/{id}`           | **`members` only** → add/remove/replace in **`scim_user_group_rel`** (see **PATCH support**)                    |
| §3.6       | DELETE | `/Groups/{id}`           | `ScimGroupRepositoryAdapter.delete` → `removeGroup` (soft delete)                                               |
| §4         | GET    | `/ServiceProviderConfig` | `ServerConfiguration` — metalake-agnostic capabilities                                                          |
| §4         | GET    | `/ResourceTypes`         | built-in `ResourceTypesResourceImpl` — User and Group types                                                     |
| §4         | GET    | `/ResourceTypes/{type}`  | Single resource type                                                                                            |
| §4         | GET    | `/Schemas`               | built-in `SchemaResourceImpl` — minimal attributes per **Attribute Support** below                              |
| §4         | GET    | `/Schemas/{schema}`      | Single schema document by URN                                                                                   |

Discovery rows use the same URL prefix and validation as provisioning rows; discovery responses are metalake-agnostic.

#### 8.2.2 Unsupported Endpoints

These endpoints exist in SCIMple but are **not supported** by repository adapters. Gravitino
returns **405 Method Not Allowed** (SCIM error body per RFC 7644 §3.12 where applicable).

| RFC 7644 § | Method                            | Endpoint       | Status  | Reason                                                                               |
|------------|-----------------------------------|----------------|---------|--------------------------------------------------------------------------------------|
| §3.5.1     | PUT                               | `/Users/{id}`  | **405** | No full replace; use POST, PATCH (`active`), or DELETE                               |
| §3.5.1     | PUT                               | `/Groups/{id}` | **405** | No full replace; use POST, PATCH (`members`), or DELETE                              |
| §3.7       | POST                              | `/Bulk`        | **405** | Bulk provisioning is out of scope                                                    |
| §3.11      | GET / POST / PUT / PATCH / DELETE | `/Me`          | **405** | Gravitino SCIM uses integration bearer tokens, not end-user self-service aliases     |
| §3.4.3     | POST                              | `/.search`     | **405** | Only resource-level search under `/Users/.search` and `/Groups/.search` is supported |

#### 8.2.3 PATCH support

RFC 7643 defines **`active`** on the **User** resource only; the **Group** resource has **`displayName`**
and **`members`** — no `active`. Entra, Okta, and OneLogin deprovision users with **`PATCH active: false`**
and deprovision groups with **`DELETE /Groups/{id}`** (or membership-only PATCH).

**Users — `PATCH /Users/{id}` (`active` only)**

Microsoft Entra ID and Okta often **deprovision users** by sending **`PATCH`** with `active: false`, not
`DELETE`. Gravitino maps this to the **`enabled`** column and core APIs in **User `enabled`**
(`enableUser` / `disableUser`).

Repository adapters resolve `{id}` to **`external_id`** in the URL metalake, then call
`disableUser` / `enableUser` via `AccessControlDispatcher`:

| Resource | PATCH body (effective) | Gravitino action                        | HTTP result (typical)     |
|----------|------------------------|-----------------------------------------|---------------------------|
| User     | `active: false`        | `disableUser` — `user_meta.enabled = 0` | **200 OK** with SCIM User |
| User     | `active: true`         | `enableUser` — `user_meta.enabled = 1`  | **200 OK** with SCIM User |

`PATCH active` on Users requires an existing user (`deleted_at = 0`); otherwise return **404 Not Found**
(use **POST** to create).

**Groups — `PATCH /Groups/{id}` (`members` only)**

IdPs send **`PATCH`** to add, remove, or replace group members (Entra/Okta) or update `displayName` (Okta).
Gravitino supports **`members`** only (`addUsersToGroup`, `removeUsersFromGroup`, `replaceUsersInGroup` in
**Group Membership (Users in Groups)**). Requests that PATCH other Group attributes (including `active`) return
**400 Bad Request** or are ignored per adapter policy.

`DELETE /Users/{id}` and `DELETE /Groups/{id}` invoke **`removeUser`** / **`removeGroup`** (soft delete
+ clear role bindings). Use **DELETE** when the user or group should be removed from Gravitino metadata,
not merely disabled (Users only).

#### 8.2.4 ServiceProviderConfig Capabilities

Gravitino advertises the following via `ServerConfiguration` in
`/scim/v2/metalakes/{metalake}/ServiceProviderConfig`, per RFC 7644 §4:

| Capability                 | Supported | Notes                                                                                                 |
|----------------------------|-----------|-------------------------------------------------------------------------------------------------------|
| `patch.supported`          | `true`    | Users: **`active` only** on `/Users/{id}`; Groups: **`members` only** on `/Groups/{id}`               |
| `filter.supported`         | `true`    | **`eq` on `externalId`**, plus **`userName` / `displayName`**; logical **`and`** for compound filters |
| `sort.supported`           | `false`   | Fixed `ORDER BY` in core paginated list (see **List Pagination**); no caller-supplied sort            |
| `etag.supported`           | `false`   | No PUT; PATCH limited to User `active` and Group `members`                                            |
| `bulk.supported`           | `false`   | `/Bulk` is not implemented                                                                            |
| `changePassword.supported` | `false`   | Password provisioning is a non-goal                                                                   |

### 8.3 Attribute Support

RFC 7644 defines how create operations process attributes according to SCIM mutability (`readOnly`,
`readWrite`, `writeOnly`, `immutable`). Gravitino applies RFC 7644 processing rules on **POST**
create requests, but synchronizes only the subset needed for user and group metadata. **PUT** is not
implemented. **PATCH**: Users — **`active` only**; Groups — **`members` only** (see **PATCH support**).

Schema references:

- User: `urn:ietf:params:scim:schemas:core:2.0:User`
- Group: `urn:ietf:params:scim:schemas:core:2.0:Group`

#### 8.3.1 User Attributes

| Attribute                 | Gravitino                                                                                                                                                              |
|---------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `externalId`              | **Required on POST** — persisted to `user_meta.external_id`; stable correlation key; returned as SCIM `id` and `externalId` on read                                    |
| `userName`                | **Synchronized on POST create only** — optional **`gravitino.scim.userMapper`** (see **Name mapping** below) maps to `user_meta.user_name`; **immutable** after create |
| `active`                  | **PATCH only** — maps to `user_meta.enabled`; `false` → `enabled = 0`; `true` → `enabled = 1`                                                                          |
| All other User attributes | **Ignored** on create (including profile fields and client-supplied `id` / `meta`)                                                                                     |

#### 8.3.2 Group Attributes

| Attribute                  | Gravitino                                                                                                                                             |
|----------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------|
| `externalId`               | **Required on POST** — persisted to `group_meta.external_id`; stable correlation key; returned as SCIM `id` and `externalId` on read                  |
| `displayName`              | **Synchronized on POST create only** — optional **`gravitino.scim.groupMapper`** maps to `group_meta.group_name`; **immutable** after create          |
| `members`                  | **Synchronized on POST/PATCH** — `value` = member user's `user_meta.external_id`; stored in **`scim_user_group_rel`**; unknown user → skip (WARN log) |
| All other Group attributes | **Ignored** on create (including `active` — not in RFC 7643 Group schema; client-supplied `id` / `meta`)                                              |

#### 8.3.3 Name mapping (`userMapper` / `groupMapper`)

SCIM push uses SCIM attributes (`userName`, `displayName`). User login uses OAuth/JWT claims via
`gravitino.authenticator.oauth.principalFields` and **`principalMapper`** for **identity** only when
SCIM is enabled; **group names for authorization** come from **`scim_user_group_rel`** (see **OAuth login group membership**), not from `groupsFields` / **`groupMapper`**. Gravitino
therefore exposes **SCIM-specific** optional mappers under `gravitino.scim.*` in **`conf/gravitino.conf`**
(not OAuth `authenticator.oauth.*` keys, and not under `scim-server/conf`). `ScimConfig` loads them from
the `AuxiliaryServiceManager` `serviceInit` map at auxiliary startup (Phase 5); repository adapters
build **`PrincipalMapper`** / **`GroupMapper`** via existing **`PrincipalMapperFactory`** /
**`GroupMapperFactory`** from `ScimConfig` (Phase 6).

Repository adapters apply mappers **before** create and **before** name-based filter lookups.
IdP `GET ...?filter=externalId eq "..."` correlates by `external_id`; `filter=userName eq "..."` uses
the mapped name.

| Config key                                 | SCIM input     | Stored field            | Mapper default | Pattern default |
|--------------------------------------------|----------------|-------------------------|----------------|-----------------|
| `gravitino.scim.userMapper`                | `userName`     | `user_meta.user_name`   | `regex`        | `^(.*)$`        |
| `gravitino.scim.userMapper.regex.pattern`  | (with `regex`) | (first capture group)   | —              | `^(.*)$`        |
| `gravitino.scim.groupMapper`               | `displayName`  | `group_meta.group_name` | `regex`        | `^(.*)$`        |
| `gravitino.scim.groupMapper.regex.pattern` | (with `regex`) | (first capture group)   | —              | `^(.*)$`        |

**Mapper types** (same pattern as OAuth `principalMapper` / `groupMapper`):

| Value                      | Behavior                                                                                                                                                                 |
|----------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `regex`                    | Built-in regex mapper; first capturing group becomes the stored name. Configure `gravitino.scim.userMapper.regex.pattern` or `gravitino.scim.groupMapper.regex.pattern`. |
| Fully qualified class name | Custom **`PrincipalMapper`** / **`GroupMapper`** implementation (same SPI as OAuth).                                                                                     |

**When to configure:** only when the IdP sends names that must be normalized before persistence
(for example `alice@corp.com` → `alice`). If the IdP attribute mapping already produces the desired
Gravitino name, leave defaults (passthrough). Stored names should align with the identity used at
login time for authorization (see **Group Membership (Users in Groups)** and **OAuth login group membership**).

**OAuth independence:** `gravitino.authenticator.oauth.principalFields` / `groupsFields` are not
used on the SCIM protocol path (**9201**). When SCIM is enabled, **`groupsFields`** / **`groupMapper`**
are also **not** used for **8090** authorization — `gravitino.authenticator.oauth.principalMapper`
must be **`ScimOAuthPrincipalMapper`**, `groupsFields` must be empty, and
`gravitino.server.webserver.customFilters` must include **`ScimOAuthRequestPathFilter`** (see §6.6 and
§10.3). Operators may set SCIM `userMapper` and OAuth `principalMapper.regex.pattern` to the same value
when both inputs need the same normalization for **usernames** (and SCIM group **displayName** at provision
time), but the configuration keys are separate.

### 8.4 Filter Support

SCIMple parses SCIM filter expressions in `Repository.find()`; repository adapters apply
the parsed filter when listing users or groups. **`externalId`** filters map to **`external_id`**.
**`userName` / `displayName`** filter values are passed through **`gravitino.scim.userMapper` /
`groupMapper`** before name-based lookup. Gravitino advertises only operators that IdPs actually use
and that map cleanly to exact-match lookups — not full range semantics on string attributes.

| Resource | Supported attributes                  | Backing field                                     |
|----------|---------------------------------------|---------------------------------------------------|
| User     | `externalId` (primary), `userName`    | `user_meta.external_id`, `user_meta.user_name`    |
| Group    | `externalId` (primary), `displayName` | `group_meta.external_id`, `group_meta.group_name` |

Supported operators:

| Operator | Description                                                                                             |
|----------|---------------------------------------------------------------------------------------------------------|
| `eq`     | Equal — primary operator for IdP re-correlation on **`externalId`**; also on `userName` / `displayName` |
| `and`    | Logical AND — combine multiple `eq` predicates                                                          |

**Not supported** (repository adapters reject or return an empty list; do not advertise in
`ServiceProviderConfig`): `ne`, `gt`, `ge`, `lt`, `le`, `co`, `sw`, `pr`, and other comparison or
substring operators. Enterprise IdPs typically use exact-match filters on these attributes;
advertising unsupported operators risks failing IdP capability probes.

Example:

```text
GET /scim/v2/metalakes/my_metalake/Users?filter=externalId eq "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
GET /scim/v2/metalakes/my_metalake/Groups?filter=externalId eq "group-object-id-from-idp"
```

### 8.5 Pagination

List responses support SCIM pagination parameters:

- `startIndex`
- `count`

The maximum supported page size is **100**, consistent with common enterprise SCIM implementations.
Repository adapters map these to core **paginated list** APIs (see **List Pagination**); do not load full metalake
lists into memory.

Example:

```text
GET /scim/v2/metalakes/my_metalake/Users?startIndex=1&count=100
```

### 8.6 Example SCIM User Create

```shell
curl -X POST "https://gravitino.example.com:9201/scim/v2/metalakes/my_metalake/Users" \
  -H "Authorization: Bearer gravitino_scim_<REDACTED>" \
  -H "Content-Type: application/scim+json" \
  -H "Accept: application/scim+json" \
  -d '{
    "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
    "externalId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "userName": "alice"
  }'
```

### 8.7 Example SCIM Group Create

```shell
curl -X POST "https://gravitino.example.com:9201/scim/v2/metalakes/my_metalake/Groups" \
  -H "Authorization: Bearer gravitino_scim_<REDACTED>" \
  -H "Content-Type: application/scim+json" \
  -H "Accept: application/scim+json" \
  -d '{
    "schemas": ["urn:ietf:params:scim:schemas:core:2.0:Group"],
    "externalId": "group-object-id-from-idp",
    "displayName": "engineering"
  }'
```

Group POST/PATCH can include a **`members`** array; Gravitino syncs it to **`scim_user_group_rel`**. Group
GET returns the stored user list as SCIM `members`.

---

## 9. SCIM Token Management APIs

SCIM token management APIs are served on the main Gravitino REST port (default **8090**) by the
token admin plugin, registered through `gravitino.server.rest.extensionPackages`. They use
Gravitino user authentication and **metalake owner** authorization on `{metalake}`; they are not authenticated with SCIM opaque
tokens (`gravitino_scim_*`). Metalake scope comes from the `{metalake}` path parameter (same JAX-RS
pattern as `/api/metalakes/{metalake}/users`); these APIs do **not** run on port **9201** and do
**not** use `ScimURLScopeResolver`.

| Traffic                   | Port | Path prefix                             | Auth mechanism                                                |
|---------------------------|------|-----------------------------------------|---------------------------------------------------------------|
| IdP SCIM provisioning     | 9201 | `/scim/v2/metalakes/{metalake}/...`     | `ScimBearerAuthFilter` (opaque `gravitino_scim_*`)            |
| Operator token management | 8090 | `/api/metalakes/{metalake}/scim/tokens` | Main-server user auth + **`METALAKE::OWNER`** on `{metalake}` |

Token management APIs reuse `gravitino.authenticators` from `gravitino.conf` (same credential types as
other main REST APIs). Unauthenticated callers receive **401**; authenticated users who are not the
**metalake owner** of `{metalake}` receive **403**.

| Operation         | Method   | Path                                                       |
|-------------------|----------|------------------------------------------------------------|
| Create SCIM token | `POST`   | `/api/metalakes/{metalake}/scim/tokens`                    |
| List SCIM tokens  | `GET`    | `/api/metalakes/{metalake}/scim/tokens`                    |
| Rotate SCIM token | `POST`   | `/api/metalakes/{metalake}/scim/tokens/{tokenName}/rotate` |
| Delete SCIM token | `DELETE` | `/api/metalakes/{metalake}/scim/tokens/{tokenName}`        |

All responses use the Gravitino REST envelope (`Content-Type: application/vnd.gravitino.v1+json`):

| Field     | Type            | Description                                                           |
|-----------|-----------------|-----------------------------------------------------------------------|
| `code`    | Integer         | `0` on success; non-zero on error (same error model as main REST API) |
| `message` | String          | Present on error responses                                            |
| Payload   | Object or array | Operation-specific field below (`token`, `tokens`, or `deleted`)      |

### 9.1 Create SCIM Token

`POST /api/metalakes/{metalake}/scim/tokens`

Creates a new metalake-scoped SCIM token and returns the generated token value once. Creation steps
and hashing rules are defined in **Storage Model** and **Token Creation Flow** above.

Path parameter:

| Field      | Type   | Description                       |
|------------|--------|-----------------------------------|
| `metalake` | String | Target metalake name (must exist) |

Request body:

| Field           | Type    | Required | Description                                                                            |
|-----------------|---------|----------|----------------------------------------------------------------------------------------|
| `tokenName`     | String  | Yes      | SCIM token name                                                                        |
| `expiresInDays` | Integer | No       | Fixed lifetime in days from creation; omit or `null` for no expiry (`expires_at = 0`). |

Error handling:

| Condition                                    | Result                                        |
|----------------------------------------------|-----------------------------------------------|
| Missing or invalid user auth                 | **401 Unauthorized**                          |
| Caller is not metalake owner of `{metalake}` | **403 Forbidden**                             |
| Metalake not found                           | **404 Not Found**                             |
| Missing or empty `tokenName`                 | **400 Bad Request**                           |
| Duplicate `tokenName`                        | **409 Conflict** (within the same metalake)   |
| CSPRNG / persistence failure                 | **500 Internal Server Error**; no partial row |

Response body (inside the `token` object):

| Field         | Type   | Description                                                                                 |
|---------------|--------|---------------------------------------------------------------------------------------------|
| `metalake`    | String | Metalake name (echo of path `{metalake}`)                                                   |
| `tokenName`   | String | SCIM token name                                                                             |
| `tokenValue`  | String | Plaintext bearer token; returned only once                                                  |
| `expiresAt`   | Long   | Expiry time in epoch milliseconds; **`0` means never expires**                              |
| `scimBaseUrl` | String | IdP SCIM base URL for this metalake, e.g. `https://host:9201/scim/v2/metalakes/my_metalake` |

Example response:

```json
{
  "code": 0,
  "token": {
    "metalake": "my_metalake",
    "tokenName": "prod",
    "tokenValue": "gravitino_scim_<REDACTED>",
    "expiresAt": 1780000000000,
    "scimBaseUrl": "https://host:9201/scim/v2/metalakes/my_metalake"
  }
}
```

Example:

```shell
curl -X POST -H "Accept: application/vnd.gravitino.v1+json" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <main-server-access-token>" \
  -d '{
    "tokenName": "prod",
    "expiresInDays": 180
  }' http://localhost:8090/api/metalakes/my_metalake/scim/tokens
```

Use the same `Authorization` header style as the main Gravitino REST API (OAuth bearer, Basic, or
other enabled authenticator). The example omits Basic credentials for brevity.

### 9.2 List SCIM Tokens

`GET /api/metalakes/{metalake}/scim/tokens`

Lists SCIM tokens for the given metalake where `deleted_at = 0`. Plaintext token values are never
returned. Rows past `expiresAt` but not yet processed by the expiry task appear with
`status = expired`; after the expiry task sets `deleted_at`, they are omitted after the expiry task soft-deletes them.

Error handling:

| Condition                                    | Result               |
|----------------------------------------------|----------------------|
| Missing or invalid user auth                 | **401 Unauthorized** |
| Caller is not metalake owner of `{metalake}` | **403 Forbidden**    |
| Metalake not found                           | **404 Not Found**    |

List has no additional business error codes; an empty `tokens` array is a successful response.

Example list item (elements of the `tokens` array):

| Field       | Example                | Description                                                                                         |
|-------------|------------------------|-----------------------------------------------------------------------------------------------------|
| `tokenName` | `prod`                 | Token name                                                                                          |
| `expiresAt` | `1780000000000` or `0` | Epoch millis; `0` means no fixed expiry                                                             |
| `status`    | `valid`                | `valid` if `expiresAt = 0` or `now < expiresAt`; `expired` if past `expiresAt` and `deleted_at = 0` |

Example response:

```json
{
  "code": 0,
  "tokens": [
    {
      "tokenName": "prod",
      "expiresAt": 1780000000000,
      "status": "valid"
    }
  ]
}
```

Example:

```shell
curl -X GET -H "Accept: application/vnd.gravitino.v1+json" \
  -H "Authorization: Bearer <main-server-access-token>" \
  http://localhost:8090/api/metalakes/my_metalake/scim/tokens
```

Use the same `Authorization` header style as **Create SCIM Token** (main-server user auth, not `gravitino_scim_*`).

### 9.3 Rotate SCIM Token

`POST /api/metalakes/{metalake}/scim/tokens/{tokenName}/rotate`

Replaces the bearer secret for an existing named token. The `tokenName` is unchanged; a new
`tokenValue` is returned once. The previous bearer for that row fails authentication on the **next**
SCIM request (atomic `token_hash` update). Steps and hashing rules are in **Token Rotation Flow** and
**Storage Model**.

Path parameters:

| Field       | Type   | Description                               |
|-------------|--------|-------------------------------------------|
| `metalake`  | String | Target metalake name (must exist)         |
| `tokenName` | String | Existing SCIM token name (must be active) |

Request body (optional JSON object; empty body is valid):

| Field           | Type    | Required | Description                                                                                      |
|-----------------|---------|----------|--------------------------------------------------------------------------------------------------|
| `expiresInDays` | Integer | No       | Reset fixed lifetime in days from rotation time; omit to keep the current `expires_at` unchanged |

Error handling:

| Condition                                    | Result                                       |
|----------------------------------------------|----------------------------------------------|
| Missing or invalid user auth                 | **401 Unauthorized**                         |
| Caller is not metalake owner of `{metalake}` | **403 Forbidden**                            |
| Metalake not found                           | **404 Not Found**                            |
| `tokenName` not found or soft-deleted        | **404 Not Found**                            |
| Invalid `expiresInDays`                      | **400 Bad Request**                          |
| CSPRNG / persistence failure                 | **500 Internal Server Error**; row unchanged |

Response body (inside the `token` object; same shape as create):

| Field         | Type   | Description                                                                                 |
|---------------|--------|---------------------------------------------------------------------------------------------|
| `metalake`    | String | Metalake name (echo of path `{metalake}`)                                                   |
| `tokenName`   | String | SCIM token name (unchanged)                                                                 |
| `tokenValue`  | String | New plaintext bearer token; returned only once                                              |
| `expiresAt`   | Long   | Expiry time in epoch milliseconds; **`0` means never expires**                              |
| `scimBaseUrl` | String | IdP SCIM base URL for this metalake, e.g. `https://host:9201/scim/v2/metalakes/my_metalake` |

Example response:

```json
{
  "code": 0,
  "token": {
    "metalake": "my_metalake",
    "tokenName": "prod",
    "tokenValue": "gravitino_scim_<REDACTED>",
    "expiresAt": 1780000000000,
    "scimBaseUrl": "https://host:9201/scim/v2/metalakes/my_metalake"
  }
}
```

Example:

```shell
curl -X POST -H "Accept: application/vnd.gravitino.v1+json" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <main-server-access-token>" \
  -d '{"expiresInDays": 180}' \
  http://localhost:8090/api/metalakes/my_metalake/scim/tokens/prod/rotate
```

Use the same `Authorization` header style as **Create SCIM Token** (main-server user auth, not `gravitino_scim_*`).

### 9.4 Delete SCIM Token

`DELETE /api/metalakes/{metalake}/scim/tokens/{tokenName}`

Soft-deletes the named SCIM token for the given metalake (sets `deleted_at`). Existing IdP
configurations using the deleted token fail authentication on the **next** SCIM request against
**that metalake's** SCIM base URL. Revocation does **not** wait for background GC.

| Aspect              | Behavior                                                                                 |
|---------------------|------------------------------------------------------------------------------------------|
| Revoke latency      | Immediate after soft delete (bearer validation looks up only rows with `deleted_at = 0`) |
| List API            | Soft-deleted rows omitted from `GET /api/metalakes/{metalake}/scim/tokens`               |
| Re-create same name | Allowed immediately after soft delete (new row with `deleted_at = 0`)                    |
| Physical removal    | Background SCIM token GC after `gravitino.entity.store.deleteAfterTimeMs`                |

Error handling:

| Condition                                    | Result               |
|----------------------------------------------|----------------------|
| Missing or invalid user auth                 | **401 Unauthorized** |
| Caller is not metalake owner of `{metalake}` | **403 Forbidden**    |
| Metalake not found                           | **404 Not Found**    |
| `tokenName` not found or soft-deleted        | **404 Not Found**    |

Example response:

```json
{
  "code": 0,
  "deleted": true
}
```

Example:

```shell
curl -X DELETE -H "Accept: application/vnd.gravitino.v1+json" \
  -H "Authorization: Bearer <main-server-access-token>" \
  http://localhost:8090/api/metalakes/my_metalake/scim/tokens/prod
```

Use the same `Authorization` header style as **Create SCIM Token** (main-server user auth, not `gravitino_scim_*`).

---

## 10. Configuration

SCIM runs as a **`GravitinoAuxiliaryService`** in the same JVM as the Gravitino server. Token
administration is a **required** plugin on the main REST stack (`ScimTokenRESTFeature` via
`gravitino.server.rest.extensionPackages`). Operators must configure the **full SCIM bundle** in
**`conf/gravitino.conf`** — extension package, auxiliary service, OAuth SCIM mapper/filter settings
(§10.1 and §10.3); startup fails if the extension package is enabled without them. Start the server
with `./bin/gravitino.sh start`.

### 10.1 Shared settings (`gravitino.conf`)

| Key                                       | Description                                                                                                                                    | Default | Required |
|-------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------|---------|----------|
| `gravitino.server.rest.extensionPackages` | Include `com.datastrato.gravitino.scim.web.rest.feature` to register `/api/metalakes/{metalake}/scim/tokens` and SCIM OAuth wiring on **8090** | (none)  | Yes      |
| `gravitino.auxService.names`              | Must include `scim` when the SCIM extension package is enabled (validated at startup)                                                          | (none)  | Yes      |

Registering the SCIM token admin extension **requires** `scim` in `gravitino.auxService.names` and the
OAuth keys in §10.3, even if you are only using token admin on **8090** today. IdP SCIM provisioning on
**9201** additionally requires §10.2 (`gravitino.scim.classpath` and listener keys).

Token management authorization uses the existing **`METALAKE::OWNER`** check on the `{metalake}` path
parameter (no SCIM-specific admin list). The metalake must have an owner assigned; callers who are
not the owner (direct user or member of an owner group) receive **403**.

`gravitino.authenticators` is typically already set in `gravitino.conf` for the main server; token
management APIs reuse it. When SCIM is enabled, OAuth must use the SCIM principal mapper and filter
settings in §10.3.

### 10.2 SCIM auxiliary service keys (`gravitino.conf`)

SCIM auxiliary service settings use the **`gravitino.scim.*`** prefix (same auxiliary-service convention as
`gravitino.iceberg-rest.*` and other `gravitino.{shortName}.*` keys). Upstream Gravitino keys
such as `gravitino.auxService.names` and `gravitino.server.rest.extensionPackages` are unchanged.

Edit `conf/gravitino.conf` and keep existing Gravitino server, entity-store, web-server, and
**authenticator** settings unchanged. Add SCIM auxiliary keys below.

`ScimRESTService` implements `GravitinoAuxiliaryService` (`shortName`: `scim`). `AuxiliaryServiceManager`
loads **`scim-server/libs`** (SCIMple + Jersey 3) via `gravitino.scim.classpath` and passes all
`gravitino.scim.*` entries from **`conf/gravitino.conf`** into `ScimRESTService.serviceInit()` (stripped
to short keys such as `httpPort` and `userMapper`). Do **not** place application properties under `scim-server/conf`.

| Key                                         | Description                                                                                                                                                                | Default   | Required |
|---------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------|----------|
| `gravitino.auxService.names`                | Include `scim` (comma-separated with other auxiliary services if needed)                                                                                                   | (none)    | Yes      |
| `gravitino.scim.classpath`                  | Directory with SCIM jars, e.g. `scim-server/libs` (libs only; not a `.conf` directory)                                                                                     | (none)    | Yes      |
| `gravitino.scim.host`                       | SCIM HTTP listener host                                                                                                                                                    | `0.0.0.0` | No       |
| `gravitino.scim.httpPort`                   | SCIM HTTP listener port                                                                                                                                                    | `9201`    | No       |
| `gravitino.scim.userMapper`                 | Map SCIM `userName` → `user_meta.user_name` before create/filter. Built-in: `regex`; or FQCN implementing **`PrincipalMapper`**.                                           | `regex`   | No       |
| `gravitino.scim.userMapper.regex.pattern`   | Regex pattern when `userMapper=regex`; first capture group is stored.                                                                                                      | `^(.*)$`  | No       |
| `gravitino.scim.groupMapper`                | Map SCIM `displayName` → `group_meta.group_name` before create/filter. Built-in: `regex`; or FQCN implementing **`GroupMapper`**.                                          | `regex`   | No       |
| `gravitino.scim.groupMapper.regex.pattern`  | Regex pattern when `groupMapper=regex`; first capture group is stored.                                                                                                     | `^(.*)$`  | No       |
| `gravitino.scim.errorHistory.retentionDays` | Days to retain failed IdP-facing Users/Groups protocol calls in `scim_error_history`. Must be a **positive integer**. A dedicated cleaner deletes older rows once per day. | `30`      | No       |

Example:

```properties
# Existing Gravitino server + entity store + authenticator settings omitted

gravitino.server.rest.extensionPackages=com.datastrato.gravitino.scim.web.rest.feature
gravitino.auxService.names=scim
gravitino.scim.classpath=scim-server/libs

gravitino.authenticator.oauth.principalMapper=com.datastrato.gravitino.scim.basic.oauth.ScimOAuthPrincipalMapper
gravitino.authenticator.oauth.groupsFields=
gravitino.server.webserver.customFilters=com.datastrato.gravitino.scim.basic.oauth.ScimOAuthRequestPathFilter

# Optional: normalize IdP SCIM names before persistence (Name mapping)
# Omit both blocks for passthrough (default ^(.*)$).
gravitino.scim.userMapper=regex
gravitino.scim.userMapper.regex.pattern=([^@]+)@.*

gravitino.scim.groupMapper=regex
gravitino.scim.groupMapper.regex.pattern=^/(.*)
```

IdP base URL example: `https://{host}:9201/scim/v2/metalakes/{metalake}`.

### 10.3 OAuth SCIM settings (`gravitino.conf`)

When the SCIM token admin extension package is enabled, `ScimTokenRESTFeature` validates these keys at
startup (see **OAuth login group membership**):

| Key                                                           | Description                                                                                                                                    | Default  | Required |
|---------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------|----------|----------|
| `gravitino.authenticator.oauth.principalMapper`               | Must be `com.datastrato.gravitino.scim.basic.oauth.ScimOAuthPrincipalMapper` — loads groups from `scim_user_group_rel` per metalake            | (none)   | Yes      |
| `gravitino.authenticator.oauth.groupsFields`                  | Must be **empty** — JWT group claims must not override SCIM membership                                                                         | `groups` | Yes¹     |
| `gravitino.server.webserver.customFilters`                    | Must include `com.datastrato.gravitino.scim.basic.oauth.ScimOAuthRequestPathFilter` — parses `{metalake}` from `/api/metalakes/{metalake}/...` | (none)   | Yes      |
| `gravitino.authenticator.oauth.principalMapper.regex.pattern` | Regex applied to JWT identity before username lookup (optional; align with `userMapper` when needed)                                           | `^(.*)$` | No       |

¹ Set to an empty value in `gravitino.conf` (for example `gravitino.authenticator.oauth.groupsFields=`).

### 10.4 Configuration key namespaces

| Namespace                               | Examples                                                                                                                                                                                                                       | Role                                                                                                                                                |
|-----------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|
| **SCIM auxiliary** (`gravitino.scim.*`) | `classpath`, `httpPort`, `userMapper`, `groupMapper`, `errorHistory.retentionDays`                                                                                                                                             | SCIM auxiliary listener, name mapping, and protocol error-history retention — same convention as other `gravitino.{shortName}.*` auxiliary services |
| **Upstream Gravitino**                  | `gravitino.auxService.names`, `gravitino.server.rest.extensionPackages`, `gravitino.authenticators`, `gravitino.authenticator.oauth.*`, `gravitino.server.webserver.customFilters`, `gravitino.entity.store.deleteAfterTimeMs` | Shared server mechanisms; OAuth and entity-store keys are not under `gravitino.scim.*`                                                              |
| **OAuth SCIM (8090)**                   | `principalMapper`, `groupsFields`, `customFilters`                                                                                                                                                                             | Required when SCIM extension package is enabled — see §10.3                                                                                         |
| **Extension package value**             | `com.datastrato.gravitino.scim.web.rest.feature`                                                                                                                                                                               | Jersey `Feature` package scanned on **8090** (same pattern as `org.apache.gravitino.idp.web.rest.feature`)                                          |

`gravitino.scim.*` keys are collected by `AuxiliaryServiceManager` for `shortName=scim` and forwarded to
`ScimRESTService.serviceInit()` → `ScimConfig`.

Token GC retention intentionally reuses **`gravitino.entity.store.deleteAfterTimeMs`** (no separate
`gravitino.scim.*` GC key in v1). Token expiry enforcement is per-row `expires_at` in
`scim_token`, not a global config default.

SCIM protocol error history retention uses **`gravitino.scim.errorHistory.retentionDays`** (default
**30**; must be a positive integer). A dedicated cleaner deletes `scim_error_history` rows older
than that window once per day. HTTP **404** Users/Groups failures are not recorded.

---

## 11. Work Plan and Checklist

### 11.1 Suggested Work Plan

| Phase | Work Item              | Module / Files                                                                      | Notes                                                                              |
|-------|------------------------|-------------------------------------------------------------------------------------|------------------------------------------------------------------------------------|
| 0     | New schema             | JDBC upgrades                                                                       | `scim_token`, `scim_user_group_rel`; `external_id`; `enabled` on `user_meta` only. |
| 1     | Core prerequisites     | Dispatcher, meta services, `UserGroupRelService`                                    | Paginated list; `external_id`; user `enabled`; membership APIs (§6).               |
| 2     | OAuth group resolution | `ScimOAuthPrincipalMapper`, `ScimOAuthRequestPathFilter`, `ScimUserGroupRelManager` | SCIM on → groups from DB only (§6.6); extension startup validation.                |
| 3     | Token storage + auth   | `ScimTokenService`, `scim_token` store                                              | Opaque token, SHA-256, metalake bearer auth (§4.6); unit tests, no HTTP.           |
| 4     | Token admin service    | `ScimTokenService`, GC task                                                         | create/list/rotate/delete; `METALAKE::OWNER`; expiry + GC; no HTTP.                |
| 5     | HTTP + SCIMple         | `ScimRESTService`, `ScimConfig`, `ScimTokenRESTFeature`                             | Aux **9201** + SCIMple; token API **8090**; filters, app mount; smoke tests.       |
| 6     | Repository adapters    | `ScimUserRepositoryAdapter`, `ScimGroupRepositoryAdapter`                           | User: `externalId`, PATCH `active`; Group: `members`; DELETE; pagination.          |
| 7     | User documentation     | docs, OpenAPI                                                                       | IdP setup and SCIM endpoint reference.                                             |

### 11.2 Review Checklist

| Area                 | Checklist                                                                                                   |
|----------------------|-------------------------------------------------------------------------------------------------------------|
| Schema policy        | `scim_token`, `scim_user_group_rel`; `external_id`; `enabled` on `user_meta` only.                          |
| Group membership     | `members` ↔ `scim_user_group_rel`; `disableUser` keeps rows; `remove*` clears bindings.                     |
| OAuth login groups   | SCIM on: `ScimOAuthPrincipalMapper` + `scim_user_group_rel`; `groupsFields` empty; off: JWT `groupsFields`. |
| External ID          | DDL + attribute mapping (§8.3).                                                                             |
| Core list pagination | JDBC `LIMIT`/`OFFSET`; fixed sort; adapter `startIndex`/`count`.                                            |
| Deactivate / roles   | User PATCH `active` → `enabled`; DELETE → soft delete + clear bindings.                                     |
| Deployment           | `auxService.names` + `extensionPackages`; SCIM not on main 8090 JAX-RS.                                     |
| Auxiliary config     | `gravitino.scim.*` in `gravitino.conf`; classpath = `scim-server/libs` only.                                |
| Metalake isolation   | URL `{metalake}` + `scim_token.metalake_id` on **9201**; admin APIs on **8090**.                            |
| Request pipeline     | **9201**: bearer filter → scope resolver; **8090**: token plugin + metalake owner.                          |
| Token admin auth     | Phase 4 service checks; Phase 5 HTTP; SCIM tokens do not authorize admin APIs.                              |
| Token security       | No plaintext; SHA-256; **419**/**401**; rotate; soft delete + GC.                                           |
| SCIM compliance      | §8 endpoints/attrs; filter `eq`/`and`; User PATCH `active`; Group PATCH `members`.                          |
| Name mapping         | Optional `gravitino.scim.*Mapper`; align OAuth `principalMapper` with `userMapper`.                         |

---

## 12. References

1. [SCIM.cloud](https://scim.cloud/)
2. [Microsoft Entra ID – Automate user provisioning with SCIM](https://learn.microsoft.com/en-us/entra/identity/app-provisioning/use-scim-to-provision-users-and-groups)
3. [RFC 7643 – SCIM Core Schema](https://www.rfc-editor.org/rfc/rfc7643.html)
4. [RFC 7644 – SCIM Protocol](https://www.rfc-editor.org/rfc/rfc7644.html)
5. [Databricks – Sync users and groups using SCIM](https://docs.databricks.com/aws/en/admin/users-groups/scim/)
6. [Databricks – Configure SCIM provisioning using Microsoft Entra ID](https://docs.databricks.com/aws/en/admin/users-groups/scim/aad)
7. [Snowflake – Authenticating SCIM API requests (legacy token, PAT, External OAuth)](https://docs.snowflake.com/en/user-guide/scim-authentication)
8. [Snowflake – Using programmatic access tokens for authentication](https://docs.snowflake.com/en/user-guide/programmatic-access-tokens)
9. [Apache SCIMple – SCIM 2.0 Java server library (Apache-2.0)](https://github.com/apache/directory-scimple) — Maven: `org.apache.directory.scimple:scim-server:1.0.0-M1`
10. [RFC 6750 – OAuth 2.0 Bearer Token Usage](https://www.rfc-editor.org/rfc/rfc6750.html)
11. [GitHub – Behind GitHub's new authentication token formats](https://github.blog/engineering/platform-security/behind-githubs-new-authentication-token-formats/)
12. [GitHub – GitHub credential types reference](https://docs.github.com/en/organizations/managing-programmatic-access-to-your-organization/github-credential-types)
13. [Stripe – API keys](https://docs.stripe.com/keys)
14. [npm – Announcing npm's new access token format](https://github.blog/security/announcing-npms-new-access-token-format/)


