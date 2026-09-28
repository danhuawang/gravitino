<!--
  Copyright 2026 Datastrato Inc.
-->

# Design: Federated Iceberg REST catalogs are pass-through

| Field  | Value                                                                                                                                                                                                                                                                       |
| ------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Status | Accepted — 2026-09-17; implemented in [#2107](https://github.com/datastrato/gravitino-enterprise/pull/2107)                                                                                                                                                                 |
| Parent | [SPIP: Governed Iceberg Table Encryption](../SPIP-governed-iceberg-encryption.md) (Q2) · [SPIP: JDBC Encryption Guarantees and Atomic Integrity](SPIP-jdbc-encryption-guarantees-and-atomic-integrity.md) (Q2) · [ADR](../design-trusted-metadata-integrity.md) §1 |
| Scope  | The Iceberg REST catalog (IRC) with a REST backend                                                                                                                                                                                                                          |

## 1. Decision

A federated catalog is pass-through. The Gravitino Iceberg REST catalog (IRC1) forwards every
table request to the remote catalog (IRC2) untouched and returns IRC2's response unenforced. No
Gravitino encryption policy applies, including metadata integrity:

- no policy resolution, KMS key validation or encryption audit
- no integrity hash written and no verification on load
- no immutability check on encryption key properties

An encryption policy attached to a federated catalog has no effect. IRC2 owns encryption, keys and
integrity for its tables.

## 2. Background

A federated catalog has `catalog-backend = rest`; IRC1 serves it through
`FederatedCatalogWrapper`, and IRC2 performs every commit. The ADR's invariant, pointer and
integrity hash published in one atomic mutation, cannot hold because IRC1 has no backing store
for these tables.

Before this decision, federated catalogs went through admission, reported no atomic integrity,
and had every encryption request refused as `UNSUPPORTED_BACKEND`. That enforced policy on tables
Gravitino does not own while guaranteeing nothing about them.

## 3. Behaviour by path

| Path | IRC1 behaviour |
| --- | --- |
| Create, including staged create and CTAS | Forwarded untouched, including encryption properties. No admission, no KMS call. |
| Register, including overwrite | Forwarded untouched. IRC1 does not read the metadata file; IRC2 reads it. |
| Commit, including encryption key property changes | Forwarded untouched. No immutability check. |
| Load and scan planning | Forwarded. No integrity verification. |
| Credentials | Forwarded. IRC1 only rewrites the refresh endpoint, as for any federated catalog. |

`encryption-kms-source` is not supported on a federated catalog and has no effect there; no
warning is logged. Outside an auxiliary service with `dynamic-config-provider` it has no effect on
any catalog.

## 4. Critical user journeys

| # | Journey | Gravitino outcome | Responsible |
| --- | --- | --- | --- |
| F1 | Create a plain table through IRC1. | Forwarded; created on IRC2. | IRC2 |
| F2 | Create a table with `encryption.key-id` through IRC1. | Forwarded untouched; the key is not validated. | IRC2 |
| F3 | Attach an encryption policy that requires encryption to the federated catalog, then create a plain table. | The policy is not applied; the plain table is created. | Operator |
| F4 | Register an encrypted `metadata.json` through IRC1. | Forwarded untouched; no admission, no hash. | IRC2 |
| F5 | Commit a change that removes `encryption.key-id`. | Forwarded; not refused. | IRC2 |
| F6 | Tamper with metadata in IRC2's storage, then load through IRC1. | IRC1 returns what IRC2 returns; nothing is detected. | IRC2 |
| F7 | Search IRC1 audit logs for key decisions on a federated table. | No encryption audit records. | IRC2 |
| F8 | Set `encryption-kms-source` on a federated catalog. | No effect; not supported. | Operator |

## 5. Code changes

- The IRC table and namespace encryption dispatchers send a federated request straight to the
  underlying dispatcher: no admission on create, staged-create commit or register; no
  immutable-property check or key-update audit on commit.
- Federated registration bypasses `GovernedRegistrationService`, so IRC1 never reads the metadata
  file: it goes through `FederatedCatalogWrapper`'s `registerTable` override, which forwards the
  request by location to the remote catalog. The `IcebergCatalogWrapper` metadata-object
  registration primitive is never reached for a federated catalog.

## 6. Tests

- Federated encrypted create: forwarded untouched, no admission or KMS call.
- Federated commit removing `encryption.key-id`: forwarded, not refused.
- Federated register: forwarded untouched; the metadata file is not read.
- A non-federated catalog in the same deployment stays governed.

## 7. Docs

`docs/iceberg-rest-service.md`, REST backend section: governed encryption does not apply.

## 8. Alternatives and future work

**Rejected: refuse encryption requests** (the previous behaviour, §2). It blocks IRC2's own
encryption without protecting anything.

Governing federated encryption needs IRC2 to take part in integrity. Unscheduled options:

1. **Trust a governed remote**: IRC2 is a governed Gravitino IRC and advertises it, for example
   in `/v1/config`.
2. **Gravitino-side hash checked on load**: detects tampering but cannot prevent it; racy against
   IRC2's commit.
3. **Iceberg REST spec extension**: the commit carries the hash. Needs upstream Iceberg.

## 9. Open question

Gravitino API `lakehouse-iceberg` catalogs can also use a REST backend. Whether they become
pass-through too is undecided; this design covers the IRC only.
Until then, the Gravitino API still validates their catalog properties, including the table
format version bounds and a bound encryption key, as for any other backend.
