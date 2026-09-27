/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.encryption;

import com.google.common.base.Preconditions;
import java.util.Map;
import java.util.Optional;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergCatalogBackend;
import org.apache.gravitino.exceptions.EncryptionKeyRepointException;
import org.apache.gravitino.exceptions.MetadataIntegrityException;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos.Reason;

/**
 * Names the audit extras a metadata-integrity failure deserves, in one place.
 *
 * <p>The classification is structural: exception type, absence of a cause, and catalog backend.
 * Exception messages are never read, so nothing an operator or a remote catalog can phrase changes
 * what is recorded.
 *
 * <p>{@link #classify(Throwable, CatalogFacts)} returns {@link Optional#empty()} for every failure
 * it cannot name. It never guesses, and it never claims a tamper it has not been told about.
 */
public final class MetadataIntegrityFailureClassifier {

  private MetadataIntegrityFailureClassifier() {}

  /**
   * Returns the audit extras a failure deserves, or nothing when it cannot be named.
   *
   * <p>The whole decision table:
   *
   * <ul>
   *   <li>a {@link MetadataIntegrityException}, or an {@link EncryptionKeyRepointException}, is
   *       {@code METADATA_INTEGRITY_FAILED}
   *   <li>an exactly-{@link RuntimeException} failure with no cause on a {@link
   *       IcebergCatalogBackend#HIVE} backend is {@code NATIVE_LOAD_FAILURE}
   *   <li>everything else is {@link Optional#empty()}
   * </ul>
   *
   * <p>{@code NATIVE_LOAD_FAILURE} is context, not a cause: it says a Hive backend refused with the
   * shape an integrity refusal has, so an operator can join the record to the server log. It does
   * not assert that metadata was tampered with.
   *
   * @param failure the failure raised by the backend, never {@code null}
   * @param facts what the catalog is, independent of the failure, never {@code null}
   * @return the extras to merge into the audit record, or empty when the failure cannot be named
   */
  public static Optional<Map<String, String>> classify(Throwable failure, CatalogFacts facts) {
    Preconditions.checkArgument(failure != null, "failure cannot be null");
    Preconditions.checkArgument(facts != null, "facts cannot be null");

    if (isIntegrityRefusal(failure)) {
      return Optional.of(extras(Reason.METADATA_INTEGRITY_FAILED, (Exception) failure, facts));
    }
    if (isBareHiveRefusal(failure, facts)) {
      return Optional.of(extras(Reason.NATIVE_LOAD_FAILURE, (Exception) failure, facts));
    }
    return Optional.empty();
  }

  /** What the catalog is, independent of the failure. Built by the caller's wiring. */
  public static final class CatalogFacts {

    private final IcebergCatalogBackend backend;
    private final boolean encryptionGoverned;
    private final AtomicIntegrityCapability integrityExpected;

    private CatalogFacts(
        IcebergCatalogBackend backend,
        boolean encryptionGoverned,
        AtomicIntegrityCapability integrityExpected) {
      this.backend = backend;
      this.encryptionGoverned = encryptionGoverned;
      this.integrityExpected = integrityExpected;
    }

    /**
     * Creates the facts a classification is made against.
     *
     * @param backend the catalog's Iceberg backend
     * @param encryptionGoverned whether an admission service governs this catalog
     * @param integrityExpected whether the loaded catalog commits pointer and hash atomically
     * @return the catalog facts
     */
    public static CatalogFacts of(
        IcebergCatalogBackend backend,
        boolean encryptionGoverned,
        AtomicIntegrityCapability integrityExpected) {
      Preconditions.checkArgument(backend != null, "backend cannot be null");
      Preconditions.checkArgument(integrityExpected != null, "integrityExpected cannot be null");
      return new CatalogFacts(backend, encryptionGoverned, integrityExpected);
    }

    /**
     * Returns the catalog's Iceberg backend.
     *
     * @return the backend
     */
    public IcebergCatalogBackend backend() {
      return backend;
    }

    /**
     * Returns whether an admission service governs this catalog.
     *
     * @return {@code true} when the catalog is governed
     */
    public boolean encryptionGoverned() {
      return encryptionGoverned;
    }

    /**
     * Returns whether the loaded catalog can commit pointer and integrity hash atomically.
     *
     * @return the atomic-integrity capability
     */
    public AtomicIntegrityCapability integrityExpected() {
      return integrityExpected;
    }
  }

  private static boolean isIntegrityRefusal(Throwable failure) {
    return failure instanceof MetadataIntegrityException
        || failure instanceof EncryptionKeyRepointException;
  }

  /**
   * A bare, cause-less {@link RuntimeException} on a Hive backend.
   *
   * <p>The class is compared exactly rather than with {@code instanceof} so that every Iceberg
   * subclass — {@code CommitFailedException}, {@code CommitStateUnknownException}, {@code
   * ValidationException}, {@code NoSuchTableException} — stays out of the branch. In
   * iceberg-hive-metastore the only bare cause-less throws on the refresh and commit paths are
   * integrity refusals; everything noisy on the same paths wraps a cause.
   */
  private static boolean isBareHiveRefusal(Throwable failure, CatalogFacts facts) {
    return failure.getClass() == RuntimeException.class
        && failure.getCause() == null
        && facts.backend() == IcebergCatalogBackend.HIVE;
  }

  private static Map<String, String> extras(Reason reason, Exception failure, CatalogFacts facts) {
    return IcebergEncryptionAuditInfos.builder()
        .withReason(reason)
        .withBackend(facts.backend())
        .withEncryptionGoverned(facts.encryptionGoverned())
        .withIntegrityExpected(facts.integrityExpected())
        .withError(failure)
        .build();
  }
}
