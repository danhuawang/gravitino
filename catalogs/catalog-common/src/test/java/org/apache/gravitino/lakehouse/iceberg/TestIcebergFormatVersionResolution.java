/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.lakehouse.iceberg;

import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergFormatVersionResolution;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergFormatVersionResolution.EffectiveFormatVersion;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergFormatVersionResolution.Operation;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Pins the one reading of the Iceberg table format version a request would land at: which path is
 * being taken, whose setting wins on it, which spelling is accepted, and when the catalog's own
 * declaration is high enough to carry an encryption key. Admission refuses below that version
 * rather than raising it, so every refusal is only as right as this resolution — and only as right
 * as its choice of path, because a version resolved for the wrong path is checked against a table
 * that never lands.
 */
public class TestIcebergFormatVersionResolution {

  private static final String PLAIN_DEFAULT = "table-default.format-version";
  private static final String PLAIN_OVERRIDE = "table-override.format-version";
  private static final String BYPASS_PREFIX = "gravitino.bypass.";

  /** The parameter source's spelling for a property that is present but written blank. */
  private static final String BLANK = "~";

  /**
   * Iceberg's precedence, which is not one precedence. On {@code CREATE} the catalog's override is
   * applied last and wins; on the staged paths Iceberg builds from the request's own properties, so
   * the request wins and the catalog's settings apply only where the request named nothing; on
   * {@code REGISTER} and {@code OVERWRITE} the file already carries a version and the catalog is
   * not read at all. A catalog column reads {@code plain|bypass}; a column with no bar is the plain
   * spelling alone, an empty side of a bar is that spelling left unset, and {@code ~} is a property
   * written out blank.
   */
  @ParameterizedTest(name = "{0}: override={1} request={2} default={3} -> {4} from {5}")
  @CsvSource({
    // CREATE: the override wins even over a version the client asked for (R4, Appendix D row 4).
    "CREATE, 2, 3, , 2, table-override.format-version",
    // The same inputs on the staged paths land at the request's version, because Iceberg never
    // applies the override to what it persists there (R2, Appendix D rows 5 and 13f).
    "STAGE, 2, 3, , 3, request",
    "STAGED_COMMIT, 2, 3, , 3, request",
    // The mirror of row 13f: an override above a request the staged path still lands at. Read
    // override-first this admits on the strength of a 3 that never reaches the table.
    "STAGE, 3, 2, , 2, request",
    "STAGED_COMMIT, 3, 2, , 2, request",
    // The request wins over the catalog's default on every create path.
    "CREATE, , 4, 3, 4, request",
    "STAGE, , 4, 3, 4, request",
    // Where the request names nothing, the override still answers on every create path: Gravitino
    // carries it to the staged table itself (Appendix D row 2).
    "CREATE, 3, , 4, 3, table-override.format-version",
    "STAGE, 3, , 4, 3, table-override.format-version",
    "STAGED_COMMIT, 3, , , 3, table-override.format-version",
    // The catalog's default wins over Iceberg's built-in 2.
    "CREATE, , , 3, 3, table-default.format-version",
    "STAGE, , , 3, 3, table-default.format-version",
    // Nothing named a version.
    "CREATE, , , , 2, iceberg-default",
    "STAGE, , , , 2, iceberg-default",
    "STAGED_COMMIT, , , , 2, iceberg-default",
    // REGISTER and OVERWRITE: the file decides, and neither catalog property has any bearing.
    "REGISTER, 4, 2, 4, 2, file",
    "OVERWRITE, 2, 3, 2, 3, file",
    // The plain spelling wins over the bypass spelling, for both properties.
    "CREATE, 3|4, , , 3, table-override.format-version",
    "CREATE, , , 3|4, 3, table-default.format-version",
    // The bypass spelling is read when it is the only one set.
    "CREATE, , , |4, 4, table-default.format-version",
    "STAGE, |4, , , 4, table-override.format-version",
    // A blank plain value is absent and does not shadow the bypass spelling.
    "CREATE, ~|4, , , 4, table-override.format-version",
    // A blank request is a request that names no version, on either precedence.
    "CREATE, , ~, 3, 3, table-default.format-version",
    "STAGE, , ~, 3, 3, table-default.format-version"
  })
  void testEffectiveVersionFollowsIcebergPrecedenceForTheOperation(
      Operation operation,
      String override,
      String request,
      String tableDefault,
      int expectedVersion,
      String expectedToken) {
    Map<String, String> catalog = new HashMap<>();
    declare(catalog, PLAIN_OVERRIDE, override);
    declare(catalog, PLAIN_DEFAULT, tableDefault);

    EffectiveFormatVersion effective =
        IcebergFormatVersionResolution.effective(
            operation, catalog, request == null ? null : written(request));

    Assertions.assertEquals(expectedVersion, effective.version());
    Assertions.assertEquals(expectedToken, effective.source().token());
  }

  /**
   * The defect this resolution exists to close. With {@code table-override.format-version=3} and a
   * request naming 2, {@code CREATE} lands at the override's 3 and a staged commit lands at the
   * request's 2. One reading cannot be right for both: an override-first reading of the staged
   * commit admits a keyed table that publishes at version 2 with a 200.
   *
   * <p>This is the assertion that fails if the operation is ever ignored again.
   */
  @Test
  void testCreateAndStagedPathsDisagreeWhenBothTheRequestAndTheOverrideNameAVersion() {
    Map<String, String> pinnedAtThree = Map.of(PLAIN_OVERRIDE, "3");

    EffectiveFormatVersion onCreate =
        IcebergFormatVersionResolution.effective(Operation.CREATE, pinnedAtThree, "2");
    EffectiveFormatVersion onStage =
        IcebergFormatVersionResolution.effective(Operation.STAGE, pinnedAtThree, "2");
    EffectiveFormatVersion onStagedCommit =
        IcebergFormatVersionResolution.effective(Operation.STAGED_COMMIT, pinnedAtThree, "2");

    Assertions.assertEquals(3, onCreate.version());
    Assertions.assertEquals(PLAIN_OVERRIDE, onCreate.source().token());

    Assertions.assertEquals(2, onStage.version());
    Assertions.assertEquals("request", onStage.source().token());
    Assertions.assertEquals(2, onStagedCommit.version());
    Assertions.assertEquals("request", onStagedCommit.source().token());

    Assertions.assertNotEquals(
        onCreate.version(),
        onStagedCommit.version(),
        "the create and staged paths must resolve differently, or the operation is being ignored");
  }

  /**
   * The message points at a property the operator can find, which means the spelling they wrote,
   * not the canonical one.
   */
  @Test
  void testNonNumericIsRefusedNamingTheSpellingAsWritten() {
    Map<String, String> plain = Map.of(PLAIN_DEFAULT, "three");
    IllegalArgumentException plainFailure =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> IcebergFormatVersionResolution.tableDefaultFormatVersion(plain));
    Assertions.assertTrue(
        plainFailure.getMessage().contains(PLAIN_DEFAULT), plainFailure.getMessage());
    Assertions.assertFalse(
        plainFailure.getMessage().contains(BYPASS_PREFIX), plainFailure.getMessage());

    Map<String, String> bypass = Map.of(BYPASS_PREFIX + PLAIN_OVERRIDE, "latest");
    IllegalArgumentException bypassFailure =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> IcebergFormatVersionResolution.tableOverrideFormatVersion(bypass));
    Assertions.assertTrue(
        bypassFailure.getMessage().contains(BYPASS_PREFIX + PLAIN_OVERRIDE),
        bypassFailure.getMessage());

    IllegalArgumentException requestFailure =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> IcebergFormatVersionResolution.effective(Operation.CREATE, Map.of(), "v3"));
    Assertions.assertTrue(
        requestFailure.getMessage().contains("format-version"), requestFailure.getMessage());
  }

  /**
   * A non-numeric catalog value is a deployment fault on every path, not only on {@code CREATE}.
   */
  @ParameterizedTest
  @EnumSource(
      value = Operation.class,
      names = {"CREATE", "STAGE", "STAGED_COMMIT"})
  void testNonNumericCatalogValueIsRefusedOnEveryCreatePath(Operation operation) {
    Map<String, String> catalog = Map.of(PLAIN_OVERRIDE, "three");

    IllegalArgumentException failure =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> IcebergFormatVersionResolution.effective(operation, catalog, null));

    Assertions.assertTrue(failure.getMessage().contains(PLAIN_OVERRIDE), failure.getMessage());
  }

  /**
   * A registered file always carries a version. Being handed none is a fault in the caller that
   * read the file, not a request that resolves to Iceberg's default.
   */
  @ParameterizedTest
  @EnumSource(
      value = Operation.class,
      names = {"REGISTER", "OVERWRITE"})
  void testRegisteringWithoutAFileVersionIsRefusedRatherThanDefaulted(Operation operation) {
    Map<String, String> catalog = Map.of(PLAIN_DEFAULT, "3");

    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> IcebergFormatVersionResolution.effective(operation, catalog, null));
  }

  /**
   * The R9 predicate: a catalog that binds a key must itself declare a version that can carry one.
   * The version the catalog declares is its override when set, otherwise its default — the same
   * order Iceberg applies to the placeholder it publishes through. It asks nothing about the key,
   * because the caller gates on the binding.
   */
  @Test
  void testCatalogDeclaresEncryptedVersionOnlyAtThreeOrAbove() {
    Assertions.assertFalse(
        IcebergFormatVersionResolution.catalogDeclaresEncryptedVersion(Map.of()));
    Assertions.assertFalse(
        IcebergFormatVersionResolution.catalogDeclaresEncryptedVersion(Map.of(PLAIN_DEFAULT, "2")));
    Assertions.assertTrue(
        IcebergFormatVersionResolution.catalogDeclaresEncryptedVersion(Map.of(PLAIN_DEFAULT, "3")));
    Assertions.assertTrue(
        IcebergFormatVersionResolution.catalogDeclaresEncryptedVersion(Map.of(PLAIN_DEFAULT, "4")));
    Assertions.assertTrue(
        IcebergFormatVersionResolution.catalogDeclaresEncryptedVersion(
            Map.of(BYPASS_PREFIX + PLAIN_DEFAULT, "3")));
    Assertions.assertTrue(
        IcebergFormatVersionResolution.catalogDeclaresEncryptedVersion(
            Map.of(PLAIN_OVERRIDE, "3")));
  }

  /**
   * The override is the catalog's declaration when it is set, whichever way the default points. A
   * catalog pinned at 2 declares 2 even while its default says 3, and the refusal must name the
   * override, because only an operator can raise it.
   */
  @Test
  void testCatalogDeclarationReadsTheOverrideBeforeTheDefault() {
    Map<String, String> pinnedLowDefaultHigh = Map.of(PLAIN_OVERRIDE, "2", PLAIN_DEFAULT, "3");
    Map<String, String> pinnedHighDefaultLow = Map.of(PLAIN_OVERRIDE, "3", PLAIN_DEFAULT, "2");

    Assertions.assertFalse(
        IcebergFormatVersionResolution.catalogDeclaresEncryptedVersion(pinnedLowDefaultHigh));
    Assertions.assertEquals(
        2, IcebergFormatVersionResolution.catalogDeclaredVersion(pinnedLowDefaultHigh).version());
    Assertions.assertEquals(
        PLAIN_OVERRIDE,
        IcebergFormatVersionResolution.catalogDeclaredVersion(pinnedLowDefaultHigh)
            .source()
            .token());

    Assertions.assertTrue(
        IcebergFormatVersionResolution.catalogDeclaresEncryptedVersion(pinnedHighDefaultLow));
    Assertions.assertEquals(
        3, IcebergFormatVersionResolution.catalogDeclaredVersion(pinnedHighDefaultLow).version());

    Assertions.assertEquals(
        PLAIN_DEFAULT,
        IcebergFormatVersionResolution.catalogDeclaredVersion(Map.of(PLAIN_DEFAULT, "3"))
            .source()
            .token());
    Assertions.assertEquals(
        "iceberg-default",
        IcebergFormatVersionResolution.catalogDeclaredVersion(Map.of()).source().token());
  }

  /** Writes a {@code plain|bypass} column of the parameter source into the catalog map. */
  private static void declare(Map<String, String> catalog, String property, String spec) {
    if (spec == null) {
      return;
    }
    String[] spellings = spec.split("\\|", -1);
    put(catalog, property, spellings[0]);
    if (spellings.length > 1) {
      put(catalog, BYPASS_PREFIX + property, spellings[1]);
    }
  }

  private static void put(Map<String, String> catalog, String property, String spelling) {
    if (!spelling.isEmpty()) {
      catalog.put(property, written(spelling));
    }
  }

  private static String written(String spelling) {
    return BLANK.equals(spelling) ? " " : spelling;
  }
}
