/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.iceberg.common.integrity;

import com.google.common.collect.ImmutableMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.gravitino.exceptions.MetadataIntegrityException;
import org.apache.gravitino.iceberg.common.integrity.IcebergMetadataIntegrity.TrustedMetadata;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Tests backend-neutral Iceberg metadata hash derivation and verification. */
public class TestIcebergMetadataIntegrity {

  private static final TableIdentifier IDENTIFIER =
      TableIdentifier.of(Namespace.of("ns"), "encrypted");
  private static final Schema SCHEMA =
      new Schema(Types.NestedField.required(1, "id", Types.IntegerType.get()));

  @Test
  void testMetadataHashUsesIcebergTableMetadataSerialization() throws Exception {
    TableMetadata metadata = metadataWithKey("kms-key-1");
    byte[] expectedHash =
        MessageDigest.getInstance("SHA-256")
            .digest(TableMetadataParser.toJson(metadata).getBytes(StandardCharsets.UTF_8));

    Assertions.assertEquals(
        Base64.getEncoder().encodeToString(expectedHash),
        IcebergMetadataIntegrity.metadataHash(metadata));
  }

  @Test
  void testDeriveTrustedMetadata() {
    TableMetadata metadata = metadataWithKey("kms-key-1");
    TrustedMetadata trustedMetadata = IcebergMetadataIntegrity.derive(metadata);

    Assertions.assertEquals("kms-key-1", trustedMetadata.keyId());
    Assertions.assertEquals(
        IcebergMetadataIntegrity.metadataHash(metadata), trustedMetadata.metadataHash());

    TrustedMetadata unencrypted = IcebergMetadataIntegrity.derive(metadataWithKey(null));
    Assertions.assertNull(unencrypted.keyId());
    Assertions.assertNull(unencrypted.metadataHash());
  }

  @Test
  void testDeriveRejectsBlankKey() {
    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> IcebergMetadataIntegrity.derive(metadataWithKey(" ")));

    Assertions.assertTrue(exception.getMessage().contains(TableProperties.ENCRYPTION_TABLE_KEY));
  }

  @Test
  void testVerifyAcceptsMatchingAndUnencryptedMetadata() {
    TableMetadata encrypted = metadataWithKey("kms-key-1");
    Assertions.assertDoesNotThrow(
        () ->
            IcebergMetadataIntegrity.verify(
                IDENTIFIER, IcebergMetadataIntegrity.derive(encrypted), encrypted));

    TableMetadata unencrypted = metadataWithKey(null);
    Assertions.assertDoesNotThrow(
        () ->
            IcebergMetadataIntegrity.verify(
                IDENTIFIER, TrustedMetadata.of(null, null), unencrypted));
  }

  @Test
  void testVerifyRejectsUnenrolledEncryptedMetadata() {
    MetadataIntegrityException exception =
        Assertions.assertThrows(
            MetadataIntegrityException.class,
            () ->
                IcebergMetadataIntegrity.verify(
                    IDENTIFIER, TrustedMetadata.of(null, null), metadataWithKey("kms-key-1")));

    Assertions.assertTrue(exception.getMessage().contains("not enrolled"));
  }

  @Test
  void testVerifyRejectsKeyMismatchAndInvalidTrustedState() {
    Assertions.assertThrows(
        MetadataIntegrityException.class,
        () ->
            IcebergMetadataIntegrity.verify(
                IDENTIFIER,
                TrustedMetadata.of("other-key", "unused"),
                metadataWithKey("kms-key-1")));
    Assertions.assertThrows(
        MetadataIntegrityException.class,
        () ->
            IcebergMetadataIntegrity.verify(
                IDENTIFIER, TrustedMetadata.of(null, "unexpected-hash"), metadataWithKey(null)));
    Assertions.assertThrows(
        MetadataIntegrityException.class,
        () ->
            IcebergMetadataIntegrity.verify(
                IDENTIFIER, TrustedMetadata.of("kms-key-1", null), metadataWithKey("kms-key-1")));
    Assertions.assertThrows(
        MetadataIntegrityException.class,
        () ->
            IcebergMetadataIntegrity.verify(
                IDENTIFIER, TrustedMetadata.of("kms-key-1", "%%%"), metadataWithKey("kms-key-1")));
  }

  @Test
  void testVerifyRejectsMetadataHashMismatch() {
    String differentHash = Base64.getEncoder().encodeToString(new byte[32]);

    Assertions.assertThrows(
        MetadataIntegrityException.class,
        () ->
            IcebergMetadataIntegrity.verify(
                IDENTIFIER,
                TrustedMetadata.of("kms-key-1", differentHash),
                metadataWithKey("kms-key-1")));
  }

  private static TableMetadata metadataWithKey(@Nullable String keyId) {
    Map<String, String> properties =
        keyId == null
            ? Collections.singletonMap(TableProperties.FORMAT_VERSION, "3")
            : ImmutableMap.of(
                TableProperties.FORMAT_VERSION, "3", TableProperties.ENCRYPTION_TABLE_KEY, keyId);
    return TableMetadata.newTableMetadata(
        SCHEMA, PartitionSpec.unpartitioned(), "file:/warehouse/ns/encrypted", properties);
  }
}
