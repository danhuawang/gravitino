/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.encryption;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergCatalogBackend;
import org.apache.gravitino.encryption.MetadataIntegrityFailureClassifier.CatalogFacts;
import org.apache.gravitino.exceptions.EncryptionKeyRepointException;
import org.apache.gravitino.exceptions.MetadataIntegrityException;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The whole decision table of {@link MetadataIntegrityFailureClassifier}, row by row, plus the
 * negatives that keep the Hive branch tight.
 */
public class TestMetadataIntegrityFailureClassifier {

  /** A tamper-like baseline message that must classify identically to unrelated messages. */
  private static final String TELLTALE_MESSAGE =
      "zzqqx metadata file might have been modified hash differs";

  private static final CatalogFacts HIVE_GOVERNED =
      CatalogFacts.of(IcebergCatalogBackend.HIVE, true, AtomicIntegrityCapability.SUPPORTED);

  @Test
  public void testTypedIntegrityFailureIsNamedOnEveryBackend() {
    for (IcebergCatalogBackend backend : IcebergCatalogBackend.values()) {
      CatalogFacts facts = CatalogFacts.of(backend, true, AtomicIntegrityCapability.SUPPORTED);
      Map<String, String> extras =
          expectPresent(
              MetadataIntegrityFailureClassifier.classify(
                  new MetadataIntegrityException(TELLTALE_MESSAGE), facts));

      assertEquals(
          Reason.METADATA_INTEGRITY_FAILED.code(), extras.get(IcebergEncryptionAuditInfos.REASON));
      assertEquals(backend.name(), extras.get(IcebergEncryptionAuditInfos.BACKEND));
    }
  }

  /**
   * The key-repoint row of the table: the typed register-overwrite refusal is an integrity refusal
   * on every backend, while the plain {@link IllegalArgumentException} it would otherwise have been
   * stays unnamed.
   */
  @Test
  public void testKeyRepointRefusalIsNamedOnEveryBackend() {
    for (IcebergCatalogBackend backend : IcebergCatalogBackend.values()) {
      CatalogFacts facts = CatalogFacts.of(backend, true, AtomicIntegrityCapability.SUPPORTED);
      Map<String, String> extras =
          expectPresent(
              MetadataIntegrityFailureClassifier.classify(
                  new EncryptionKeyRepointException(TELLTALE_MESSAGE), facts));

      assertEquals(
          Reason.METADATA_INTEGRITY_FAILED.code(), extras.get(IcebergEncryptionAuditInfos.REASON));
      assertEquals(
          EncryptionKeyRepointException.class.getName(),
          extras.get(IcebergEncryptionAuditInfos.ERROR_TYPE));
      assertFalse(
          MetadataIntegrityFailureClassifier.classify(
                  new IllegalArgumentException(TELLTALE_MESSAGE), facts)
              .isPresent());
    }
  }

  @Test
  public void testBareCauselessRuntimeExceptionOnHiveIsNativeLoadFailure() {
    Map<String, String> extras =
        expectPresent(
            MetadataIntegrityFailureClassifier.classify(
                new RuntimeException(TELLTALE_MESSAGE), HIVE_GOVERNED));

    assertEquals(Reason.NATIVE_LOAD_FAILURE.code(), extras.get(IcebergEncryptionAuditInfos.REASON));
    assertEquals(
        IcebergCatalogBackend.HIVE.name(), extras.get(IcebergEncryptionAuditInfos.BACKEND));
    assertEquals(
        RuntimeException.class.getName(), extras.get(IcebergEncryptionAuditInfos.ERROR_TYPE));
  }

  @ParameterizedTest
  @EnumSource(
      value = IcebergCatalogBackend.class,
      names = {"JDBC", "MEMORY", "REST", "CUSTOM"})
  public void testBareRuntimeExceptionOffHiveIsNotNamed(IcebergCatalogBackend backend) {
    CatalogFacts facts = CatalogFacts.of(backend, true, AtomicIntegrityCapability.SUPPORTED);

    assertFalse(
        MetadataIntegrityFailureClassifier.classify(new RuntimeException(TELLTALE_MESSAGE), facts)
            .isPresent());
  }

  @Test
  public void testRuntimeExceptionCarryingACauseIsNotNamed() {
    assertEmpty(
        new RuntimeException(TELLTALE_MESSAGE, new IllegalStateException("backend failed")));
  }

  /** The Hive fallback matches {@link RuntimeException} exactly, not its subclasses. */
  @Test
  public void testRuntimeExceptionSubclassIsNotNamed() {
    assertEmpty(new IllegalStateException(TELLTALE_MESSAGE));
  }

  @Test
  public void testUnnamedFailuresStayUnnamed() {
    assertAll(
        () -> assertEmpty(new IllegalArgumentException(TELLTALE_MESSAGE)),
        () -> assertEmpty(new Throwable(TELLTALE_MESSAGE)));
  }

  @Test
  public void testEveryNamedResultCarriesTheCatalogFacts() {
    for (boolean governed : new boolean[] {true, false}) {
      for (AtomicIntegrityCapability capability : AtomicIntegrityCapability.values()) {
        CatalogFacts facts = CatalogFacts.of(IcebergCatalogBackend.HIVE, governed, capability);

        for (Throwable failure :
            new Throwable[] {
              new MetadataIntegrityException(TELLTALE_MESSAGE),
              new RuntimeException(TELLTALE_MESSAGE)
            }) {
          Map<String, String> extras =
              expectPresent(MetadataIntegrityFailureClassifier.classify(failure, facts));

          assertEquals(
              IcebergCatalogBackend.HIVE.name(), extras.get(IcebergEncryptionAuditInfos.BACKEND));
          assertEquals(
              Boolean.toString(governed),
              extras.get(IcebergEncryptionAuditInfos.ENCRYPTION_GOVERNED));
          assertEquals(
              capability.name(), extras.get(IcebergEncryptionAuditInfos.INTEGRITY_EXPECTED));
          assertEquals(
              failure.getClass().getName(), extras.get(IcebergEncryptionAuditInfos.ERROR_TYPE));
          assertTrue(extras.containsKey(IcebergEncryptionAuditInfos.REASON));
        }
      }
    }
  }

  @Test
  public void testKeysSitUnderTheSharedPrefix() {
    assertAll(
        () -> assertEquals("icebergEncryption.backend", IcebergEncryptionAuditInfos.BACKEND),
        () ->
            assertEquals(
                "icebergEncryption.encryptionGoverned",
                IcebergEncryptionAuditInfos.ENCRYPTION_GOVERNED),
        () ->
            assertEquals(
                "icebergEncryption.integrityExpected",
                IcebergEncryptionAuditInfos.INTEGRITY_EXPECTED));
  }

  @Test
  public void testClassificationIsIndependentOfMessageAndMessagesAreNotEmitted() {
    Map<String, String> typedBaseline =
        expectPresent(
            MetadataIntegrityFailureClassifier.classify(
                new MetadataIntegrityException(TELLTALE_MESSAGE), HIVE_GOVERNED));
    Map<String, String> nativeBaseline =
        expectPresent(
            MetadataIntegrityFailureClassifier.classify(
                new RuntimeException(TELLTALE_MESSAGE), HIVE_GOVERNED));

    for (String message : new String[] {"unrelated failure", ""}) {
      assertEquals(
          typedBaseline,
          expectPresent(
              MetadataIntegrityFailureClassifier.classify(
                  new MetadataIntegrityException("%s", message), HIVE_GOVERNED)));
      assertEquals(
          nativeBaseline,
          expectPresent(
              MetadataIntegrityFailureClassifier.classify(
                  new RuntimeException(message), HIVE_GOVERNED)));
    }
    assertEquals(
        nativeBaseline,
        expectPresent(
            MetadataIntegrityFailureClassifier.classify(
                new RuntimeException((String) null), HIVE_GOVERNED)));

    String privateMarker = "message-never-leaks-4fbddc";
    for (Throwable failure :
        new Throwable[] {
          new MetadataIntegrityException(privateMarker), new RuntimeException(privateMarker)
        }) {
      Map<String, String> extras =
          expectPresent(MetadataIntegrityFailureClassifier.classify(failure, HIVE_GOVERNED));

      for (Map.Entry<String, String> entry : extras.entrySet()) {
        assertFalse(
            entry.getValue().contains(privateMarker),
            "extra " + entry.getKey() + " leaked the failure message");
      }
    }
  }

  @Test
  public void testNullArgumentsAreRejected() {
    assertAll(
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> MetadataIntegrityFailureClassifier.classify(null, HIVE_GOVERNED)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    MetadataIntegrityFailureClassifier.classify(
                        new RuntimeException(TELLTALE_MESSAGE), null)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> CatalogFacts.of(null, true, AtomicIntegrityCapability.SUPPORTED)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> CatalogFacts.of(IcebergCatalogBackend.HIVE, true, null)));
  }

  @Test
  public void testCatalogFactsExposeWhatTheyWereGiven() {
    CatalogFacts facts =
        CatalogFacts.of(IcebergCatalogBackend.JDBC, false, AtomicIntegrityCapability.UNSUPPORTED);

    assertAll(
        () -> assertEquals(IcebergCatalogBackend.JDBC, facts.backend()),
        () -> assertFalse(facts.encryptionGoverned()),
        () -> assertEquals(AtomicIntegrityCapability.UNSUPPORTED, facts.integrityExpected()));
  }

  private static void assertEmpty(Throwable failure) {
    assertFalse(
        MetadataIntegrityFailureClassifier.classify(failure, HIVE_GOVERNED).isPresent(),
        failure.getClass().getName() + " must not be named");
  }

  private static Map<String, String> expectPresent(Optional<Map<String, String>> extras) {
    assertTrue(extras.isPresent(), "expected a classification");
    return extras.get();
  }
}
