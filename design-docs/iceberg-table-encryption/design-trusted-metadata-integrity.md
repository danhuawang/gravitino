<!--
  Copyright 2026 Datastrato Inc.
-->

# ADR: Atomic metadata integrity for encrypted Iceberg tables

| Field      | Value                                                                                                                                                                                                                                                                                         |
| ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Status     | Accepted                                                                                                                                                                                                                                                                                      |
| Decision   | Use backing-store-specific atomic pointer-and-hash commits for JDBC and Hive                                                                                                                                                                                                                  |
| Authors    | Nevin Zheng ([@nevzheng](https://github.com/nevzheng))                                                                                                                                                                                                                                        |
| Created    | 2026-08-31                                                                                                                                                                                                                                                                                    |
| Updated    | 2026-09-08                                                                                                                                                                                                                                                                                    |
| Parent     | [SPIP: Governed Iceberg Table Encryption](SPIP-governed-iceberg-encryption.md), section [B3](SPIP-governed-iceberg-encryption.md#b3-integrity-on-load)                                                                                                                                        |
| Supersedes | The persistence and backend-scope details previously recorded in SPIP B3                                                                                                                                                                                                                      |
| Related    | [#914](https://github.com/datastrato/gravitino-enterprise/issues/914) · [#1289](https://github.com/datastrato/gravitino-enterprise/pull/1289) · [#1332](https://github.com/datastrato/gravitino-enterprise/pull/1332) · [#1264](https://github.com/datastrato/gravitino-enterprise/pull/1264) |
| Iceberg    | [Table encryption](https://iceberg.apache.org/docs/latest/encryption/) · [Catalog security requirements](https://iceberg.apache.org/docs/latest/encryption/#catalog-security-requirements)                                                                                                    |

## 1. Decision

For v1, Gravitino supports exactly two backing paths that can atomically publish an Iceberg table's
`metadata_location` and trusted hash. This prioritizes atomicity over uniform persistence and
broader catalog support.

### Supported options

| Option                                                         | How the commit is atomic                                                                                                         |
| -------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| **JDBC:** `provider=lakehouse-iceberg`, `catalog-backend=jdbc` | `metadata_location` and the JDBC-local hash row use one JDBC `Connection` and transaction, so they commit or roll back together. |
| **Hive:** `provider=lakehouse-iceberg`, `catalog-backend=hive` | `metadata_location` and native HMS `metadata_hash` use one create or alter mutation, so HMS accepts or rejects both.             |

Support depends on the backing path, not the API or IRC entry point, backend label, or infrastructure
ownership. Gravitino must mediate every supported pointer mutation, the path must provide the atomic
write and consistent read defined below, and JDBC must permit side-table migration. All other paths
are out of v1 scope.

### Trade-off

We accept two backend-specific implementations and narrower v1 support to eliminate partial
pointer-and-hash commits. We reject a uniform split-store design whose transaction log,
reconciliation, and audit events could detect or recover incomplete writes but could not make them
atomic.

### Required invariant

For every supported Gravitino commit, the authoritative catalog pointer and trusted hash advance
together or not at all:

```text
before:  (pointer = P0, hash = H0)
success: (pointer = P1, hash = H1)
failure: (pointer = P0, hash = H0)
never:   (pointer = P1, hash = H0) or (pointer = P0, hash = H1)
```

`Pointer` means the catalog's current `metadata_location`, not `metadata.json` or Gravitino's
`table_meta`. A failed commit may leave an unreferenced metadata file without violating the
invariant. The invariant covers pointer, identity, and existence changes: JDBC rename and drop must
update or delete the hash in the same transaction, and register or overwrite requires separately
approved enrollment.

## 2. Background and rationale

`metadata.json` lives in potentially untrusted object storage, so Gravitino binds its
Base64-encoded SHA-256 hash—over Iceberg's canonical `TableMetadataParser` serialization—to the
authoritative catalog pointer. The governed Hive path uses Iceberg's native `metadata_hash`; JDBC
needs a side table because the standard schema has no equivalent.

A shared entity-store row cannot commit atomically with an HMS or separately managed JDBC pointer.
Backend-local persistence and narrower v1 support preserve atomicity.

## 3. Consequences

- Backend-specific trusted stores share hash derivation and verification while avoiding split-store
  commits, a write-ahead log, and `committed` reconciliation state.
- Reads obtain a consistent pointer and hash—JDBC through a join or snapshot/repeatable-read
  transaction, HMS through one table object—and fail closed before returning or caching invalid
  metadata.
- Existing tables require approved enrollment. Direct or legacy writers are outside the guarantee
  and may make governed tables unavailable until approved repair.
- A new catalog requires a native atomic integrity contract or a new product decision accepting
  recovery instead of strict atomicity.

## 4. Alternatives considered

### 4.1 Unified entity-store integrity table — Rejected

Store the authoritative hash for JDBC and Hive in one Gravitino entity-store table. This provides a
uniform persistence model, but the hash cannot commit atomically with an HMS or separately managed
JDBC pointer.

### 4.2 Split-store transaction log and audit — Rejected

Record the intended hash with `committed=false`, publish the catalog pointer in a separate
transaction, then verify it and mark the record committed. Reconciliation and audit events would
identify interrupted or unresolved operations.

This provides recovery, not atomicity: a pointer and hash can still reach different committed
states. It is not planned for v1. It may be reconsidered only if future catalog support explicitly
accepts a recovery-based guarantee.

### 4.3 XA or two-phase commit — Rejected

Coordinate the catalog and entity store through a distributed transaction. This adds backend
requirements, coordination, and failure modes that the two backend-local transactions avoid.
