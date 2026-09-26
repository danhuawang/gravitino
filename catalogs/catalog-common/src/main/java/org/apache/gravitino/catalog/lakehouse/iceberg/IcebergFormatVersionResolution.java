/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.catalog.lakehouse.iceberg;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;

/**
 * The Iceberg table format version a request would land at, resolved from Iceberg's own settings on
 * the path the request actually takes.
 *
 * <p>Gravitino owns no format-version property and raises no version of its own: it reads the
 * version Iceberg would use and refuses the request when encryption needs a higher one. Iceberg
 * resolves that version differently per path, and {@link Operation} is the only place that per-path
 * precedence is encoded.
 *
 * <p>The version that is checked must be the version that lands, or a refusal decides nothing. On a
 * staged path an override-first reading admits a request naming version 2 on the strength of an
 * override naming 3, and Iceberg then publishes the table at 2 — carrying a key, with a 200.
 *
 * <p>Catalog properties are read under both spellings exactly as {@link
 * IcebergEncryptionCatalogBinding} reads the encryption key. Every method is a pure function of the
 * map it is handed; whether encryption applies is the caller's question.
 */
public final class IcebergFormatVersionResolution {

  private static final String TABLE_DEFAULT_PREFIX = "table-default.";
  private static final String TABLE_OVERRIDE_PREFIX = "table-override.";
  private static final String CATALOG_BYPASS_PREFIX = "gravitino.bypass.";

  /** The catalog property naming the format version tables created on this catalog default to. */
  public static final String TABLE_DEFAULT_FORMAT_VERSION =
      TABLE_DEFAULT_PREFIX + IcebergConstants.FORMAT_VERSION;

  /** The catalog property naming the format version the catalog forces onto every table. */
  public static final String TABLE_OVERRIDE_FORMAT_VERSION =
      TABLE_OVERRIDE_PREFIX + IcebergConstants.FORMAT_VERSION;

  /** Iceberg's built-in table format version, used when nothing else names one. */
  public static final int ICEBERG_DEFAULT_FORMAT_VERSION = 2;

  /** The lowest Iceberg table format version that can carry an encryption key. */
  public static final int MIN_ENCRYPTED_FORMAT_VERSION = 3;

  /**
   * The path a request takes through Iceberg, which decides whose setting wins. Line citations are
   * against the bundled Iceberg 1.11.0.
   */
  public enum Operation {

    /**
     * An ordinary create. {@code BaseMetastoreCatalogTableBuilder} seeds the table with the
     * catalog's {@code table-default.*} ({@code BaseMetastoreCatalog:153}), layers the request on
     * top ({@code :175-180}) and applies {@code table-override.*} last ({@code :196}), so the
     * override wins over the version the client named.
     */
    CREATE,

    /**
     * A stage-create, the first half of a CTAS. {@code CatalogHandlers.stageTableCreate} ({@code
     * :406-443}) builds the staged metadata from the request's own properties and reads none of the
     * catalog's, so the request wins. The catalog's settings reach a staged table only when
     * Gravitino writes the resolved version into a request that names none, so they rank after it.
     */
    STAGE,

    /**
     * The {@code assert-create} commit that publishes a table, staged or stage-less. {@code
     * CatalogHandlers.create} ({@code :598-611}) builds it from {@code
     * TableMetadata.buildFromEmpty} plus the commit's own updates, so the request wins here too.
     * The commit's version is the highest {@code upgrade-format-version} it carries.
     */
    STAGED_COMMIT,

    /**
     * Registering an existing metadata file. Iceberg reads the version out of the file and consults
     * the catalog for nothing, so neither the override nor the default has any bearing.
     */
    REGISTER,

    /**
     * Overwriting a table with an existing metadata file. As {@link #REGISTER}, the file decides.
     */
    OVERWRITE;

    private boolean versionComesFromTheFile() {
      return this == REGISTER || this == OVERWRITE;
    }

    /**
     * Returns whether Iceberg applies the catalog's override to the metadata this path persists,
     * which is exactly when the override outranks the version the request names.
     */
    private boolean catalogOverrideOutranksTheRequest() {
      return this == CREATE;
    }
  }

  /** Where the effective format version came from. */
  public enum Source {
    /** The {@code format-version} the create or commit request carried. */
    REQUEST("request"),
    /** The catalog's {@code table-override.format-version}, in either spelling. */
    TABLE_OVERRIDE(TABLE_OVERRIDE_FORMAT_VERSION),
    /** The catalog's {@code table-default.format-version}, in either spelling. */
    TABLE_DEFAULT(TABLE_DEFAULT_FORMAT_VERSION),
    /** Iceberg's built-in default, because nothing named a version. */
    ICEBERG_DEFAULT("iceberg-default"),
    /** The registered metadata file's own version, which no catalog setting can change. */
    FILE("file");

    private final String token;

    Source(String token) {
      this.token = token;
    }

    /**
     * Returns the name this source is reported under. For a catalog property it is the plain
     * spelling, so a refusal names what an operator would set.
     *
     * @return the source's token
     */
    public String token() {
      return token;
    }
  }

  /** The format version a request would land at, and the setting that decided it. */
  public static final class EffectiveFormatVersion {

    private final int version;
    private final Source source;

    private EffectiveFormatVersion(int version, Source source) {
      this.version = version;
      this.source = source;
    }

    /**
     * Returns the format version the table would be created at.
     *
     * @return the effective format version
     */
    public int version() {
      return version;
    }

    /**
     * Returns the setting that decided the version.
     *
     * @return the source of the effective format version
     */
    public Source source() {
      return source;
    }

    @Override
    public String toString() {
      return version + " (" + source.token() + ")";
    }
  }

  private IcebergFormatVersionResolution() {}

  /**
   * Resolves the format version the request would land at on the path it takes, in Iceberg's own
   * precedence for that path.
   *
   * <p>Reports what will land, not what ought to. On staged paths Iceberg never applies the
   * override, so it is not reported here; a pin below the encrypted minimum is refused by the
   * caller as an R10 mandate (see {@link #tableOverrideFormatVersion(Map)}).
   *
   * @param operation the path the request takes, which selects the precedence
   * @param catalogProperties the catalog's properties
   * @param namedVersion the version the request names: the request's {@code format-version} on
   *     {@link Operation#CREATE} and {@link Operation#STAGE}, the highest {@code
   *     upgrade-format-version} the commit carries on {@link Operation#STAGED_COMMIT}, and the
   *     version read out of the metadata file on {@link Operation#REGISTER} and {@link
   *     Operation#OVERWRITE}. {@code null} or blank when none is named
   * @return the effective version and the setting that decided it
   * @throws IllegalArgumentException if a value that is present is not a number, or if a metadata
   *     file is registered without a version, which a written file always carries
   */
  public static EffectiveFormatVersion effective(
      Operation operation, Map<String, String> catalogProperties, @Nullable String namedVersion) {
    if (operation.versionComesFromTheFile()) {
      return fromMetadataFile(namedVersion);
    }
    Supplier<Optional<EffectiveFormatVersion>> request = () -> requested(namedVersion);
    Supplier<Optional<EffectiveFormatVersion>> override = () -> overriding(catalogProperties);
    Supplier<Optional<EffectiveFormatVersion>> declared = () -> declared(catalogProperties);
    return operation.catalogOverrideOutranksTheRequest()
        ? firstNamed(override, request, declared)
        : firstNamed(request, override, declared);
  }

  /**
   * Returns the format version the catalog itself declares, and the property that declared it:
   * {@code table-override.format-version} when it is set, otherwise {@code
   * table-default.format-version}, otherwise Iceberg's built-in default.
   *
   * <p>The override is read first for the same reason it is read first on {@link Operation#CREATE}:
   * at the publishing commit Iceberg rebuilds the table from the catalog's properties alone and
   * applies the override last ({@code BaseMetastoreCatalog:241} before {@code :249}), so the
   * override is what the catalog's own placeholder is versioned at.
   *
   * @param catalogProperties the catalog's properties
   * @return the version the catalog declares, and the setting that declared it
   * @throws IllegalArgumentException if a declared value is not a number
   */
  public static EffectiveFormatVersion catalogDeclaredVersion(
      Map<String, String> catalogProperties) {
    Optional<EffectiveFormatVersion> overridden = overriding(catalogProperties);
    if (overridden.isPresent()) {
      return overridden.get();
    }
    return declared(catalogProperties).orElseGet(IcebergFormatVersionResolution::icebergDefault);
  }

  /**
   * Returns the catalog's {@code table-default.format-version}, in either spelling.
   *
   * @param catalogProperties the catalog's properties
   * @return the declared default version, or empty when the catalog declares none
   * @throws IllegalArgumentException if the declared value is not a number
   */
  public static Optional<Integer> tableDefaultFormatVersion(Map<String, String> catalogProperties) {
    return configured(catalogProperties, TABLE_DEFAULT_FORMAT_VERSION);
  }

  /**
   * Returns the catalog's {@code table-override.format-version}, in either spelling.
   *
   * @param catalogProperties the catalog's properties
   * @return the overriding version, or empty when the catalog overrides none
   * @throws IllegalArgumentException if the overriding value is not a number
   */
  public static Optional<Integer> tableOverrideFormatVersion(
      Map<String, String> catalogProperties) {
    return configured(catalogProperties, TABLE_OVERRIDE_FORMAT_VERSION);
  }

  /**
   * Returns whether {@link #catalogDeclaredVersion(Map)} can carry an encryption key. It does not
   * check whether the catalog binds one; the caller gates on that.
   *
   * @param catalogProperties the catalog's properties
   * @return {@code true} when the declared version is at least {@value
   *     #MIN_ENCRYPTED_FORMAT_VERSION}
   * @throws IllegalArgumentException if a declared value is not a number
   */
  public static boolean catalogDeclaresEncryptedVersion(Map<String, String> catalogProperties) {
    return catalogDeclaredVersion(catalogProperties).version() >= MIN_ENCRYPTED_FORMAT_VERSION;
  }

  /** Returns the first candidate that names a version, else Iceberg's built-in default. */
  private static EffectiveFormatVersion firstNamed(
      Supplier<Optional<EffectiveFormatVersion>> first,
      Supplier<Optional<EffectiveFormatVersion>> second,
      Supplier<Optional<EffectiveFormatVersion>> third) {
    Optional<EffectiveFormatVersion> named = first.get();
    if (!named.isPresent()) {
      named = second.get();
    }
    if (!named.isPresent()) {
      named = third.get();
    }
    return named.orElseGet(IcebergFormatVersionResolution::icebergDefault);
  }

  private static Optional<EffectiveFormatVersion> requested(@Nullable String namedVersion) {
    if (StringUtils.isBlank(namedVersion)) {
      return Optional.empty();
    }
    return Optional.of(
        new EffectiveFormatVersion(
            parse(IcebergConstants.FORMAT_VERSION, namedVersion), Source.REQUEST));
  }

  private static Optional<EffectiveFormatVersion> overriding(
      Map<String, String> catalogProperties) {
    return tableOverrideFormatVersion(catalogProperties)
        .map(version -> new EffectiveFormatVersion(version, Source.TABLE_OVERRIDE));
  }

  private static Optional<EffectiveFormatVersion> declared(Map<String, String> catalogProperties) {
    return tableDefaultFormatVersion(catalogProperties)
        .map(version -> new EffectiveFormatVersion(version, Source.TABLE_DEFAULT));
  }

  private static EffectiveFormatVersion icebergDefault() {
    return new EffectiveFormatVersion(ICEBERG_DEFAULT_FORMAT_VERSION, Source.ICEBERG_DEFAULT);
  }

  /**
   * Reads the version a registered metadata file carries. Nothing else is consulted: the file is
   * already written, and no catalog setting re-versions it.
   */
  private static EffectiveFormatVersion fromMetadataFile(@Nullable String fileVersion) {
    if (StringUtils.isBlank(fileVersion)) {
      throw new IllegalArgumentException(
          "No Iceberg table format version was read from the metadata file being registered: "
              + "every written metadata file carries one");
    }
    return new EffectiveFormatVersion(
        parse(IcebergConstants.FORMAT_VERSION, fileVersion), Source.FILE);
  }

  /**
   * Reads a catalog-level format version under both spellings, naming in any failure the spelling
   * the value was actually written under, so the message points at a property the operator set.
   */
  private static Optional<Integer> configured(
      Map<String, String> catalogProperties, String property) {
    String value = IcebergEncryptionCatalogBinding.catalogProperty(catalogProperties, property);
    if (value == null) {
      return Optional.empty();
    }
    String spelling =
        StringUtils.isNotBlank(catalogProperties.get(property))
            ? property
            : CATALOG_BYPASS_PREFIX + property;
    return Optional.of(parse(spelling, value));
  }

  /** Parses a format version as Iceberg's {@code format-version} accepts it. */
  private static int parse(String property, String value) {
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          String.format(
              "Invalid Iceberg table format version '%s' for '%s': expected a number",
              value, property),
          e);
    }
  }
}
