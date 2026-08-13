/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.transit.kms;

import com.datastrato.gravitino.transit.common.TransitConnection;
import java.util.Objects;
import java.util.Optional;
import org.apache.gravitino.encryption.kms.KmsClient;
import org.apache.gravitino.encryption.kms.KmsKeyProperties;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.exceptions.ConnectionFailedException;

/**
 * Non-owning, thread-safe KMS view over a shared Transit connection.
 *
 * <p>The provider composition root owns and closes the supplied connection. Closing this API view
 * has no effect on that shared resource.
 */
public final class TransitKmsClient implements KmsClient {

  private final String providerName;
  private final String expectedApi;
  private final String source;
  private final TransitKmsApi api;

  /**
   * Creates a KMS API view over a shared Transit connection.
   *
   * @param providerName provider name used in errors
   * @param expectedApi provider API identifier
   * @param source configured KMS source
   * @param transitMount Transit secrets-engine mount
   * @param connection shared provider-owned connection
   */
  public TransitKmsClient(
      String providerName,
      String expectedApi,
      String source,
      String transitMount,
      TransitConnection connection) {
    this.providerName = providerName;
    this.expectedApi = expectedApi;
    this.source = source;
    this.api =
        new TransitKmsApi(
            providerName,
            transitMount,
            Objects.requireNonNull(connection, "Transit KMS connection cannot be null"));
  }

  /** {@inheritDoc} */
  @Override
  public Optional<KmsKeyProperties> getKeyProperties(KmsReference reference) {
    validateReference(reference);

    Optional<TransitReadKeyResponse> response = api.readKey(reference.keyId());
    if (!response.isPresent()) {
      return Optional.empty();
    }

    TransitKeyData data = response.get().data();
    if (data == null || data.supportsEncryption() == null || data.supportsDecryption() == null) {
      throw malformedResponse();
    }
    if (Boolean.TRUE.equals(data.softDeleted())) {
      return Optional.empty();
    }

    return Optional.of(
        new TransitKmsKeyProperties(
            reference, data.supportsEncryption(), data.supportsDecryption()));
  }

  private void validateReference(KmsReference reference) {
    if (reference == null) {
      throw new IllegalArgumentException(
          String.format("%s key reference cannot be null", providerName));
    }
    if (!expectedApi.equals(reference.api())) {
      throw new IllegalArgumentException(
          String.format(
              "KMS API %s does not match expected API %s for %s",
              reference.api(), expectedApi, providerName));
    }
    if (!source.equals(reference.source())) {
      throw new IllegalArgumentException(
          String.format(
              "%s source %s does not match configured source %s",
              providerName, reference.source(), source));
    }
    validateKeyId(reference.keyId());
  }

  private void validateKeyId(String keyId) {
    if (keyId == null || keyId.trim().isEmpty()) {
      throw new IllegalArgumentException(String.format("%s key ID cannot be blank", providerName));
    }
    if (keyId.contains("/") || keyId.contains("\\") || ".".equals(keyId) || "..".equals(keyId)) {
      throw new IllegalArgumentException(
          String.format("Invalid %s key ID: %s", providerName, keyId));
    }
  }

  private ConnectionFailedException malformedResponse() {
    return new ConnectionFailedException("%s returned a malformed response", providerName);
  }
}
