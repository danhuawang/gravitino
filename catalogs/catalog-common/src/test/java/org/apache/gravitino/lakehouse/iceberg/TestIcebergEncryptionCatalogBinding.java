/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.lakehouse.iceberg;

import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergEncryptionCatalogBinding;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Pins the one reading of a catalog's encryption binding: which spelling is accepted, which key
 * wins, and when a catalog counts as binding a key at all. Governance and the Iceberg catalog
 * configuration both decide from this answer, so they cannot disagree about it.
 */
public class TestIcebergEncryptionCatalogBinding {

  private static final String PLAIN_DEFAULT = "table-default." + IcebergConstants.ENCRYPTION_KEY_ID;
  private static final String PLAIN_OVERRIDE =
      "table-override." + IcebergConstants.ENCRYPTION_KEY_ID;
  private static final String BYPASS_DEFAULT = "gravitino.bypass." + PLAIN_DEFAULT;
  private static final String BYPASS_OVERRIDE = "gravitino.bypass." + PLAIN_OVERRIDE;

  @Test
  void testPropertyNamesAreBuiltFromTheIcebergSpellings() {
    Assertions.assertEquals(PLAIN_DEFAULT, IcebergEncryptionCatalogBinding.TABLE_DEFAULT_KEY_ID);
    Assertions.assertEquals(PLAIN_OVERRIDE, IcebergEncryptionCatalogBinding.TABLE_OVERRIDE_KEY_ID);
  }

  @Test
  void testEitherSpellingBindsAKeyWithThePlainOneFirst() {
    Map<String, String> catalog = new HashMap<>();
    catalog.put(PLAIN_DEFAULT, "iceberg-spelled-default-key");
    catalog.put(BYPASS_DEFAULT, "bypass-spelled-default-key");

    Assertions.assertEquals(
        "iceberg-spelled-default-key", IcebergEncryptionCatalogBinding.defaultKeyId(catalog));

    catalog.remove(PLAIN_DEFAULT);
    Assertions.assertEquals(
        "bypass-spelled-default-key", IcebergEncryptionCatalogBinding.defaultKeyId(catalog));

    catalog.clear();
    catalog.put(PLAIN_OVERRIDE, "iceberg-spelled-override-key");
    catalog.put(BYPASS_OVERRIDE, "bypass-spelled-override-key");

    Assertions.assertEquals(
        "iceberg-spelled-override-key", IcebergEncryptionCatalogBinding.overrideKeyId(catalog));

    catalog.remove(PLAIN_OVERRIDE);
    Assertions.assertEquals(
        "bypass-spelled-override-key", IcebergEncryptionCatalogBinding.overrideKeyId(catalog));
  }

  @Test
  void testTheBoundKeyIsTheOverrideThenTheDefault() {
    Map<String, String> catalog = new HashMap<>();

    Assertions.assertNull(IcebergEncryptionCatalogBinding.boundKeyId(catalog));
    Assertions.assertFalse(IcebergEncryptionCatalogBinding.bindsKey(catalog));

    catalog.put(PLAIN_DEFAULT, "default-key");
    Assertions.assertEquals("default-key", IcebergEncryptionCatalogBinding.boundKeyId(catalog));
    Assertions.assertTrue(IcebergEncryptionCatalogBinding.bindsKey(catalog));

    catalog.put(BYPASS_OVERRIDE, "override-key");
    Assertions.assertEquals("override-key", IcebergEncryptionCatalogBinding.boundKeyId(catalog));
  }

  /**
   * A property spelled out empty is not a key. Treating it as present would let one spelling shadow
   * the other and would bind a table to an empty key id.
   */
  @Test
  void testABlankValueIsNoBindingAndDoesNotShadowTheOtherSpelling() {
    Map<String, String> catalog = new HashMap<>();
    catalog.put(PLAIN_OVERRIDE, "");
    catalog.put(BYPASS_OVERRIDE, "bypass-spelled-override-key");

    Assertions.assertEquals(
        "bypass-spelled-override-key", IcebergEncryptionCatalogBinding.overrideKeyId(catalog));

    catalog.clear();
    catalog.put(PLAIN_OVERRIDE, " ");
    catalog.put(PLAIN_DEFAULT, "");
    catalog.put(BYPASS_DEFAULT, "");

    Assertions.assertNull(IcebergEncryptionCatalogBinding.boundKeyId(catalog));
    Assertions.assertFalse(IcebergEncryptionCatalogBinding.bindsKey(catalog));
  }
}
