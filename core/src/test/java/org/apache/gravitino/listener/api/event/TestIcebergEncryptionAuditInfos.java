/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.listener.api.event;

import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.audit.AuditLog;
import org.apache.gravitino.audit.v2.SimpleAuditLogV2;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos.Compliance;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos.KmsValidationStatus;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos.Reason;
import org.apache.gravitino.listener.api.info.TableInfo;
import org.apache.gravitino.policy.IcebergEncryptionContent;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.indexes.Indexes;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Pins the contracts encryption audit facts depend on: they ride existing table events without
 * changing operation classification, the caller's map is snapshotted and exposed read-only, null
 * builder arguments are rejected as illegal input, reason codes stay stable and unique, and error
 * detail never reaches the persisted audit log.
 */
public class TestIcebergEncryptionAuditInfos {

  private static final NameIdentifier TABLE =
      NameIdentifier.of("metalake", "catalog", "schema", "orders");

  @Test
  void testReportStaysOnSuccessfulCreateTableEvent() {
    KmsReference key = new KmsReference("openbao", "dev-key");
    Map<String, String> extras =
        IcebergEncryptionAuditInfos.builder()
            .withPolicyName("pii-encryption-required")
            .withPolicyEvaluation(Compliance.VIOLATION, IcebergEncryptionContent.Enforcement.REPORT)
            .withReason(Reason.KEY_NOT_ALLOWED)
            .withProviderKey(key)
            .withKmsValidation(KmsValidationStatus.VALID)
            .build();
    TableInfo created =
        tableInfo(
            ImmutableMap.of("encryption.key-provider", "openbao", "encryption.key-id", "dev-key"));
    CreateTableEvent event = new CreateTableEvent("alice", TABLE, created, extras);

    Assertions.assertEquals(OperationType.CREATE_TABLE, event.operationType());
    Assertions.assertEquals(OperationStatus.SUCCESS, event.operationStatus());
    Assertions.assertEquals(AuditLog.Operation.CREATE_TABLE, AuditLog.Operation.fromEvent(event));
    Assertions.assertEquals(
        AuditLog.Operation.CREATE_TABLE, new SimpleAuditLogV2(event).operation());
    Assertions.assertEquals(
        "pii-encryption-required", event.customInfo().get(IcebergEncryptionAuditInfos.POLICY_NAME));
    Assertions.assertEquals(
        "VIOLATION", event.customInfo().get(IcebergEncryptionAuditInfos.COMPLIANCE));
    Assertions.assertEquals(
        "REPORT", event.customInfo().get(IcebergEncryptionAuditInfos.ENFORCEMENT));
    Assertions.assertEquals(
        "KEY_NOT_ALLOWED", event.customInfo().get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        "openbao", event.customInfo().get(IcebergEncryptionAuditInfos.PROVIDER));
    Assertions.assertEquals("dev-key", event.customInfo().get(IcebergEncryptionAuditInfos.KEY_ID));
    Assertions.assertEquals(
        "dev-key", event.createdTableInfo().properties().get("encryption.key-id"));
    Assertions.assertFalse(event.customInfo().containsKey(IcebergEncryptionAuditInfos.ERROR_TYPE));
  }

  @Test
  void testDenyStaysOnCreateTableFailureEvent() {
    Map<String, String> extras =
        IcebergEncryptionAuditInfos.builder()
            .withPolicyName("pii-encryption-required")
            .withPolicyEvaluation(
                Compliance.VIOLATION, IcebergEncryptionContent.Enforcement.DENY_CREATE)
            .withReason(Reason.KEY_REQUIRED)
            .build();
    CreateTableFailureEvent event =
        new CreateTableFailureEvent(
            "alice",
            TABLE,
            new IllegalArgumentException("missing_or_not_allowed"),
            tableInfo(null),
            extras);

    Assertions.assertEquals(OperationType.CREATE_TABLE, event.operationType());
    Assertions.assertEquals(OperationStatus.FAILURE, event.operationStatus());
    Assertions.assertEquals(AuditLog.Operation.CREATE_TABLE, AuditLog.Operation.fromEvent(event));
    Assertions.assertEquals(
        "VIOLATION", event.customInfo().get(IcebergEncryptionAuditInfos.COMPLIANCE));
    Assertions.assertEquals(
        "DENY_CREATE", event.customInfo().get(IcebergEncryptionAuditInfos.ENFORCEMENT));
    Assertions.assertEquals(
        "KEY_REQUIRED", event.customInfo().get(IcebergEncryptionAuditInfos.REASON));
  }

  @Test
  void testKeyBindingBlockStaysOnAlterTableFailureEvent() {
    KmsReference existing = new KmsReference("openbao", "pii-tier1");
    KmsReference attempted = new KmsReference("openbao", "other-key");
    Map<String, String> extras =
        IcebergEncryptionAuditInfos.builder()
            .withProviderKey(existing)
            .withAttemptedProviderKey(attempted)
            .withReason(Reason.ENCRYPTION_KEY_CHANGE_DENIED)
            .build();
    AlterTableFailureEvent event =
        new AlterTableFailureEvent(
            "alice",
            TABLE,
            new IllegalArgumentException("encryption key change denied"),
            new TableChange[0],
            extras);

    Assertions.assertEquals(OperationType.ALTER_TABLE, event.operationType());
    Assertions.assertEquals(OperationStatus.FAILURE, event.operationStatus());
    Assertions.assertEquals(AuditLog.Operation.ALTER_TABLE, AuditLog.Operation.fromEvent(event));
    Assertions.assertEquals(
        "pii-tier1", event.customInfo().get(IcebergEncryptionAuditInfos.KEY_ID));
    Assertions.assertEquals(
        "other-key", event.customInfo().get(IcebergEncryptionAuditInfos.ATTEMPTED_KEY_ID));
  }

  @Test
  void testIntegrityFailureStaysOnLoadTableFailureEvent() {
    RuntimeException error =
        new RuntimeException("jdbc:postgresql://secret-host/database?password=do-not-log");
    Map<String, String> extras =
        IcebergEncryptionAuditInfos.builder()
            .withReason(Reason.METADATA_INTEGRITY_FAILED)
            .withMetadataLocation("s3://bucket/metadata/00001-abc.json")
            .withVerified(false)
            .withError(error)
            .build();
    LoadTableFailureEvent event = new LoadTableFailureEvent("alice", TABLE, error, extras);
    String persisted = new SimpleAuditLogV2(event).toString();

    Assertions.assertEquals(OperationType.LOAD_TABLE, event.operationType());
    Assertions.assertEquals(OperationStatus.FAILURE, event.operationStatus());
    Assertions.assertEquals(AuditLog.Operation.LOAD_TABLE, AuditLog.Operation.fromEvent(event));
    Assertions.assertEquals("false", event.customInfo().get(IcebergEncryptionAuditInfos.VERIFIED));
    Assertions.assertEquals(
        "s3://bucket/metadata/00001-abc.json",
        event.customInfo().get(IcebergEncryptionAuditInfos.METADATA_LOCATION));
    Assertions.assertEquals(
        RuntimeException.class.getName(),
        event.customInfo().get(IcebergEncryptionAuditInfos.ERROR_TYPE));
    Assertions.assertFalse(event.customInfo().containsKey("icebergEncryption.errorMessage"));
    Assertions.assertFalse(persisted.contains("secret-host"));
    Assertions.assertFalse(persisted.contains("do-not-log"));
  }

  @Test
  void testKmsValidationRequiresKeyIdentity() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            IcebergEncryptionAuditInfos.builder()
                .withKmsValidation(KmsValidationStatus.INVALID)
                .build());
  }

  @Test
  void testNullBuilderArgumentsAreRejectedAsIllegalArguments() {
    IcebergEncryptionAuditInfos.Builder builder = IcebergEncryptionAuditInfos.builder();

    Assertions.assertEquals(
        "policy names cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> builder.withPolicyNames((String[]) null))
            .getMessage());
    Assertions.assertEquals(
        "compliance cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                    builder.withPolicyEvaluation(null, IcebergEncryptionContent.Enforcement.REPORT))
            .getMessage());
    Assertions.assertEquals(
        "enforcement cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> builder.withPolicyEvaluation(Compliance.COMPLIANT, null))
            .getMessage());
    Assertions.assertEquals(
        "reason cannot be null",
        Assertions.assertThrows(IllegalArgumentException.class, () -> builder.withReason(null))
            .getMessage());
    Assertions.assertEquals(
        "kmsValidation cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> builder.withKmsValidation(null))
            .getMessage());
    Assertions.assertEquals(
        "error cannot be null",
        Assertions.assertThrows(IllegalArgumentException.class, () -> builder.withError(null))
            .getMessage());
    Assertions.assertEquals(
        "providerKey cannot be null",
        Assertions.assertThrows(IllegalArgumentException.class, () -> builder.withProviderKey(null))
            .getMessage());
    Assertions.assertEquals(
        "previousProviderKey cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> builder.withPreviousProviderKey(null))
            .getMessage());
    Assertions.assertEquals(
        "attemptedProviderKey cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> builder.withAttemptedProviderKey(null))
            .getMessage());
  }

  @Test
  void testBlankPolicyNamesAreRejectedAsIllegalArguments() {
    IcebergEncryptionAuditInfos.Builder builder = IcebergEncryptionAuditInfos.builder();

    for (String blank : new String[] {"", "   "}) {
      Assertions.assertEquals(
          "policyName cannot be blank",
          Assertions.assertThrows(
                  IllegalArgumentException.class, () -> builder.withPolicyName(blank))
              .getMessage());
      Assertions.assertEquals(
          "policy name cannot be blank",
          Assertions.assertThrows(
                  IllegalArgumentException.class, () -> builder.withPolicyNames("valid", blank))
              .getMessage());
    }
  }

  @Test
  void testCustomInfoSnapshotsCallerMapAndIsReadOnly() {
    Map<String, String> extras =
        IcebergEncryptionAuditInfos.builder().withReason(Reason.COMPLIANT).build();
    Assertions.assertThrows(UnsupportedOperationException.class, () -> extras.put("k", "v"));

    Map<String, String> caller = new HashMap<>(extras);
    CreateTableEvent success = new CreateTableEvent("alice", TABLE, tableInfo(null), caller);
    CreateTableFailureEvent failure =
        new CreateTableFailureEvent(
            "alice", TABLE, new IllegalArgumentException("denied"), tableInfo(null), caller);
    caller.put("icebergEncryption.injected", "after-construction");

    Assertions.assertFalse(success.customInfo().containsKey("icebergEncryption.injected"));
    Assertions.assertFalse(failure.customInfo().containsKey("icebergEncryption.injected"));
    Assertions.assertThrows(
        UnsupportedOperationException.class, () -> success.customInfo().put("k", "v"));
    Assertions.assertThrows(
        UnsupportedOperationException.class, () -> failure.customInfo().put("k", "v"));
  }

  @Test
  void testReasonCodesAreStableAndUnique() {
    Set<String> codes =
        Arrays.stream(Reason.values()).map(Reason::code).collect(Collectors.toSet());

    Assertions.assertEquals(Reason.values().length, codes.size());
    Assertions.assertEquals("COMPLIANT", Reason.COMPLIANT.code());
    Assertions.assertEquals("KMS_SERVICE_UNAVAILABLE", Reason.KMS_SERVICE_UNAVAILABLE.code());
    Assertions.assertEquals("AMBIGUOUS_POLICY", Reason.AMBIGUOUS_POLICY.code());
    Assertions.assertEquals("METADATA_INTEGRITY_FAILED", Reason.METADATA_INTEGRITY_FAILED.code());
  }

  private static TableInfo tableInfo(@Nullable Map<String, String> properties) {
    return new TableInfo(
        "orders",
        new Column[] {Column.of("id", Types.LongType.get())},
        null,
        properties,
        new Transform[0],
        Distributions.NONE,
        new SortOrder[0],
        Indexes.EMPTY_INDEXES,
        null);
  }
}
