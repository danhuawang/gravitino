/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.catalog.lakehouse.iceberg;

import java.util.Map;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;

/**
 * The encryption key a catalog binds onto the tables created on it, read from the catalog's own
 * properties.
 *
 * <p>This is the single definition of how that binding is spelled. A catalog may carry the key
 * under the plain Iceberg spelling ({@code table-default.encryption.key-id}) or under the {@code
 * gravitino.bypass.} form a Gravitino catalog passes unknown Iceberg properties through, and an
 * override binding wins over a default one. Admission reads the binding to decide a table's key.
 *
 * <p>Every method is a pure function of the map it is handed.
 */
public final class IcebergEncryptionCatalogBinding {

  private static final String TABLE_DEFAULT_PREFIX = "table-default.";
  private static final String TABLE_OVERRIDE_PREFIX = "table-override.";
  private static final String CATALOG_BYPASS_PREFIX = "gravitino.bypass.";

  /** The catalog property naming the key tables created on this catalog default to. */
  public static final String TABLE_DEFAULT_KEY_ID =
      TABLE_DEFAULT_PREFIX + IcebergConstants.ENCRYPTION_KEY_ID;

  /** The catalog property naming the key every table created on this catalog is forced to. */
  public static final String TABLE_OVERRIDE_KEY_ID =
      TABLE_OVERRIDE_PREFIX + IcebergConstants.ENCRYPTION_KEY_ID;

  private IcebergEncryptionCatalogBinding() {}

  /**
   * Returns whether the catalog binds an encryption key onto the tables created on it.
   *
   * @param catalogProperties the catalog's properties
   * @return {@code true} when a default or an override key is bound
   */
  public static boolean bindsKey(Map<String, String> catalogProperties) {
    return boundKeyId(catalogProperties) != null;
  }

  /**
   * Returns the key id a create on this catalog is given when the request names none: the override
   * key, or the default key.
   *
   * @param catalogProperties the catalog's properties
   * @return the bound key id, or {@code null} when the catalog binds none
   */
  @Nullable
  public static String boundKeyId(Map<String, String> catalogProperties) {
    String overrideKeyId = overrideKeyId(catalogProperties);
    return overrideKeyId != null ? overrideKeyId : defaultKeyId(catalogProperties);
  }

  /**
   * Returns the key id the catalog forces onto every table created on it.
   *
   * @param catalogProperties the catalog's properties
   * @return the override key id, or {@code null} when the catalog overrides no key
   */
  @Nullable
  public static String overrideKeyId(Map<String, String> catalogProperties) {
    return catalogProperty(catalogProperties, TABLE_OVERRIDE_KEY_ID);
  }

  /**
   * Returns the key id a table created on this catalog is given when the request names none.
   *
   * @param catalogProperties the catalog's properties
   * @return the default key id, or {@code null} when the catalog defaults to no key
   */
  @Nullable
  public static String defaultKeyId(Map<String, String> catalogProperties) {
    return catalogProperty(catalogProperties, TABLE_DEFAULT_KEY_ID);
  }

  /**
   * Reads a catalog-level table property, accepting the plain Iceberg spelling and the {@code
   * gravitino.bypass.} form a Gravitino catalog carries it under, in that order. A blank value is
   * absent rather than a setting: a catalog that spells the property out empty must not shadow the
   * other spelling, nor bind a table to an empty key id.
   *
   * <p>Package-visible because {@link IcebergFormatVersionResolution} reads {@code
   * table-default.format-version} and {@code table-override.format-version} by the same rule, and
   * the two must not disagree about which spelling wins.
   *
   * @param catalogProperties the catalog's properties
   * @param property the plain Iceberg spelling of the property
   * @return the value under the plain spelling, else under the bypass spelling, else {@code null}
   */
  @Nullable
  static String catalogProperty(Map<String, String> catalogProperties, String property) {
    String value = catalogProperties.get(property);
    if (StringUtils.isNotBlank(value)) {
      return value;
    }
    String bypassValue = catalogProperties.get(CATALOG_BYPASS_PREFIX + property);
    return StringUtils.isNotBlank(bypassValue) ? bypassValue : null;
  }
}
