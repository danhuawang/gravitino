<!--
Copyright 2026 Datastrato Pvt Ltd.
-->

---
title: Iceberg table encryption policy
slug: /iceberg-encryption-policy
---

## Overview

The built-in `system_iceberg_encryption` policy describes the encryption requirements for Iceberg
tables in scope. Scope follows Gravitino's normal policy association and inheritance
(catalog → schema → table), the same pattern as `system_iceberg_compaction`. The policy is
Iceberg-specific; it does not define a general encryption-policy language for other catalog
providers.

The standard policy fields own identity and lifecycle state. The typed `content` owns the required
encryption behavior. Associate the policy to a catalog, schema, or table after creation.

```json
{
  "name": "customer-data-encryption",
  "policyType": "system_iceberg_encryption",
  "comment": "Require an approved key for associated Iceberg tables",
  "enabled": true,
  "content": {
    "schemaVersion": 1,
    "required": true,
    "allowedKeys": [
      {
        "provider": "openbao-production",
        "keyId": "customer-pii-v1"
      }
    ],
    "enforcement": "deny-create"
  }
}
```

## Content fields

| Field           | Required    | Description |
|-----------------|-------------|-------------|
| `schemaVersion` | Yes         | Content schema version. Version 1 is the only supported value. |
| `required`      | No          | Whether an in-scope table must specify encryption. Defaults to `true`. |
| `allowedKeys`   | Conditional | Exact `{provider, keyId}` identities. At least one is required when `required` is `true`. |
| `enforcement`   | No          | `report` or `deny-create`. Defaults to `report`. |

`provider` is the named KMS config handle (not the catalog plugin). `keyId` is the unmodified
provider-native key identifier. Protocol `api` is configuration on that provider, not part of the
policy identity.

`provider` must match `[A-Za-z0-9][A-Za-z0-9_-]*`. Duplicate `{provider, keyId}` entries are
rejected, as are null entries.

Policy content stores references only. It never stores KMS credentials or cryptographic key
material.

## Create a policy

```shell
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{
    "name": "customer-data-encryption",
    "policyType": "system_iceberg_encryption",
    "comment": "Require an approved key for associated Iceberg tables",
    "enabled": true,
    "content": {
      "schemaVersion": 1,
      "required": true,
      "allowedKeys": [
        {"provider": "openbao-production", "keyId": "customer-pii-v1"}
      ],
      "enforcement": "deny-create"
    }
  }' \
  http://localhost:8090/api/metalakes/test/policies
```

Then associate it with a catalog, schema, or table using the normal policy association API
(`POST /api/metalakes/{metalake}/objects/{type}/{fullName}/policies`).

This document describes the policy API contract. Policy resolution, KMS validation, table-create
enforcement, and audit publication are separate runtime components.
