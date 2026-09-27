/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.encryption;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergEncryptionCatalogBinding;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergFormatVersionResolution;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergFormatVersionResolution.Source;
import org.apache.gravitino.connector.BaseCatalog;
import org.apache.gravitino.encryption.EncryptionAdmissionService.Operation;
import org.apache.gravitino.encryption.EncryptionPropertyRules.FormatVersionOutcome;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Pins the pure property rules an admission applies: which key a request ends up with, how a
 * key-id-only reference is completed, what a staged commit must carry, and what an admitted request
 * is forwarded with. No policy resolver, evaluator or KMS takes part.
 */
public class TestEncryptionPropertyRules {

  private static final String SOURCE = "openbao";
  private static final String KEY_ID = "customer-pii-v1";
  private static final String PLAIN_DEFAULT = "table-default." + IcebergConstants.ENCRYPTION_KEY_ID;
  private static final String PLAIN_OVERRIDE =
      "table-override." + IcebergConstants.ENCRYPTION_KEY_ID;
  private static final String BYPASS_DEFAULT = "gravitino.bypass." + PLAIN_DEFAULT;
  private static final String BYPASS_OVERRIDE = "gravitino.bypass." + PLAIN_OVERRIDE;
  private static final KmsReference VALIDATED_KEY = new KmsReference(SOURCE, KEY_ID);

  @Test
  void testCreateKeyPrecedenceIsOverrideThenRequestThenDefault() {
    Map<String, String> catalog = new HashMap<>();
    catalog.put(BYPASS_DEFAULT, "catalog-default-key");

    Assertions.assertEquals("catalog-default-key", createKey(catalog, Collections.emptyMap()));
    Assertions.assertEquals(KEY_ID, createKey(catalog, keyId(KEY_ID)));

    catalog.put(BYPASS_OVERRIDE, "catalog-override-key");

    Assertions.assertEquals("catalog-override-key", createKey(catalog, keyId(KEY_ID)));
  }

  @Test
  void testOnlyCreateAndStageInheritCatalogKeys() {
    Map<String, String> catalog =
        Map.of(BYPASS_DEFAULT, "catalog-default-key", BYPASS_OVERRIDE, "catalog-override-key");

    Assertions.assertEquals(
        "catalog-override-key",
        EncryptionPropertyRules.effectiveProperties(
                Operation.STAGE, Collections.emptyMap(), catalog, SOURCE)
            .get(IcebergConstants.ENCRYPTION_KEY_ID));
    for (Operation operation :
        new Operation[] {Operation.STAGED_COMMIT, Operation.REGISTER, Operation.OVERWRITE}) {
      Assertions.assertTrue(
          EncryptionPropertyRules.effectiveProperties(
                  operation, Collections.emptyMap(), catalog, SOURCE)
              .isEmpty(),
          "" + operation);
      Assertions.assertEquals(
          KEY_ID,
          EncryptionPropertyRules.effectiveProperties(operation, keyId(KEY_ID), catalog, SOURCE)
              .get(IcebergConstants.ENCRYPTION_KEY_ID));
    }
  }

  @Test
  void testCatalogKeyIsReadFromEitherSpellingWithThePlainOneFirst() {
    Map<String, String> catalog = new HashMap<>();
    catalog.put(PLAIN_DEFAULT, "iceberg-spelled-default-key");
    catalog.put(BYPASS_DEFAULT, "bypass-spelled-default-key");
    catalog.put(PLAIN_OVERRIDE, "iceberg-spelled-override-key");
    catalog.put(BYPASS_OVERRIDE, "bypass-spelled-override-key");

    Assertions.assertEquals("iceberg-spelled-override-key", createKey(catalog, keyId(KEY_ID)));

    catalog.remove(PLAIN_OVERRIDE);
    Assertions.assertEquals("bypass-spelled-override-key", createKey(catalog, keyId(KEY_ID)));

    catalog.remove(BYPASS_OVERRIDE);
    Assertions.assertEquals(
        "iceberg-spelled-default-key", createKey(catalog, Collections.emptyMap()));

    catalog.remove(PLAIN_DEFAULT);
    Assertions.assertEquals(
        "bypass-spelled-default-key", createKey(catalog, Collections.emptyMap()));
  }

  /**
   * A property spelled out empty is not a key. Treating it as present would let the Iceberg
   * spelling shadow the bypass form a Gravitino catalog actually carries the key under, and bind
   * the table to an empty key id.
   */
  @Test
  void testBlankCatalogKeyIsAbsentAndDoesNotShadowTheOtherSpelling() {
    Map<String, String> catalog = new HashMap<>();
    catalog.put(PLAIN_OVERRIDE, "");
    catalog.put(BYPASS_OVERRIDE, "bypass-spelled-override-key");

    Assertions.assertEquals("bypass-spelled-override-key", createKey(catalog, keyId(KEY_ID)));

    catalog.clear();
    catalog.put(PLAIN_OVERRIDE, " ");
    catalog.put(PLAIN_DEFAULT, "");
    catalog.put(BYPASS_DEFAULT, "");

    Assertions.assertEquals(KEY_ID, createKey(catalog, keyId(KEY_ID)));
    Map<String, String> keyless =
        EncryptionPropertyRules.effectiveProperties(
            Operation.CREATE, Collections.emptyMap(), catalog, SOURCE);
    Assertions.assertFalse(
        EncryptionPropertyRules.hasEncryptionInput(keyless),
        "a blank catalog key is no encryption input at all");
  }

  @Test
  void testKeyIdOnlyReferenceIsCompletedFromTheCatalogKmsSource() {
    Map<String, String> completed =
        EncryptionPropertyRules.effectiveProperties(
            Operation.REGISTER, keyId(KEY_ID), Collections.emptyMap(), SOURCE);

    Assertions.assertEquals(SOURCE, completed.get(IcebergConstants.ENCRYPTION_KEY_PROVIDER));

    Map<String, String> explicitProvider = new HashMap<>(keyId(KEY_ID));
    explicitProvider.put(IcebergConstants.ENCRYPTION_KEY_PROVIDER, "aws");

    Assertions.assertEquals(
        "aws",
        EncryptionPropertyRules.effectiveProperties(
                Operation.CREATE, explicitProvider, Collections.emptyMap(), SOURCE)
            .get(IcebergConstants.ENCRYPTION_KEY_PROVIDER),
        "an explicit provider is kept, and refused later if it names another KMS");
    Assertions.assertFalse(
        EncryptionPropertyRules.effectiveProperties(
                Operation.CREATE, keyId(KEY_ID), Collections.emptyMap(), null)
            .containsKey(IcebergConstants.ENCRYPTION_KEY_PROVIDER),
        "no KMS source, nothing to complete from");
  }

  /**
   * The staged-commit key check binds only a staged commit, and only where the catalog actually
   * binds a key: a blank catalog key is no binding. The violations themselves are pinned through
   * the service ({@code TestEncryptionAdmissionService}), where their audit facts live.
   */
  @Test
  void testKeyViolationIsOnlyCheckedForAStagedCommit() {
    Map<String, String> catalog = Map.of(BYPASS_OVERRIDE, "catalog-override-key");

    for (Operation operation :
        new Operation[] {
          Operation.CREATE, Operation.STAGE, Operation.REGISTER, Operation.OVERWRITE
        }) {
      Assertions.assertEquals(
          Optional.empty(),
          EncryptionPropertyRules.stagedCommitKeyViolation(
              operation, catalog, Collections.emptyMap()),
          "" + operation);
    }
    Assertions.assertEquals(
        Optional.empty(), stagedViolation(Collections.emptyMap(), Collections.emptyMap()));
    Assertions.assertEquals(
        Optional.empty(), stagedViolation(Map.of(BYPASS_DEFAULT, " "), Collections.emptyMap()));
  }

  @Test
  void testAdmittedPropertiesRebuildTheKeyFromTheValidatedReferenceAndDropLegacyInput() {
    // C2: the two rejected Apache spellings are recognised here and nowhere else, so without
    // pinning the literals they are held by compilation alone and a rename would silently change
    // which properties get stripped on the wire.
    Assertions.assertEquals("encryption.kms-api", IcebergConstants.ENCRYPTION_KMS_API);
    Assertions.assertEquals("encryption.key-source", IcebergConstants.ENCRYPTION_KEY_SOURCE);

    Map<String, String> effective = new HashMap<>();
    effective.put(IcebergConstants.ENCRYPTION_KEY_PROVIDER, SOURCE);
    effective.put(IcebergConstants.ENCRYPTION_KEY_ID, "raw-input");
    effective.put(IcebergConstants.ENCRYPTION_KMS_API, "leftover");
    effective.put(IcebergConstants.ENCRYPTION_KEY_SOURCE, "leftover");
    effective.put("unrelated", "preserved");

    Map<String, String> admitted =
        EncryptionPropertyRules.admittedProperties(effective, new KmsReference(SOURCE, KEY_ID), 3);

    Assertions.assertEquals(
        Map.of(
            IcebergConstants.ENCRYPTION_KEY_PROVIDER,
            SOURCE,
            IcebergConstants.ENCRYPTION_KEY_ID,
            KEY_ID,
            IcebergConstants.FORMAT_VERSION,
            "3",
            "unrelated",
            "preserved"),
        admitted);
    Assertions.assertThrows(UnsupportedOperationException.class, () -> admitted.put("k", "v"));

    Map<String, String> keyless = EncryptionPropertyRules.admittedProperties(effective, null, 3);
    Assertions.assertEquals(
        Map.of("unrelated", "preserved"),
        keyless,
        "without a validated key neither the key nor the version it needs is forwarded");
  }

  /**
   * R6: an admission that validated a key writes the version R2 resolved where the request names
   * none, and keeps a named one exactly as it arrived. Nothing is raised and nothing is lowered,
   * and an operation that resolves no version writes none.
   */
  @Test
  void testAKeyedAdmissionWritesTheResolvedVersionAndKeepsANamedOne() {
    Assertions.assertEquals("3", admittedFormatVersion(null, VALIDATED_KEY, 3));
    Assertions.assertEquals("3", admittedFormatVersion(" ", VALIDATED_KEY, 3));
    Assertions.assertEquals("4", admittedFormatVersion(null, VALIDATED_KEY, 4));
    Assertions.assertEquals("3", admittedFormatVersion("3", VALIDATED_KEY, 4));
    Assertions.assertEquals("4", admittedFormatVersion("4", VALIDATED_KEY, 4));
    Assertions.assertNull(admittedFormatVersion(null, VALIDATED_KEY, null));
  }

  /**
   * V6 and lifecycle row 15: the default belongs to the key, not to the admission. An admission
   * that validated no key — a plain create on an unkeyed catalog, or one under a {@code report}
   * policy that wanted a key and did not get one — keeps the version the request asked for, and
   * names no version where the request named none, whatever the catalog default is. A table's
   * format version is permanent, so stamping v3 onto a plain table would strand its readers for a
   * key it never received.
   */
  @Test
  void testAKeylessAdmissionKeepsTheRequestedFormatVersion() {
    Assertions.assertNull(admittedFormatVersion(null, null, 3));
    Assertions.assertEquals("2", admittedFormatVersion("2", null, 3));
    Assertions.assertEquals("v4", admittedFormatVersion("v4", null, 3));
    Assertions.assertEquals("4", admittedFormatVersion("4", null, 3));
  }

  /**
   * R3 and R4: when a key applies to a create, the version it is held to is the effective one, and
   * below 3 it is refused with a message naming the setting that decided it and the property an
   * operator would change. An omitted version is not exempt: it resolves to Iceberg's own default
   * of 2 and is refused too, rather than raised.
   */
  @Test
  void testAKeyedCreateBelowVersionThreeIsRefusedOnItsEffectiveVersion() {
    Map<String, String> undeclared = Map.of();
    Assertions.assertEquals(Optional.empty(), versionViolation(KEY_ID, "3", undeclared));
    Assertions.assertEquals(Optional.empty(), versionViolation(KEY_ID, " 4 ", undeclared));
    Assertions.assertEquals(Optional.empty(), versionViolation(KEY_ID, null, catalogDefault("3")));

    String omitted = versionViolation(KEY_ID, null, undeclared).orElseThrow();
    Assertions.assertTrue(omitted.contains("encryption is active"), omitted);
    Assertions.assertTrue(omitted.contains("(from iceberg-default)"), omitted);
    Assertions.assertTrue(
        omitted.contains(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION + "=3"),
        omitted);

    String belowThree = versionViolation(KEY_ID, "2", undeclared).orElseThrow();
    Assertions.assertTrue(belowThree.contains("(from request)"), belowThree);
    Assertions.assertTrue(
        versionViolation(KEY_ID, null, catalogDefault("2"))
            .orElseThrow()
            .contains("(from " + IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION + ")"),
        "a catalog default below 3 is named as the source");
    Assertions.assertTrue(versionViolation(KEY_ID, "1", undeclared).isPresent());
    Assertions.assertTrue(versionViolation(KEY_ID, "v4", undeclared).isPresent());

    // R4 and R10: the catalog's override defeats even a request that asks for a version encryption
    // could use, and the refusal is the pinned-catalog one, because no request value can rescue it.
    String overridden = versionViolation(KEY_ID, "3", catalogOverride("2")).orElseThrow();
    Assertions.assertTrue(
        overridden.contains(IcebergFormatVersionResolution.TABLE_OVERRIDE_FORMAT_VERSION + "=2"),
        overridden);
  }

  /**
   * V2 applies only when a key applies (row 15, V6): a plain create may ask for any version its
   * catalog allows, and the catalog's own cap refuses the rest (V8, row 5c), not admission.
   */
  @Test
  void testAPlainCreateHasNoVersionRule() {
    Assertions.assertEquals(Optional.empty(), versionViolation(null, "2", Map.of()));
    Assertions.assertEquals(Optional.empty(), versionViolation(null, "1", Map.of()));
    Assertions.assertEquals(Optional.empty(), versionViolation(null, null, catalogDefault("2")));
    Assertions.assertEquals(Optional.empty(), versionViolation(" ", "2", Map.of()));
  }

  /**
   * R7: every operation that names a table which does not exist yet is held to the rule, a
   * registration and an overwrite on the version of their metadata file included, because Iceberg
   * registers a file that pairs a key with version 2 without complaint. The staged commit's version
   * reaches the rule as {@code format-version}, folded from its {@code upgrade-format-version}
   * update. Only a create, a stage and a staged commit resolve their version through the catalog
   * and get it written back (R2, R6); a registration is held to its file's own version, and its
   * refusal names the file rather than a request property. A keyed registration that reaches the
   * rule with no file version is a caller fault, not a decision: every written metadata file
   * carries one, so the resolver throws rather than refusing, and the fault is never audited as the
   * registrant's.
   */
  @Test
  void testEveryOperationOnANewTableIsCheckedForItsFormatVersion() {
    Map<String, String> effective =
        Map.of(IcebergConstants.ENCRYPTION_KEY_ID, KEY_ID, IcebergConstants.FORMAT_VERSION, "2");
    for (Operation operation : Operation.values()) {
      boolean resolves = EncryptionPropertyRules.resolvesVersionThroughCatalog(operation);
      Assertions.assertEquals(
          operation != Operation.REGISTER && operation != Operation.OVERWRITE,
          resolves,
          "" + operation);
      Source source = resolves ? Source.REQUEST : Source.FILE;
      FormatVersionOutcome.Deny deny =
          Assertions.assertInstanceOf(
              FormatVersionOutcome.Deny.class,
              EncryptionPropertyRules.formatVersionOutcome(operation, effective, Map.of()),
              "" + operation);
      Assertions.assertEquals(Integer.valueOf(2), deny.effective(), "" + operation);
      Assertions.assertEquals(source, deny.source(), "" + operation);
      Assertions.assertTrue(
          deny.message().contains("(from " + source.token() + ")"), deny.message());
      if (!resolves) {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> outcome(operation, KEY_ID, null, Map.of()),
            "" + operation);
      }
    }
  }

  /**
   * R10: a catalog whose {@code table-override.format-version} sits below 3 cannot host an
   * encrypted table on any path Iceberg builds — a create, a stage and the commit that publishes a
   * staged create — whatever the request names. The refusal reports the pin rather than the version
   * that would have landed: the override's own value, and {@code table-override.format-version} as
   * the source. Under a pin neither remedy works, so neither is offered (Appendix D): not the
   * client's {@code format-version} and not the operator's {@code table-default.format-version},
   * which Iceberg never consults while the override stands.
   */
  @Test
  void testACatalogPinnedBelowVersionThreeRefusesEveryPathIcebergBuilds() {
    Map<String, String> pinned = catalogOverride("2");
    for (Operation operation :
        new Operation[] {Operation.CREATE, Operation.STAGE, Operation.STAGED_COMMIT}) {
      for (String requested : new String[] {null, "2", "3", "4", "nonsense"}) {
        String cell = operation + " requesting " + requested;
        FormatVersionOutcome.Deny deny =
            Assertions.assertInstanceOf(
                FormatVersionOutcome.Deny.class,
                outcome(operation, KEY_ID, requested, pinned),
                cell);
        Assertions.assertEquals(2, deny.effective(), cell);
        Assertions.assertEquals(Source.TABLE_OVERRIDE, deny.source(), cell);
        String message = deny.message();
        Assertions.assertTrue(
            message.contains(IcebergFormatVersionResolution.TABLE_OVERRIDE_FORMAT_VERSION + "=2"),
            message);
        Assertions.assertTrue(
            message.contains("An operator must raise or remove that property"), message);
        Assertions.assertFalse(
            message.contains(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION + "=3"),
            message);
        Assertions.assertFalse(message.contains("Send format-version=3"), message);
      }
    }
  }

  /**
   * R10 stops at the operations Iceberg builds; R7 does not. A registration and an overwrite
   * publish a metadata file that already exists at its own version; Iceberg does not consult the
   * catalog when it registers one, so the override never had any bearing on how that file was
   * written and refusing on its strength would deny a well-formed version 3 table. The floor still
   * applies, on the file's own version: a keyed version 2 file is refused as a file whatever the
   * override says, an override of 4 included, and a version 3 file is forwarded as it is, never
   * written over. The refusal offers neither remedy a resolved version would, because nothing the
   * registrant sends and nothing an operator sets re-versions a written file: it names the one
   * thing that works, a file written at version 3 or later.
   */
  @Test
  void testARegistrationIsHeldToTheFloorButNotToThePin() {
    for (Operation operation : new Operation[] {Operation.REGISTER, Operation.OVERWRITE}) {
      for (Map<String, String> catalog : List.of(catalogOverride("2"), catalogOverride("4"))) {
        String cell = operation + " under " + catalog;
        FormatVersionOutcome.Forward atThree =
            Assertions.assertInstanceOf(
                FormatVersionOutcome.Forward.class, outcome(operation, KEY_ID, "3", catalog), cell);
        Assertions.assertEquals(3, atThree.resolved(), cell);
        Assertions.assertEquals(Source.FILE, atThree.source(), cell);

        String message =
            Assertions.assertInstanceOf(
                    FormatVersionOutcome.Deny.class, outcome(operation, KEY_ID, "2", catalog), cell)
                .message();
        Assertions.assertTrue(
            message.contains("register a file written at format-version 3"), message);
        Assertions.assertFalse(
            message.contains(IcebergFormatVersionResolution.TABLE_OVERRIDE_FORMAT_VERSION),
            message);
        Assertions.assertFalse(message.contains("Send format-version=3"), message);
        Assertions.assertFalse(
            message.contains(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION + "=3"),
            message);
      }
    }
  }

  /** A pin at or above the floor is not a pin: it is simply the version the table lands at. */
  @Test
  void testAnOverrideAtOrAboveThreeIsNotAPin() {
    for (String version : new String[] {"3", "4"}) {
      Assertions.assertEquals(
          Optional.empty(), versionViolation(KEY_ID, null, catalogOverride(version)));
      Assertions.assertEquals(
          Optional.empty(), versionViolation(KEY_ID, "3", catalogOverride(version)));
    }
  }

  /**
   * A pin only binds where a key applies (R1). A plain table on a pinned catalog is Iceberg's own
   * business and passes through untouched.
   */
  @Test
  void testAPinnedCatalogDoesNotRefuseAPlainTable() {
    Assertions.assertSame(
        FormatVersionOutcome.NOT_GOVERNED,
        outcome(Operation.CREATE, null, "2", catalogOverride("2")));
    Assertions.assertSame(
        FormatVersionOutcome.NOT_GOVERNED,
        outcome(Operation.STAGE, null, null, catalogOverride("2")));
  }

  /**
   * R2, Appendix D rows 4 and 5: the precedence is the path's, not one rule for all of them. On a
   * create Iceberg applies the catalog's override last and the client's version is replaced; on a
   * stage and on a stage-less {@code assert-create} commit Iceberg builds what it persists from the
   * request's own properties and never reads the override into it, so the request wins. The version
   * checked is the version that lands, on both.
   */
  @Test
  void testTheResolvedVersionFollowsThePathsOwnPrecedence() {
    Map<String, String> overrideFour = catalogOverride("4");

    FormatVersionOutcome.Forward created =
        Assertions.assertInstanceOf(
            FormatVersionOutcome.Forward.class,
            outcome(Operation.CREATE, KEY_ID, "3", overrideFour));
    Assertions.assertEquals(4, created.resolved());
    Assertions.assertEquals(Source.TABLE_OVERRIDE, created.source());

    for (Operation operation : new Operation[] {Operation.STAGE, Operation.STAGED_COMMIT}) {
      FormatVersionOutcome.Forward staged =
          Assertions.assertInstanceOf(
              FormatVersionOutcome.Forward.class,
              outcome(operation, KEY_ID, "3", overrideFour),
              "" + operation);
      Assertions.assertEquals(3, staged.resolved(), "" + operation);
      Assertions.assertEquals(Source.REQUEST, staged.source(), "" + operation);
    }

    // Rows 1 and 2: where the request names nothing the override is what lands on every path,
    // because Gravitino is the one who carries it there (R6).
    for (Operation operation :
        new Operation[] {Operation.CREATE, Operation.STAGE, Operation.STAGED_COMMIT}) {
      FormatVersionOutcome.ForwardWriting written =
          Assertions.assertInstanceOf(
              FormatVersionOutcome.ForwardWriting.class,
              outcome(operation, KEY_ID, null, overrideFour),
              "" + operation);
      Assertions.assertEquals(4, written.resolved(), "" + operation);
      Assertions.assertEquals(Source.TABLE_OVERRIDE, written.source(), "" + operation);
    }
  }

  /**
   * R6 through the outcome type: a request that names a version is {@code Forward} and is never
   * written over, a request that names none is {@code ForwardWriting}. The distinction is the rule,
   * not a property-map accident.
   */
  @Test
  void testOnlyARequestThatNamesNoVersionIsWrittenOne() {
    Assertions.assertInstanceOf(
        FormatVersionOutcome.ForwardWriting.class,
        outcome(Operation.CREATE, KEY_ID, null, catalogDefault("3")));
    Assertions.assertInstanceOf(
        FormatVersionOutcome.ForwardWriting.class,
        outcome(Operation.CREATE, KEY_ID, "  ", catalogDefault("3")));
    Assertions.assertInstanceOf(
        FormatVersionOutcome.Forward.class,
        outcome(Operation.CREATE, KEY_ID, "4", catalogDefault("3")));
  }

  /**
   * Appendix D's refusal-message table for the two remedies that do work. A version the request
   * named can only be fixed by the client: on every path the request's own value outranks the
   * catalog's default, so offering an operator the default there would name a property that would
   * still lose. A version nobody requested can be fixed by either party, and the message says so.
   */
  @Test
  void testEachRefusalOffersOnlyTheRemedyItsSourceAllows() {
    String fromRequest = versionViolation(KEY_ID, "2", Map.of()).orElseThrow();
    Assertions.assertTrue(
        fromRequest.contains("Send format-version=3 in the request"), fromRequest);
    Assertions.assertFalse(
        fromRequest.contains(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION + "=3"),
        fromRequest);

    for (Map<String, String> catalog : List.of(Map.<String, String>of(), catalogDefault("2"))) {
      String fromCatalog = versionViolation(KEY_ID, null, catalog).orElseThrow();
      Assertions.assertTrue(
          fromCatalog.contains("Send format-version=3 in the request"), fromCatalog);
      Assertions.assertTrue(
          fromCatalog.contains(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION + "=3"),
          fromCatalog);
    }
  }

  /**
   * R9 reads the catalog's <em>effective</em> declaration, not {@code table-default.format-version}
   * alone. The override is what Iceberg applies last to the placeholder the publishing commit is
   * checked against, so a catalog that overrides to 3 has declared 3 whatever its default says, and
   * a catalog-bound key is admitted there.
   */
  @Test
  void testTheCatalogBindingRuleReadsTheOverrideBeforeTheDefault() {
    // The request names 3, so the resolved version clears the floor and R9 is what is left to say.
    Map<String, String> declaresNothing = new HashMap<>();
    declaresNothing.put(PLAIN_DEFAULT, "catalog-bound-key");
    Assertions.assertTrue(
        versionViolation("catalog-bound-key", "3", declaresNothing)
            .orElseThrow()
            .contains("binds encryption key"));

    Map<String, String> overridesToThree = new HashMap<>(declaresNothing);
    overridesToThree.put(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION, "2");
    overridesToThree.put(IcebergFormatVersionResolution.TABLE_OVERRIDE_FORMAT_VERSION, "3");
    FormatVersionOutcome.ForwardWriting admitted =
        Assertions.assertInstanceOf(
            FormatVersionOutcome.ForwardWriting.class,
            EncryptionPropertyRules.formatVersionOutcome(
                Operation.CREATE, keyId("catalog-bound-key"), overridesToThree),
            "an override of 3 is the declaration Iceberg applies, whatever the default says");
    Assertions.assertEquals(3, admitted.resolved());

    // And a catalog that overrides below 3 is R10's, not R9's: the pin is the thing to report.
    Map<String, String> overridesToTwo = new HashMap<>(declaresNothing);
    overridesToTwo.put(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION, "3");
    overridesToTwo.put(IcebergFormatVersionResolution.TABLE_OVERRIDE_FORMAT_VERSION, "2");
    FormatVersionOutcome.Deny pinned =
        Assertions.assertInstanceOf(
            FormatVersionOutcome.Deny.class,
            EncryptionPropertyRules.formatVersionOutcome(
                Operation.CREATE, keyId("catalog-bound-key"), overridesToTwo));
    Assertions.assertEquals(Source.TABLE_OVERRIDE, pinned.source());
    Assertions.assertFalse(pinned.message().contains("binds encryption key"), pinned.message());
  }

  /**
   * V5: the {@code gravitino.bypass.} spelling this rule set reads a catalog key under is the
   * prefix Gravitino actually passes unknown catalog properties through, {@link
   * BaseCatalog#CATALOG_BYPASS_PREFIX}. {@link IcebergEncryptionCatalogBinding} spells the prefix
   * out as its own literal, so the two are pinned together here rather than assumed equal.
   */
  @Test
  void testTheBypassKeySpellingIsGravitinosOwnCatalogBypassPrefix() {
    Assertions.assertEquals(BaseCatalog.CATALOG_BYPASS_PREFIX + PLAIN_DEFAULT, BYPASS_DEFAULT);
    Assertions.assertEquals(BaseCatalog.CATALOG_BYPASS_PREFIX + PLAIN_OVERRIDE, BYPASS_OVERRIDE);

    Map<String, String> catalog =
        Collections.singletonMap(
            BaseCatalog.CATALOG_BYPASS_PREFIX
                + IcebergEncryptionCatalogBinding.TABLE_DEFAULT_KEY_ID,
            "catalog-default-key");

    Assertions.assertTrue(IcebergEncryptionCatalogBinding.bindsKey(catalog));
    Assertions.assertEquals("catalog-default-key", createKey(catalog, Collections.emptyMap()));
  }

  @Test
  void testProviderKeyIsNullUnlessTheReferenceIsComplete() {
    Assertions.assertEquals(
        new KmsReference(SOURCE, KEY_ID),
        EncryptionPropertyRules.providerKey(
            Map.of(
                IcebergConstants.ENCRYPTION_KEY_PROVIDER,
                SOURCE,
                IcebergConstants.ENCRYPTION_KEY_ID,
                KEY_ID)));
    Assertions.assertNull(EncryptionPropertyRules.providerKey(keyId(KEY_ID)));
    Assertions.assertNull(
        EncryptionPropertyRules.providerKey(
            Map.of(
                IcebergConstants.ENCRYPTION_KEY_PROVIDER,
                " ",
                IcebergConstants.ENCRYPTION_KEY_ID,
                KEY_ID)));
  }

  @Test
  void testEncryptionInputIncludesLegacyProtocolProperties() {
    Assertions.assertFalse(EncryptionPropertyRules.hasEncryptionInput(Map.of("owner", "x")));
    for (String property :
        new String[] {
          IcebergConstants.ENCRYPTION_KEY_PROVIDER,
          IcebergConstants.ENCRYPTION_KEY_ID,
          IcebergConstants.ENCRYPTION_KMS_API,
          IcebergConstants.ENCRYPTION_KEY_SOURCE
        }) {
      Assertions.assertTrue(
          EncryptionPropertyRules.hasEncryptionInput(Map.of(property, "x")), property);
    }
  }

  private static String createKey(
      Map<String, String> catalogProperties, Map<String, String> requested) {
    return EncryptionPropertyRules.effectiveProperties(
            Operation.CREATE, requested, catalogProperties, SOURCE)
        .get(IcebergConstants.ENCRYPTION_KEY_ID);
  }

  private static Optional<String> stagedViolation(
      Map<String, String> catalogProperties, Map<String, String> requested) {
    Map<String, String> effective =
        EncryptionPropertyRules.effectiveProperties(
            Operation.STAGED_COMMIT, requested, catalogProperties, SOURCE);
    return EncryptionPropertyRules.stagedCommitKeyViolation(
        Operation.STAGED_COMMIT, catalogProperties, effective);
  }

  private static String admittedFormatVersion(
      String requested, KmsReference validatedKey, Integer resolvedFormatVersion) {
    Map<String, String> effective = new HashMap<>();
    if (requested != null) {
      effective.put(IcebergConstants.FORMAT_VERSION, requested);
    }
    return EncryptionPropertyRules.admittedProperties(
            effective, validatedKey, resolvedFormatVersion)
        .get(IcebergConstants.FORMAT_VERSION);
  }

  private static Optional<String> versionViolation(
      String keyId, String requested, Map<String, String> catalogProperties) {
    return refusal(outcome(Operation.CREATE, keyId, requested, catalogProperties));
  }

  /** The one resolution, over a request carrying at most a key and a version. */
  private static FormatVersionOutcome outcome(
      Operation operation, String keyId, String requested, Map<String, String> catalogProperties) {
    Map<String, String> effective = new HashMap<>();
    if (keyId != null) {
      effective.put(IcebergConstants.ENCRYPTION_KEY_ID, keyId);
    }
    if (requested != null) {
      effective.put(IcebergConstants.FORMAT_VERSION, requested);
    }
    return EncryptionPropertyRules.formatVersionOutcome(operation, effective, catalogProperties);
  }

  private static Optional<String> refusal(FormatVersionOutcome outcome) {
    return outcome instanceof FormatVersionOutcome.Deny deny
        ? Optional.of(deny.message())
        : Optional.empty();
  }

  private static Map<String, String> catalogOverride(String version) {
    return Map.of(IcebergFormatVersionResolution.TABLE_OVERRIDE_FORMAT_VERSION, version);
  }

  private static Map<String, String> catalogDefault(String version) {
    return Map.of(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION, version);
  }

  private static Map<String, String> keyId(String keyId) {
    return Collections.singletonMap(IcebergConstants.ENCRYPTION_KEY_ID, keyId);
  }
}
