/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.encryption;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.function.Function;
import org.apache.gravitino.encryption.kms.KmsClient;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.encryption.kms.KmsKeyProperties;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Pins the factory guards and the shared validation contract: a wrapping-capable key is {@code
 * VALID}; an absent or unusable key is {@code INVALID}; connection and unexpected failures are
 * {@code UNAVAILABLE}; argument errors are {@code INVALID}.
 */
public class TestIcebergEncryptionKmsKeyValidators {

  private static final KmsReference KEY = new KmsReference("openbao", "customer-pii-v1");
  private static final IcebergEncryptionDecision.KmsValidationStatus VALID =
      IcebergEncryptionDecision.KmsValidationStatus.VALID;
  private static final IcebergEncryptionDecision.KmsValidationStatus INVALID =
      IcebergEncryptionDecision.KmsValidationStatus.INVALID;
  private static final IcebergEncryptionDecision.KmsValidationStatus UNAVAILABLE =
      IcebergEncryptionDecision.KmsValidationStatus.UNAVAILABLE;

  @Test
  void testNullBackingSourcesAreRejectedAsIllegalArguments() {
    Assertions.assertEquals(
        "registry cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> IcebergEncryptionKmsKeyValidators.fromRegistry(null))
            .getMessage());
    Assertions.assertEquals(
        "clientResolver cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> IcebergEncryptionKmsKeyValidators.fromClientResolver(null))
            .getMessage());
    Assertions.assertEquals(
        "client cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> IcebergEncryptionKmsKeyValidators.fromClient(null))
            .getMessage());
  }

  @Test
  void testEnabledWrappingKeyIsValidOnEveryFactory() {
    KmsClient client = clientWith(properties(true, true));

    assertStatus(IcebergEncryptionKmsKeyValidators.fromClient(client), VALID);
    assertStatus(IcebergEncryptionKmsKeyValidators.fromClientResolver(key -> client), VALID);
    assertStatus(IcebergEncryptionKmsKeyValidators.fromRegistry(registryWith(client)), VALID);
  }

  @Test
  void testAbsentOrUnusableKeysAreInvalidOnEveryFactory() {
    KmsClient absent = clientWith(Optional.empty());
    KmsClient disabled = clientWith(properties(false, true));
    KmsClient notWrappable = clientWith(properties(true, false));

    assertInvalidOnEveryFactory(absent);
    assertInvalidOnEveryFactory(disabled);
    assertInvalidOnEveryFactory(notWrappable);
  }

  @Test
  void testConnectionFailedExceptionIsUnavailableOnEveryFactory() {
    KmsClient client = throwingClient(new ConnectionFailedException("KMS is unreachable"));

    assertStatus(IcebergEncryptionKmsKeyValidators.fromClient(client), UNAVAILABLE);
    assertStatus(IcebergEncryptionKmsKeyValidators.fromClientResolver(key -> client), UNAVAILABLE);
    assertStatus(IcebergEncryptionKmsKeyValidators.fromRegistry(registryWith(client)), UNAVAILABLE);
  }

  @Test
  void testIllegalArgumentExceptionIsInvalidOnEveryFactory() {
    KmsClient client = throwingClient(new IllegalArgumentException("key does not belong here"));

    assertStatus(IcebergEncryptionKmsKeyValidators.fromClient(client), INVALID);
    assertStatus(IcebergEncryptionKmsKeyValidators.fromClientResolver(key -> client), INVALID);
    assertStatus(IcebergEncryptionKmsKeyValidators.fromRegistry(registryWith(client)), INVALID);
  }

  @Test
  void testOtherRuntimeExceptionIsUnavailableOnEveryFactory() {
    KmsClient client = throwingClient(new IllegalStateException("unexpected KMS failure"));

    assertStatus(IcebergEncryptionKmsKeyValidators.fromClient(client), UNAVAILABLE);
    assertStatus(IcebergEncryptionKmsKeyValidators.fromClientResolver(key -> client), UNAVAILABLE);
    assertStatus(IcebergEncryptionKmsKeyValidators.fromRegistry(registryWith(client)), UNAVAILABLE);
  }

  @Test
  void testRegistryLookupFailuresMapToValidationStatus() {
    KmsClientRegistry unknownProvider = mock(KmsClientRegistry.class);
    when(unknownProvider.getClient(KEY))
        .thenThrow(new IllegalArgumentException("unknown provider"));
    assertStatus(IcebergEncryptionKmsKeyValidators.fromRegistry(unknownProvider), INVALID);

    KmsClientRegistry closedRegistry = mock(KmsClientRegistry.class);
    when(closedRegistry.getClient(KEY)).thenThrow(new IllegalStateException("registry is closed"));
    assertStatus(IcebergEncryptionKmsKeyValidators.fromRegistry(closedRegistry), UNAVAILABLE);
  }

  @Test
  void testClientResolverLookupFailuresMapToValidationStatus() {
    Function<KmsReference, KmsClient> unknownProvider =
        key -> {
          throw new IllegalArgumentException("unknown provider");
        };
    assertStatus(IcebergEncryptionKmsKeyValidators.fromClientResolver(unknownProvider), INVALID);

    Function<KmsReference, KmsClient> resolverFailure =
        key -> {
          throw new IllegalStateException("resolver failed");
        };
    assertStatus(
        IcebergEncryptionKmsKeyValidators.fromClientResolver(resolverFailure), UNAVAILABLE);
  }

  private static void assertInvalidOnEveryFactory(KmsClient client) {
    assertStatus(IcebergEncryptionKmsKeyValidators.fromClient(client), INVALID);
    assertStatus(IcebergEncryptionKmsKeyValidators.fromClientResolver(key -> client), INVALID);
    assertStatus(IcebergEncryptionKmsKeyValidators.fromRegistry(registryWith(client)), INVALID);
  }

  private static void assertStatus(
      IcebergEncryptionPolicyEvaluator.KmsKeyValidator validator,
      IcebergEncryptionDecision.KmsValidationStatus expected) {
    Assertions.assertEquals(expected, validator.validate(KEY));
  }

  private static KmsClientRegistry registryWith(KmsClient client) {
    KmsClientRegistry registry = mock(KmsClientRegistry.class);
    when(registry.getClient(KEY)).thenReturn(client);
    return registry;
  }

  private static KmsClient clientWith(Optional<KmsKeyProperties> properties) {
    KmsClient client = mock(KmsClient.class);
    when(client.getKeyProperties(KEY)).thenReturn(properties);
    return client;
  }

  private static Optional<KmsKeyProperties> properties(boolean enabled, boolean supportsWrapping) {
    KmsKeyProperties properties = mock(KmsKeyProperties.class);
    when(properties.enabled()).thenReturn(enabled);
    when(properties.supportsWrapping()).thenReturn(supportsWrapping);
    return Optional.of(properties);
  }

  private static KmsClient throwingClient(RuntimeException failure) {
    KmsClient client = mock(KmsClient.class);
    when(client.getKeyProperties(KEY)).thenThrow(failure);
    return client;
  }
}
