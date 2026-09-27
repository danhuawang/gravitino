/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.audit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.audit.v2.SimpleFormatterV2;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.listener.TableEventDispatcher;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos.Compliance;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos.KmsValidationStatus;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos.Reason;
import org.apache.gravitino.policy.IcebergEncryptionContent;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distribution;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.utils.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Encryption facts an inner table dispatcher stashes reach the written audit line through the real
 * chain: {@link TableEventDispatcher}, event bus, {@link AuditLogManager}, formatter and redactor.
 * Every assertion reads the line a {@link CapturingAuditLogWriter} captured, never the event.
 */
public class TestEncryptionAuditRecord {

  private static final NameIdentifier TABLE =
      NameIdentifier.of("metalake", "catalog", "schema", "table");
  private static final String CANARY_KEY = "Authorization";
  private static final String CANARY_VALUE = "Bearer audit-canary-token";

  /** Every encryption fact the builder can record, each with a distinct value. */
  private static final Map<String, String> ALL_FACTS =
      IcebergEncryptionAuditInfos.builder()
          .withDecisionId("decision-7f3a")
          .withPolicyNames("pii-policy", "finance-policy")
          .withPolicyEvaluation(Compliance.VIOLATION, IcebergEncryptionContent.Enforcement.REPORT)
          .withReason(Reason.KEY_NOT_ALLOWED)
          .withProviderKey(new KmsReference("openbao", "customer-pii-v2"))
          .withPreviousProviderKey(new KmsReference("openbao-old", "customer-pii-v1"))
          .withAttemptedProviderKey(new KmsReference("aws", "attempted-key-v9"))
          .withMetadataLocation("s3://warehouse/schema/table/metadata/v3.metadata.json")
          .withVerified(true)
          .withKmsValidation(KmsValidationStatus.VALID)
          .withError(new IllegalStateException("never written"))
          .build();

  @BeforeEach
  @AfterEach
  void clearCapturedState() {
    CapturingAuditLogWriter.clear();
    RequestContext.clear();
  }

  @ParameterizedTest
  @ValueSource(classes = {SimpleFormatterV2.class, JsonAuditFormatter.class})
  void testCreateSuccessWritesOneLineWithEveryEncryptionFactVerbatim(
      Class<? extends Formatter> formatter) {
    TableDispatcher inner = mock(TableDispatcher.class);
    Table table = table();
    when(createTable(inner))
        .thenAnswer(
            invocation -> {
              RequestContext.setAuditExtras(factsWithCanary());
              return table;
            });

    create(new TableEventDispatcher(CapturingAuditLogWriter.auditEventBus(formatter), inner));

    WrittenAuditLine line = onlyLine();
    Assertions.assertEquals("CREATE_TABLE", line.operation(), line.raw());
    Assertions.assertEquals("SUCCESS", line.status(), line.raw());
    assertEveryFactVerbatimAndCanaryMasked(line);
  }

  @ParameterizedTest
  @ValueSource(classes = {SimpleFormatterV2.class, JsonAuditFormatter.class})
  void testCreateFailureWritesOneFailureLineWithEveryEncryptionFactVerbatim(
      Class<? extends Formatter> formatter) {
    TableDispatcher inner = mock(TableDispatcher.class);
    IllegalArgumentException refusal = new IllegalArgumentException("refused");
    when(createTable(inner))
        .thenAnswer(
            invocation -> {
              RequestContext.setAuditExtras(factsWithCanary());
              throw refusal;
            });
    TableEventDispatcher dispatcher =
        new TableEventDispatcher(CapturingAuditLogWriter.auditEventBus(formatter), inner);

    Assertions.assertSame(
        refusal, Assertions.assertThrows(IllegalArgumentException.class, () -> create(dispatcher)));

    WrittenAuditLine line = onlyLine();
    Assertions.assertEquals("CREATE_TABLE", line.operation(), line.raw());
    Assertions.assertEquals("FAILURE", line.status(), line.raw());
    assertEveryFactVerbatimAndCanaryMasked(line);
  }

  @ParameterizedTest
  @ValueSource(classes = {SimpleFormatterV2.class, JsonAuditFormatter.class})
  void testAlterSuccessWritesOneLineWithEveryEncryptionFactVerbatim(
      Class<? extends Formatter> formatter) {
    TableDispatcher inner = mock(TableDispatcher.class);
    Table table = table();
    when(inner.alterTable(any(NameIdentifier.class), any(TableChange[].class)))
        .thenAnswer(
            invocation -> {
              RequestContext.setAuditExtras(factsWithCanary());
              return table;
            });

    new TableEventDispatcher(CapturingAuditLogWriter.auditEventBus(formatter), inner)
        .alterTable(TABLE, TableChange.setProperty("owner", "analytics"));

    WrittenAuditLine line = onlyLine();
    Assertions.assertEquals("ALTER_TABLE", line.operation(), line.raw());
    Assertions.assertEquals("SUCCESS", line.status(), line.raw());
    assertEveryFactVerbatimAndCanaryMasked(line);
  }

  /** The v1 formatter writes no custom info, so encryption audit needs a v2 or JSON formatter. */
  @Test
  @SuppressWarnings("deprecation")
  void testV1SimpleFormatterDropsTheEncryptionFacts() {
    TableDispatcher inner = mock(TableDispatcher.class);
    Table table = table();
    when(createTable(inner))
        .thenAnswer(
            invocation -> {
              RequestContext.setAuditExtras(factsWithCanary());
              return table;
            });

    create(
        new TableEventDispatcher(
            CapturingAuditLogWriter.auditEventBus(SimpleFormatter.class), inner));

    WrittenAuditLine line = onlyLine();
    Assertions.assertEquals("CREATE_TABLE", line.operation(), line.raw());
    Assertions.assertTrue(line.customInfo().isEmpty(), line.raw());
    Assertions.assertFalse(
        line.raw().contains(IcebergEncryptionAuditInfos.PREFIX),
        "v1 lines carry no encryption facts: " + line.raw());
    Assertions.assertFalse(line.raw().contains("customer-pii-v2"), line.raw());
  }

  private static void assertEveryFactVerbatimAndCanaryMasked(WrittenAuditLine line) {
    List<String> keys = encryptionKeyConstants();
    Assertions.assertTrue(
        ALL_FACTS.keySet().containsAll(keys), "fixture must record every key: " + keys);
    for (String key : keys) {
      Assertions.assertEquals(
          ALL_FACTS.get(key), line.customInfo().get(key), key + " in " + line.raw());
    }
    Assertions.assertEquals(
        AuditLogRedactor.REDACTED_VALUE, line.customInfo().get(CANARY_KEY), line.raw());
    Assertions.assertFalse(line.raw().contains(CANARY_VALUE), line.raw());
  }

  /** The {@code icebergEncryption.*} key constants, read by reflection so a new one is covered. */
  private static List<String> encryptionKeyConstants() {
    List<String> keys =
        Arrays.stream(IcebergEncryptionAuditInfos.class.getFields())
            .filter(field -> Modifier.isStatic(field.getModifiers()))
            .filter(field -> field.getType() == String.class)
            .filter(field -> !field.getName().equals("PREFIX"))
            .map(TestEncryptionAuditRecord::constantValue)
            .collect(Collectors.toList());
    Assertions.assertFalse(keys.isEmpty());
    keys.forEach(
        key -> Assertions.assertTrue(key.startsWith(IcebergEncryptionAuditInfos.PREFIX), key));
    return keys;
  }

  private static String constantValue(Field field) {
    try {
      return (String) field.get(null);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Map<String, String> factsWithCanary() {
    return ImmutableMap.<String, String>builder()
        .putAll(ALL_FACTS)
        .put(CANARY_KEY, CANARY_VALUE)
        .build();
  }

  /** Asserts that exactly one line was written and returns it parsed. */
  private static WrittenAuditLine onlyLine() {
    List<String> lines = CapturingAuditLogWriter.lines();
    Assertions.assertEquals(1, lines.size(), "one written line per decision: " + lines);
    return WrittenAuditLine.parse(lines.get(0));
  }

  private static Table createTable(TableDispatcher inner) {
    return inner.createTable(
        any(NameIdentifier.class),
        any(Column[].class),
        any(),
        any(),
        any(Transform[].class),
        any(Distribution.class),
        any(SortOrder[].class),
        any(Index[].class));
  }

  private static void create(TableEventDispatcher dispatcher) {
    dispatcher.createTable(
        TABLE,
        new Column[] {Column.of("id", Types.LongType.get())},
        "comment",
        ImmutableMap.of(),
        new Transform[0],
        Distributions.NONE,
        new SortOrder[0],
        new Index[0]);
  }

  private static Table table() {
    Table table = mock(Table.class);
    when(table.name()).thenReturn(TABLE.name());
    when(table.columns()).thenReturn(new Column[] {Column.of("id", Types.LongType.get())});
    when(table.properties()).thenReturn(ImmutableMap.of());
    when(table.partitioning()).thenReturn(new Transform[0]);
    when(table.distribution()).thenReturn(Distributions.NONE);
    when(table.sortOrder()).thenReturn(new SortOrder[0]);
    when(table.index()).thenReturn(new Index[0]);
    return table;
  }
}
