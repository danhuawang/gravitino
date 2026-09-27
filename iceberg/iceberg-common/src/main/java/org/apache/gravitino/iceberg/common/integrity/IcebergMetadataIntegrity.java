/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.iceberg.common.integrity;

import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;
import javax.annotation.Nullable;
import org.apache.gravitino.exceptions.EncryptionKeyRepointException;
import org.apache.gravitino.exceptions.MetadataIntegrityException;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.util.HashWriter;
import org.apache.iceberg.util.JsonUtil;

/** Derives and verifies trusted integrity data for Iceberg table metadata. */
public final class IcebergMetadataIntegrity {

  /** Digest name persisted with the trusted metadata pointer. v1 follows Iceberg Hive SHA-256. */
  public static final String HASH_ALGORITHM = "SHA-256";

  /** Decoded length of a v1 {@code metadata_hash}: SHA-256 is 32 bytes. */
  public static final int METADATA_HASH_BYTES = 32;

  private IcebergMetadataIntegrity() {}

  /**
   * Decodes a v1 {@code metadata_hash}: standard Base64 of a SHA-256 digest.
   *
   * @param metadataHash the persisted digest
   * @return the 32 decoded bytes
   * @throws IllegalArgumentException if the value is blank, not standard Base64, or the wrong
   *     length
   */
  public static byte[] decodeMetadataHash(String metadataHash) {
    if (metadataHash == null || metadataHash.trim().isEmpty()) {
      throw new IllegalArgumentException("metadata hash cannot be blank");
    }
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(metadataHash);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("metadata hash must be standard Base64", e);
    }
    if (decoded.length != METADATA_HASH_BYTES) {
      throw new IllegalArgumentException(
          String.format(
              "metadata hash must decode to %d bytes, got %d",
              METADATA_HASH_BYTES, decoded.length));
    }
    return decoded;
  }

  /**
   * Derives the trusted key identifier and metadata hash for table metadata.
   *
   * @param metadata the Iceberg table metadata
   * @return the trusted metadata, or an empty value for an unencrypted table
   */
  public static TrustedMetadata derive(TableMetadata metadata) {
    String keyId = metadata.properties().get(TableProperties.ENCRYPTION_TABLE_KEY);
    if (keyId == null) {
      return TrustedMetadata.of(null, null);
    }
    if (keyId.trim().isEmpty()) {
      throw new IllegalArgumentException(
          String.format(
              "Iceberg table property '%s' cannot be blank", TableProperties.ENCRYPTION_TABLE_KEY));
    }
    return TrustedMetadata.of(keyId, metadataHash(metadata));
  }

  /**
   * Verifies table metadata against trusted catalog state.
   *
   * @param identifier the table identifier
   * @param trustedMetadata the trusted key identifier and metadata hash
   * @param metadata the Iceberg table metadata to verify
   * @throws MetadataIntegrityException if the metadata does not match trusted state
   */
  public static void verify(
      TableIdentifier identifier, TrustedMetadata trustedMetadata, TableMetadata metadata) {
    String metadataKeyId = metadata.properties().get(TableProperties.ENCRYPTION_TABLE_KEY);
    if (metadataKeyId != null
        && trustedMetadata.keyId() == null
        && trustedMetadata.metadataHash() == null) {
      throw new MetadataIntegrityException(
          "Encrypted table %s is not enrolled in metadata integrity; only encrypted tables "
              + "created after integrity is enabled are supported",
          identifier);
    }
    if (!Objects.equals(trustedMetadata.keyId(), metadataKeyId)) {
      throw new MetadataIntegrityException(
          "Trusted table key does not match Iceberg metadata for table %s", identifier);
    }

    if (trustedMetadata.keyId() == null) {
      if (trustedMetadata.metadataHash() != null) {
        throw new MetadataIntegrityException(
            "Metadata hash exists without a trusted table key for table %s", identifier);
      }
      return;
    }

    if (trustedMetadata.keyId().trim().isEmpty()) {
      throw new MetadataIntegrityException(
          "Trusted table key is blank for encrypted table %s", identifier);
    }

    if (trustedMetadata.metadataHash() == null || trustedMetadata.metadataHash().trim().isEmpty()) {
      throw new MetadataIntegrityException(
          "Trusted metadata hash is missing for encrypted table %s", identifier);
    }

    byte[] expectedHash;
    try {
      expectedHash = decodeMetadataHash(trustedMetadata.metadataHash());
    } catch (IllegalArgumentException e) {
      throw new MetadataIntegrityException(
          e, "Trusted metadata hash is malformed for encrypted table %s", identifier);
    }

    if (!MessageDigest.isEqual(expectedHash, metadataHashBytes(metadata))) {
      throw new MetadataIntegrityException(
          "Iceberg metadata integrity verification failed for table %s", identifier);
    }
  }

  /**
   * Requires that a repoint keeps the table's encryption key binding unchanged.
   *
   * <p>Backends call this before a register-overwrite because Iceberg has no native overwrite and
   * therefore no native check on this path. Re-registering a metadata file must not enroll an
   * unencrypted table, rekey an encrypted one, or strip its key; those are governed changes that
   * happen through admission, never through a repoint.
   *
   * @param identifier the table identifier
   * @param current the table's current metadata
   * @param target the metadata to publish
   * @throws EncryptionKeyRepointException if the target carries a different encryption key than the
   *     current metadata, including a key appearing or disappearing
   */
  public static void requireSameEncryptionKey(
      TableIdentifier identifier, TableMetadata current, TableMetadata target) {
    String currentKeyId = current.properties().get(TableProperties.ENCRYPTION_TABLE_KEY);
    String targetKeyId = target.properties().get(TableProperties.ENCRYPTION_TABLE_KEY);
    if (!Objects.equals(currentKeyId, targetKeyId)) {
      throw new EncryptionKeyRepointException(
          "Cannot change the encryption key of table %s by re-registering it", identifier);
    }
  }

  /**
   * Computes the Base64-encoded SHA-256 hash of Iceberg's table metadata serialization.
   *
   * @param metadata the Iceberg table metadata
   * @return the Base64-encoded hash
   */
  public static String metadataHash(TableMetadata metadata) {
    return Base64.getEncoder().encodeToString(metadataHashBytes(metadata));
  }

  private static byte[] metadataHashBytes(TableMetadata metadata) {
    try (HashWriter hashWriter = new HashWriter(HASH_ALGORITHM, StandardCharsets.UTF_8)) {
      JsonGenerator generator = JsonUtil.factory().createGenerator(hashWriter);
      TableMetadataParser.toJson(metadata, generator);
      generator.flush();
      return hashWriter.getHash();
    } catch (NoSuchAlgorithmException | IOException e) {
      throw new IllegalStateException("Unable to hash Iceberg table metadata", e);
    }
  }

  /** Trusted key identifier and metadata hash stored by a catalog backend. */
  public static final class TrustedMetadata {
    @Nullable private final String keyId;
    @Nullable private final String metadataHash;

    private TrustedMetadata(@Nullable String keyId, @Nullable String metadataHash) {
      this.keyId = keyId;
      this.metadataHash = metadataHash;
    }

    /**
     * Creates trusted metadata from catalog values.
     *
     * @param keyId the trusted key identifier, or {@code null}
     * @param metadataHash the trusted metadata hash, or {@code null}
     * @return trusted metadata containing the supplied values
     */
    public static TrustedMetadata of(@Nullable String keyId, @Nullable String metadataHash) {
      return new TrustedMetadata(keyId, metadataHash);
    }

    /**
     * Returns the trusted key identifier.
     *
     * @return the trusted key identifier, or {@code null}
     */
    @Nullable
    public String keyId() {
      return keyId;
    }

    /**
     * Returns the trusted metadata hash.
     *
     * @return the trusted metadata hash, or {@code null}
     */
    @Nullable
    public String metadataHash() {
      return metadataHash;
    }
  }
}
