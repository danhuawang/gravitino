/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.iceberg.common;

import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergEncryptionCatalogBinding;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergFormatVersionResolution;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Pins what Iceberg's own {@code table-default.} and {@code table-override.} semantics do to
 * creates, commits and registers on a keyed catalog: the facts R2, R4, R7 and A.5 rely on.
 *
 * <p>The catalog properties are written by hand here, because Gravitino declares no format version
 * of its own: it reads the operator's and refuses a request Iceberg would land below the version
 * encryption needs. The format version is read from the table metadata, because Iceberg strips
 * {@code format-version} from the persisted properties as a reserved property.
 */
public class TestKeyedCatalogFormatVersion {

  private static final String KEY_ID = "catalog-default-key";
  private static final Namespace NAMESPACE = Namespace.of("schema");
  private static final Schema SCHEMA =
      new Schema(Types.NestedField.required(1, "id", Types.LongType.get()));

  /**
   * A.5 and matrix row 8a: a keyed catalog that declares no format version builds its creates at
   * Iceberg's default of 2, where Iceberg itself refuses the key. That refusal is what R3 turns
   * into an admission refusal naming {@code table-default.format-version}. When the operator
   * declares 3, the same create lands at 3 under the bound key.
   */
  @Test
  void testAKeyedCatalogWithoutADeclaredVersionBuildsTheCreateAtTwoAndIcebergRefusesTheKey() {
    InMemoryCatalog undeclared = catalog(keyedWithoutDeclaredVersion());

    IllegalArgumentException refusal =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                undeclared
                    .buildTable(TableIdentifier.of(NAMESPACE, "keyed_undeclared"), SCHEMA)
                    .create());
    Assertions.assertTrue(
        refusal.getMessage().contains("Invalid properties for v2"), refusal.getMessage());

    InMemoryCatalog declared = catalog(keyedProperties());
    Table created =
        declared.buildTable(TableIdentifier.of(NAMESPACE, "keyed_create"), SCHEMA).create();

    Assertions.assertEquals(3, formatVersion(created));
    Assertions.assertEquals(KEY_ID, created.properties().get(IcebergConstants.ENCRYPTION_KEY_ID));
  }

  /**
   * Matrix row 18 and V4: binding a key on a catalog changes creates only. A table that was created
   * plain at version 2 before the binding still commits, at version 2, and is never given the
   * catalog's key — table defaults are read when a table is built, not when it is committed to.
   */
  @Test
  void testAnOrdinaryCommitOnAPlainVersionTwoTableKeepsItPlainAtVersionTwo() {
    InMemoryCatalog catalog = catalog(unkeyedProperties());
    TableIdentifier table = TableIdentifier.of(NAMESPACE, "plain_before_the_binding");
    Assertions.assertEquals(2, formatVersion(catalog.buildTable(table, SCHEMA).create()));

    // The catalog later binds a key: same catalog, same tables, new properties.
    catalog.initialize("backend", keyedProperties());
    Table loaded = catalog.loadTable(table);
    loaded.updateProperties().set("owner", "analytics").commit();

    Table committed = catalog.loadTable(table);
    Assertions.assertEquals(2, formatVersion(committed));
    Assertions.assertEquals("analytics", committed.properties().get("owner"));
    Assertions.assertFalse(
        committed.properties().containsKey(IcebergConstants.ENCRYPTION_KEY_ID),
        "a commit never inherits the catalog key");
    // The binding did take effect: a create on the same catalog is encrypted at version 3.
    Assertions.assertEquals(
        3,
        formatVersion(
            catalog.buildTable(TableIdentifier.of(NAMESPACE, "created_after"), SCHEMA).create()));
  }

  /**
   * Matrix row 25: a register takes the metadata file it was handed. A version 2 plain file stays
   * version 2 and plain when it is registered into a keyed catalog, because registration reads the
   * file rather than building a table from the catalog's defaults.
   */
  @Test
  void testRegisteringAPlainVersionTwoFileIntoAKeyedCatalogKeepsItPlainAtVersionTwo() {
    InMemoryCatalog source = catalog(unkeyedProperties());
    TableIdentifier sourceTable = TableIdentifier.of(NAMESPACE, "plain_file");
    Table plain = source.buildTable(sourceTable, SCHEMA).create();
    String metadataFileLocation =
        ((HasTableOperations) plain).operations().current().metadataFileLocation();

    InMemoryCatalog keyed = catalog(keyedProperties());
    Assertions.assertEquals(
        3,
        formatVersion(keyed.buildTable(TableIdentifier.of(NAMESPACE, "created"), SCHEMA).create()),
        "the target catalog is keyed, so its own creates are at version 3");
    Table registered =
        keyed.registerTable(
            TableIdentifier.of(NAMESPACE, "registered_plain"), metadataFileLocation);

    Assertions.assertEquals(2, formatVersion(registered));
    Assertions.assertFalse(
        registered.properties().containsKey(IcebergConstants.ENCRYPTION_KEY_ID),
        "a register never inherits the catalog key");
  }

  /**
   * Matrix row 12: a {@code table-override.} version defeats the table default, because Iceberg
   * applies a catalog's overrides last — after its table defaults and after the request's own
   * properties. That is why the override is step 1 of R2's precedence and a first-class input to
   * admission: {@code table-override.format-version=2} on a keyed catalog would put every create
   * back at version 2 with the key present, so Gravitino refuses such a request in admission,
   * before Iceberg is ever asked. This pins what Iceberg does when it is asked anyway.
   */
  @Test
  void testATableOverrideFormatVersionDefeatsTheVersionTheBindingDeclares() {
    Map<String, String> properties = keyedProperties();
    properties.put(IcebergFormatVersionResolution.TABLE_OVERRIDE_FORMAT_VERSION, "2");
    InMemoryCatalog catalog = catalog(properties);

    IllegalArgumentException refusal =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> catalog.buildTable(TableIdentifier.of(NAMESPACE, "overridden"), SCHEMA).create());

    Assertions.assertTrue(
        refusal.getMessage().contains("Invalid properties for v2"), refusal.getMessage());
  }

  private static InMemoryCatalog catalog(Map<String, String> properties) {
    InMemoryCatalog catalog = new InMemoryCatalog();
    catalog.initialize("backend", properties);
    catalog.createNamespace(NAMESPACE);
    return catalog;
  }

  /** A keyed catalog whose operator declared the format version encryption needs. */
  private static Map<String, String> keyedProperties() {
    Map<String, String> properties = keyedWithoutDeclaredVersion();
    properties.put(IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION, "3");
    return properties;
  }

  /** A keyed catalog whose operator declared no format version at all. */
  private static Map<String, String> keyedWithoutDeclaredVersion() {
    Map<String, String> properties = unkeyedProperties();
    properties.put(IcebergEncryptionCatalogBinding.TABLE_DEFAULT_KEY_ID, KEY_ID);
    return properties;
  }

  private static Map<String, String> unkeyedProperties() {
    return backendProperties(new HashMap<>());
  }

  /** The Iceberg catalog properties a Gravitino catalog with these properties is loaded with. */
  private static Map<String, String> backendProperties(Map<String, String> catalogProperties) {
    Map<String, String> properties = new HashMap<>(catalogProperties);
    properties.put(CatalogProperties.WAREHOUSE_LOCATION, "memory://warehouse");
    return properties;
  }

  private static int formatVersion(Table table) {
    return ((HasTableOperations) table).operations().current().formatVersion();
  }
}
