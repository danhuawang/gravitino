/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.encryption;

import com.google.common.base.Preconditions;
import java.util.Optional;
import java.util.function.Function;
import org.apache.gravitino.encryption.kms.KmsClient;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.encryption.kms.KmsKeyProperties;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.exceptions.ConnectionFailedException;

/** Adapts KMS clients to the Iceberg encryption policy evaluator's validation contract. */
public final class IcebergEncryptionKmsKeyValidators {

  private IcebergEncryptionKmsKeyValidators() {}

  /**
   * Creates a validator backed by the server's configured KMS client registry.
   *
   * <p>The requested {@link KmsReference} {@code {provider, keyId}} is inspected with a
   * metadata-only client looked up by {@link KmsReference#provider()}.
   *
   * @param registry configured KMS client registry
   * @return metadata-only key validator
   */
  public static IcebergEncryptionPolicyEvaluator.KmsKeyValidator fromRegistry(
      KmsClientRegistry registry) {
    Preconditions.checkArgument(registry != null, "registry cannot be null");
    return key -> {
      try {
        return IcebergEncryptionKmsKeyValidators.validate(registry.getClient(key), key);
      } catch (IllegalArgumentException e) {
        return IcebergEncryptionDecision.KmsValidationStatus.INVALID;
      } catch (RuntimeException e) {
        return IcebergEncryptionDecision.KmsValidationStatus.UNAVAILABLE;
      }
    };
  }

  /**
   * Creates a validator backed by a client resolver.
   *
   * @param clientResolver resolver for the client selected by a key reference
   * @return metadata-only key validator
   */
  public static IcebergEncryptionPolicyEvaluator.KmsKeyValidator fromClientResolver(
      Function<KmsReference, KmsClient> clientResolver) {
    Preconditions.checkArgument(clientResolver != null, "clientResolver cannot be null");
    return key -> validate(clientResolver, key);
  }

  /**
   * Creates a validator backed by one KMS client.
   *
   * @param client KMS client
   * @return metadata-only key validator
   */
  public static IcebergEncryptionPolicyEvaluator.KmsKeyValidator fromClient(KmsClient client) {
    Preconditions.checkArgument(client != null, "client cannot be null");
    return key -> validate(client, key);
  }

  private static IcebergEncryptionDecision.KmsValidationStatus validate(
      Function<KmsReference, KmsClient> clientResolver, KmsReference reference) {
    try {
      return validate(clientResolver.apply(reference), reference);
    } catch (IllegalArgumentException e) {
      return IcebergEncryptionDecision.KmsValidationStatus.INVALID;
    } catch (RuntimeException e) {
      return IcebergEncryptionDecision.KmsValidationStatus.UNAVAILABLE;
    }
  }

  private static IcebergEncryptionDecision.KmsValidationStatus validate(
      KmsClient client, KmsReference reference) {
    try {
      Optional<KmsKeyProperties> properties = client.getKeyProperties(reference);
      return properties.isPresent()
              && properties.get().enabled()
              && properties.get().supportsWrapping()
          ? IcebergEncryptionDecision.KmsValidationStatus.VALID
          : IcebergEncryptionDecision.KmsValidationStatus.INVALID;
    } catch (ConnectionFailedException e) {
      return IcebergEncryptionDecision.KmsValidationStatus.UNAVAILABLE;
    } catch (IllegalArgumentException e) {
      return IcebergEncryptionDecision.KmsValidationStatus.INVALID;
    } catch (RuntimeException e) {
      return IcebergEncryptionDecision.KmsValidationStatus.UNAVAILABLE;
    }
  }
}
