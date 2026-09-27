<!--
  Copyright 2026 Datastrato Inc.
-->

# Opt-in server-side key validation

## Scope

Add `gravitino.encryption.validateKeys`, a server boolean with default `false` (#2179). This is a focused follow-up to the cross-API encryption acceptance stack. Server-side key inspection was never a requirement for governed encryption, and some clients cannot give the server KMS access: a wrong, disabled or inaccessible key fails at first write, in the engine's KMS client. So by default the server does not construct KMS clients or inspect keys; Spark and Iceberg need a working engine KMS to encrypt and decrypt data either way. `true` is the optional server-side check, kept for deployments that want it: configured KMS clients are constructed and each terminal admission validates its key once.

## Behavior

The shared `EncryptionAdmissionService` uses the existing evaluator preflight for terminal operations unless the setting is true. Preflight retains key-reference, catalog-source, policy and allowlist checks. The service retains its format-version, staged-commit binding, backend-capability and integrity boundaries. An admission decided without the KMS forwards the checked effective key, just as the existing stage path does. With the setting at `false`, no audit record carries a `kmsValidation` field: `NOT_ATTEMPTED` on every record would read as a check that failed to run. The service never reports `VALID` without KMS confirmation. CTAS stage remains preparation; commit remains terminal admission and publishes the one audit record. Federated pass-through and the standalone IRC guard are unchanged.

The setting is a standard `Configs` boolean, parsed like every other one; a value other than `true` reads as `false`. `KmsClientRegistry` reads it once, creates factories and clients only when it is true, and exposes it as `validatesKeys()`, which both admission services are built from. The KMS config parser retains its existing provider configuration checks. Listed providers still require a class name; a deployment at the default may omit server provider entries while retaining the catalog's `encryption-kms-source` binding.

## Change locations

- `Configs`: the setting and its default.
- `KmsClientRegistry`: read the setting once, construct clients after configuration parsing only when it is true, and expose `validatesKeys()`.
- `EncryptionAdmissionService`: preflight selection, checked-key forwarding after successful admission, and the audit field.
- `DatastratoGravitinoEnv` and `RESTService`: pass `validatesKeys()` to the admission service.
- Server configuration, the encryption policy guide and the admission/CTAS design documents: describe the opt-in.

## Behaviour by setting

| `validateKeys`   | KMS                                                                                            | Audit `kmsValidation` |
|------------------|------------------------------------------------------------------------------------------------|-----------------------|
| absent (default) | no client built, no call                                                                       | absent                |
| `false`          | no client built, no call                                                                       | absent                |
| `true`           | one call per terminal admission; an invalid key or an unreachable KMS is refused (fail-closed) | the call's result     |

Every other governance check (wrong source, malformed key, deny-create allowlist, format version, staged key, integrity) is unchanged and fail-closed whatever the setting, and report-mode violations remain audited.
