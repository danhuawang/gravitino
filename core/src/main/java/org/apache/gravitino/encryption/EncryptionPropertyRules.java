/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.encryption;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergEncryptionCatalogBinding;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergFormatVersionResolution;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergFormatVersionResolution.EffectiveFormatVersion;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergFormatVersionResolution.Source;
import org.apache.gravitino.encryption.EncryptionAdmissionService.Operation;
import org.apache.gravitino.encryption.kms.KmsReference;

/**
 * The table-property rules an encryption admission applies around its decision.
 *
 * <p>Every method is a pure function of the maps it is handed: no policy, no KMS, no audit and no
 * logging. {@link EncryptionAdmissionService} owns the decision and calls these rules for the
 * property arithmetic before and after it, so a rule can be pinned without standing up a policy
 * resolver or a key validator.
 */
final class EncryptionPropertyRules {

  /** The only format-version floor there is today: Iceberg encryption cannot sit below 3 (R3). */
  private static final FormatVersionRequirement ENCRYPTION =
      new FormatVersionRequirement(
          IcebergFormatVersionResolution.MIN_ENCRYPTED_FORMAT_VERSION, "Iceberg encryption");

  private EncryptionPropertyRules() {}

  /**
   * Builds the encryption input an admission decides on: the request's properties, with the key
   * inherited from the catalog when the operation inherits one (override, then request, then
   * default), and a key-id-only reference completed with the catalog's KMS source.
   *
   * @param operation the mutation being admitted
   * @param requestedProperties the properties exactly as the request carried them
   * @param catalogProperties the owning catalog's properties
   * @param kmsSource the catalog's {@code encryption-kms-source}, or {@code null}
   * @return a new mutable map; the inputs are not modified
   */
  static Map<String, String> effectiveProperties(
      Operation operation,
      Map<String, String> requestedProperties,
      Map<String, String> catalogProperties,
      @Nullable String kmsSource) {
    Map<String, String> effective = new LinkedHashMap<>(requestedProperties);
    String keyId = effective.get(IcebergConstants.ENCRYPTION_KEY_ID);
    if (inheritsCatalogKeys(operation)) {
      String overrideKeyId = IcebergEncryptionCatalogBinding.overrideKeyId(catalogProperties);
      String defaultKeyId = IcebergEncryptionCatalogBinding.defaultKeyId(catalogProperties);
      keyId = overrideKeyId != null ? overrideKeyId : keyId != null ? keyId : defaultKeyId;
    }
    if (keyId != null) {
      effective.put(IcebergConstants.ENCRYPTION_KEY_ID, keyId);
      if (!effective.containsKey(IcebergConstants.ENCRYPTION_KEY_PROVIDER)
          && StringUtils.isNotBlank(kmsSource)) {
        // Engines write only the key id into metadata files, so a key-id-only reference is
        // completed from the catalog's KMS binding rather than refused.
        effective.put(IcebergConstants.ENCRYPTION_KEY_PROVIDER, kmsSource);
      }
    }
    return effective;
  }

  /**
   * Says whether the catalog's default and override key take part in the merge: only a create and
   * the stage step of a staged create do. A staged commit is checked against the catalog's key
   * instead ({@link #stagedCommitKeyViolation} says why); a registration or an overwrite takes its
   * key from the metadata file it was handed, which is never injected from catalog properties (R7).
   */
  static boolean inheritsCatalogKeys(Operation operation) {
    return operation == Operation.CREATE || operation == Operation.STAGE;
  }

  /**
   * Checks the key a staged commit publishes against the key a create would have been given.
   *
   * <p><b>Why a staged commit is checked against the catalog key rather than given it.</b> Iceberg
   * stages a create from the request properties alone, applying neither the catalog's {@code
   * table-default.} nor its {@code table-override.} key ({@code CatalogHandlers.stageTableCreate},
   * iceberg-core 1.11.0, {@code CatalogHandlers.java:415-440}), so the stage fills the key in, and
   * the Java REST client replays the staged metadata's whole property map in the commit ({@code
   * RESTSessionCatalog.createChanges}, {@code RESTSessionCatalog.java:1140-1176}). The server keeps
   * no state between the two and a client may commit {@code assert-create} without ever staging, so
   * the commit is admitted on its own: one that carries no key where the catalog binds one, or
   * another key than the catalog's override, was built away from that binding. It is never given
   * the key instead, because data may already be written under whatever key the stage named.
   *
   * @param operation the mutation being admitted; only {@link Operation#STAGED_COMMIT} is checked
   * @param catalogProperties the owning catalog's properties
   * @param effective the effective properties from {@link #effectiveProperties}
   * @return an operator-facing description of the violation, or empty when there is none
   */
  static Optional<String> stagedCommitKeyViolation(
      Operation operation, Map<String, String> catalogProperties, Map<String, String> effective) {
    if (operation != Operation.STAGED_COMMIT) {
      return Optional.empty();
    }
    String stagedKeyId = effective.get(IcebergConstants.ENCRYPTION_KEY_ID);
    String stagedKey = StringUtils.isBlank(stagedKeyId) ? null : stagedKeyId;
    String overrideKeyId = IcebergEncryptionCatalogBinding.overrideKeyId(catalogProperties);
    if (overrideKeyId != null) {
      if (overrideKeyId.equals(stagedKey)) {
        return Optional.empty();
      }
      return Optional.of(
          String.format(
              "Staged commit does not carry the catalog's override encryption key: the staged "
                  + "metadata names %s where the catalog overrides every table to '%s'",
              stagedKey == null ? "no key" : "'" + stagedKey + "'", overrideKeyId));
    }
    String defaultKeyId = IcebergEncryptionCatalogBinding.defaultKeyId(catalogProperties);
    if (defaultKeyId == null || stagedKey != null) {
      return Optional.empty();
    }
    return Optional.of(
        String.format(
            "Staged commit carries no encryption key although the catalog binds one: staged "
                + "metadata lost the catalog default key '%s'",
            defaultKeyId));
  }

  /**
   * Returns whether the properties carry any encryption input at all, including the leftover Apache
   * protocol properties the evaluator refuses.
   */
  static boolean hasEncryptionInput(Map<String, String> properties) {
    return properties.containsKey(IcebergConstants.ENCRYPTION_KEY_PROVIDER)
        || properties.containsKey(IcebergConstants.ENCRYPTION_KEY_ID)
        || properties.containsKey(IcebergConstants.ENCRYPTION_KMS_API)
        || properties.containsKey(IcebergConstants.ENCRYPTION_KEY_SOURCE);
  }

  /**
   * Says whether an operation is held to the format version rule for encrypted tables (R7): the
   * operations that name the version of a table that does not exist yet. A registration or an
   * overwrite publishes a file at the version it was written at, which no catalog setting shaped,
   * so R10 does not reach it and this admission checks no floor on the file either.
   */
  static boolean checksRequestedFormatVersion(Operation operation) {
    return operation == Operation.CREATE
        || operation == Operation.STAGE
        || operation == Operation.STAGED_COMMIT;
  }

  /**
   * Decides the format version a new table would land at, whether that satisfies what encryption
   * requires, and what the admission forwards (R2, R3, R4, R6, R9, R10); see {@link
   * #decideFormatVersion}. Encryption contributes a floor, and only when a key applies (R1): a
   * plain table keeps whatever version its catalog gives it, on every operation.
   *
   * @param effective the effective properties from {@link #effectiveProperties}
   * @throws IllegalArgumentException if a format version the catalog declares is malformed
   */
  static FormatVersionOutcome formatVersionOutcome(
      Operation operation, Map<String, String> effective, Map<String, String> catalogProperties) {
    return decideFormatVersion(
        operation, effective, catalogProperties, keyApplies(effective) ? ENCRYPTION : null);
  }

  /**
   * Resolves a format version against a requirement, in the precedence the operation's path
   * actually uses (R2); {@link IcebergFormatVersionResolution.Operation} documents that precedence
   * per path.
   *
   * <p>This is the admission's only resolution of the version: the version that is checked and the
   * version that is forwarded come from the same answer, so the two can never disagree.
   *
   * <p><b>Why the pin is checked first (R10).</b> A {@code table-override.format-version} below the
   * floor cannot be rescued by any value a client sends, because on a create Iceberg applies it
   * last and on the staged paths the same catalog would refuse the identical table on create. So it
   * is answered before the resolution, and its refusal names the override and the only party who
   * can change it.
   *
   * @param operation the mutation being admitted
   * @param requestProperties the request's properties; only {@link IcebergConstants#FORMAT_VERSION}
   *     is read, which the merge never adds, so the effective properties carry the request's value
   * @param catalogProperties the owning catalog's properties, spellings collapsed on read
   * @param requirement the floor a feature imposes, or {@code null} when nothing constrains the
   *     version and therefore no refusal is possible
   * @return what the version resolved to and what to do with it
   * @throws IllegalArgumentException if a format version the catalog declares is malformed: a
   *     deployment fault, where a malformed request value is a {@link FormatVersionOutcome.Deny}
   */
  static FormatVersionOutcome decideFormatVersion(
      Operation operation,
      Map<String, String> requestProperties,
      Map<String, String> catalogProperties,
      @Nullable FormatVersionRequirement requirement) {
    if (requirement == null || !checksRequestedFormatVersion(operation)) {
      return FormatVersionOutcome.NOT_GOVERNED;
    }

    Optional<Integer> override =
        IcebergFormatVersionResolution.tableOverrideFormatVersion(catalogProperties);
    if (override.isPresent() && override.get() < requirement.minimum()) {
      return new FormatVersionOutcome.Deny(
          override.get(), Source.TABLE_OVERRIDE, pinnedCatalogMessage(override.get(), requirement));
    }

    String requested =
        StringUtils.trimToNull(requestProperties.get(IcebergConstants.FORMAT_VERSION));
    if (requested != null && parseFormatVersion(requested) == null) {
      return new FormatVersionOutcome.Deny(
          null, Source.REQUEST, malformedRequestMessage(requested, requirement));
    }

    EffectiveFormatVersion resolved = resolve(operation, requested, catalogProperties);
    if (resolved.version() < requirement.minimum()) {
      return new FormatVersionOutcome.Deny(
          resolved.version(), resolved.source(), belowFloorMessage(resolved, requirement));
    }

    // R9: a catalog that binds a key is rebuilt from its own properties at the publishing commit,
    // so its own declaration has to clear the floor on every path Iceberg builds. This is not the
    // key merge's question: a staged commit inherits no catalog key, yet Iceberg still rebuilds it.
    if (IcebergEncryptionCatalogBinding.bindsKey(catalogProperties)) {
      EffectiveFormatVersion declared =
          IcebergFormatVersionResolution.catalogDeclaredVersion(catalogProperties);
      if (declared.version() < requirement.minimum()) {
        return new FormatVersionOutcome.Deny(
            declared.version(),
            declared.source(),
            catalogBindingMessage(catalogProperties, declared, requirement));
      }
    }

    return requested == null
        ? new FormatVersionOutcome.ForwardWriting(resolved.version(), resolved.source())
        : new FormatVersionOutcome.Forward(resolved.version(), resolved.source());
  }

  /** The one call into {@link IcebergFormatVersionResolution}; see {@link #decideFormatVersion}. */
  private static EffectiveFormatVersion resolve(
      Operation operation, @Nullable String requested, Map<String, String> catalogProperties) {
    return IcebergFormatVersionResolution.effective(
        IcebergFormatVersionResolution.Operation.valueOf(operation.name()),
        catalogProperties,
        requested);
  }

  /**
   * R10's refusal. It offers neither of the remedies below, deliberately: under an override the
   * client's value and the catalog's default both lose, so naming either would name a remedy the
   * reader cannot perform.
   */
  private static String pinnedCatalogMessage(int pinned, FormatVersionRequirement requirement) {
    return String.format(
        "This request cannot be supported in the current catalog configuration: %s is active for "
            + "this table and requires format-version %d or later, but %s=%d pins every table "
            + "Iceberg builds below it. An operator must raise or remove that property",
        requirement.requiredBy(),
        requirement.minimum(),
        IcebergFormatVersionResolution.TABLE_OVERRIDE_FORMAT_VERSION,
        pinned);
  }

  /** The refusal for a version that resolved below the floor, with the remedy its source allows. */
  private static String belowFloorMessage(
      EffectiveFormatVersion resolved, FormatVersionRequirement requirement) {
    if (resolved.source() == Source.REQUEST) {
      // Only the client can fix this one: on every path the request's own value outranks the
      // catalog's default, so a raised default would still lose.
      return String.format(
          "%s is active for this table and requires format-version %d or later, but this request "
              + "asked for format-version %d (from %s). Send format-version=%d in the request",
          requirement.requiredBy(),
          requirement.minimum(),
          resolved.version(),
          resolved.source().token(),
          requirement.minimum());
    }
    return String.format(
        "%s is active for this table and requires format-version %d or later, but no format "
            + "version was requested and this catalog resolves to %d (from %s). Send "
            + "format-version=%d in the request, or ask an operator to set %s=%d on the catalog",
        requirement.requiredBy(),
        requirement.minimum(),
        resolved.version(),
        resolved.source().token(),
        requirement.minimum(),
        IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION,
        requirement.minimum());
  }

  /** The refusal for a request whose {@code format-version} is not a version at all. */
  private static String malformedRequestMessage(
      String requested, FormatVersionRequirement requirement) {
    return String.format(
        "%s is active for this table and requires format-version %d or later, but '%s' (from %s) "
            + "is not a format version. Send format-version=%d in the request",
        requirement.requiredBy(),
        requirement.minimum(),
        requested.trim(),
        Source.REQUEST.token(),
        requirement.minimum());
  }

  /** R9's refusal, which names the bound key rather than a resolution source. */
  private static String catalogBindingMessage(
      Map<String, String> catalogProperties,
      EffectiveFormatVersion declared,
      FormatVersionRequirement requirement) {
    String declaration =
        declared.source() == Source.ICEBERG_DEFAULT
            ? String.format(
                "declares no format version, so Iceberg's default of %d applies",
                declared.version())
            : String.format("declares %s=%d", declared.source().token(), declared.version());
    return String.format(
        "The catalog binds encryption key '%s' but %s; a catalog-bound key requires "
            + "%s=%d or later on the catalog (set it directly or with the gravitino.bypass. prefix)",
        IcebergEncryptionCatalogBinding.boundKeyId(catalogProperties),
        declaration,
        IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION,
        requirement.minimum());
  }

  /**
   * A floor on the Iceberg table format version ({@code minimum}) and the feature that imposed it
   * ({@code requiredBy}), named as a refusal names it.
   */
  record FormatVersionRequirement(int minimum, String requiredBy) {}

  /**
   * What one resolution decided: refuse, forward the version the request named, or forward and
   * write the version the request left out.
   */
  sealed interface FormatVersionOutcome
      permits FormatVersionOutcome.Deny,
          FormatVersionOutcome.Forward,
          FormatVersionOutcome.ForwardWriting,
          FormatVersionOutcome.NotGoverned {

    /** The one instance of {@link NotGoverned}. */
    FormatVersionOutcome NOT_GOVERNED = new NotGoverned();

    /**
     * The version is below the requirement. {@code effective} is {@code null} only when the
     * request's own value is not a version at all; {@code message} names {@code source} and the one
     * party who can change it.
     */
    record Deny(@Nullable Integer effective, Source source, String message)
        implements FormatVersionOutcome {}

    /** The request named a version: forward it untouched. */
    record Forward(int resolved, Source source) implements FormatVersionOutcome {}

    /** The request named none: write {@code resolved} into the forwarded properties (R6). */
    record ForwardWriting(int resolved, Source source) implements FormatVersionOutcome {}

    /** No key applies, or the operation takes the version of an existing metadata file. */
    record NotGoverned() implements FormatVersionOutcome {}
  }

  /**
   * Returns whether a key applies to the request: the effective properties name a key id, whether
   * the request supplied it or the catalog bound it.
   */
  static boolean keyApplies(Map<String, String> effective) {
    return StringUtils.isNotBlank(effective.get(IcebergConstants.ENCRYPTION_KEY_ID));
  }

  /**
   * Assembles the properties an admitted request is forwarded with. Key properties are rebuilt
   * exclusively from the validated key, so raw request input is never forwarded after evaluation
   * and the leftover Apache protocol properties never reach Iceberg.
   *
   * <p>Where a key applies and the request names no format version, the version {@link
   * #decideFormatVersion} resolved is written (R6): not a raise, but the operator's own
   * declaration, which Iceberg would not apply to staged metadata or to a stage-less {@code
   * assert-create} commit, so without the write a keyed table could still be published below
   * version 3. The write belongs to the key: a request without a validated key keeps the version it
   * asked for, or none, because nothing about a plain table requires version 3 and the version is
   * permanent once written (row 15, R7).
   *
   * @param effective the effective properties that were admitted
   * @param validatedKey the key the evaluator validated, or {@code null} when none was
   * @param resolvedFormatVersion the version {@link FormatVersionOutcome.ForwardWriting#resolved()}
   *     carries, or {@code null} when the request named its own or no version rule applies
   * @return an unmodifiable map
   */
  static Map<String, String> admittedProperties(
      Map<String, String> effective,
      @Nullable KmsReference validatedKey,
      @Nullable Integer resolvedFormatVersion) {
    Map<String, String> admitted = new LinkedHashMap<>(effective);
    admitted.remove(IcebergConstants.ENCRYPTION_KMS_API);
    admitted.remove(IcebergConstants.ENCRYPTION_KEY_SOURCE);
    admitted.remove(IcebergConstants.ENCRYPTION_KEY_PROVIDER);
    admitted.remove(IcebergConstants.ENCRYPTION_KEY_ID);
    if (validatedKey != null) {
      if (resolvedFormatVersion != null
          && StringUtils.isBlank(admitted.get(IcebergConstants.FORMAT_VERSION))) {
        admitted.put(IcebergConstants.FORMAT_VERSION, String.valueOf(resolvedFormatVersion));
      }
      admitted.put(IcebergConstants.ENCRYPTION_KEY_PROVIDER, validatedKey.provider());
      admitted.put(IcebergConstants.ENCRYPTION_KEY_ID, validatedKey.keyId());
    }
    return Collections.unmodifiableMap(admitted);
  }

  /**
   * Reads the complete provider key reference out of properties.
   *
   * @param properties effective properties
   * @return the reference, or {@code null} when it is incomplete or malformed
   */
  @Nullable
  static KmsReference providerKey(Map<String, String> properties) {
    String provider = properties.get(IcebergConstants.ENCRYPTION_KEY_PROVIDER);
    String keyId = properties.get(IcebergConstants.ENCRYPTION_KEY_ID);
    if (StringUtils.isAnyBlank(provider, keyId)) {
      return null;
    }
    try {
      return new KmsReference(provider, keyId);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  @Nullable
  private static Integer parseFormatVersion(String value) {
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
