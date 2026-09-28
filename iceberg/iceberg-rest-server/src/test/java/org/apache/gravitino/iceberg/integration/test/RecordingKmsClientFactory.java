/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.iceberg.integration.test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.gravitino.encryption.kms.KmsClient;
import org.apache.gravitino.encryption.kms.KmsClientFactory;
import org.apache.gravitino.encryption.kms.KmsKeyProperties;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.exceptions.ConnectionFailedException;

/**
 * Test KMS factory that accepts two keys, reports one key as unreachable, and records every key
 * validation.
 */
public final class RecordingKmsClientFactory implements KmsClientFactory {

  static final String KEY_ID = "customer-pii-v1";
  static final String MISSING_KEY_ID = "missing-key";
  // Valid in this KMS but deliberately absent from the acceptance policy's allow list.
  static final String OTHER_KEY_ID = "customer-pii-v2";
  // The KMS cannot be reached for this key, so validation reports it unavailable.
  static final String UNAVAILABLE_KEY_ID = "unavailable-key";
  static final String PROVIDER = "primary";

  private static final AtomicReference<KmsReference> LAST_REFERENCE = new AtomicReference<>();
  private static final AtomicInteger INSPECTION_COUNT = new AtomicInteger();
  private static final AtomicInteger CREATION_COUNT = new AtomicInteger();

  static void reset() {
    LAST_REFERENCE.set(null);
    INSPECTION_COUNT.set(0);
    CREATION_COUNT.set(0);
  }

  static Optional<KmsReference> lastReference() {
    return Optional.ofNullable(LAST_REFERENCE.get());
  }

  static int inspectionCount() {
    return INSPECTION_COUNT.get();
  }

  static int creationCount() {
    return CREATION_COUNT.get();
  }

  /** {@inheritDoc} */
  @Override
  public KmsClient create(String provider, Map<String, String> properties) {
    CREATION_COUNT.incrementAndGet();
    return new KmsClient() {
      @Override
      public Optional<KmsKeyProperties> getKeyProperties(KmsReference reference) {
        LAST_REFERENCE.set(reference);
        INSPECTION_COUNT.incrementAndGet();
        if (UNAVAILABLE_KEY_ID.equals(reference.keyId())) {
          throw new ConnectionFailedException(
              "Test KMS is unreachable for key %s", reference.keyId());
        }
        if (!PROVIDER.equals(reference.provider())
            || !(KEY_ID.equals(reference.keyId()) || OTHER_KEY_ID.equals(reference.keyId()))) {
          return Optional.empty();
        }
        return Optional.of(new ValidKeyProperties(reference));
      }
    };
  }

  private static final class ValidKeyProperties implements KmsKeyProperties {

    private final KmsReference reference;

    private ValidKeyProperties(KmsReference reference) {
      this.reference = reference;
    }

    @Override
    public KmsReference reference() {
      return reference;
    }

    @Override
    public boolean enabled() {
      return true;
    }

    @Override
    public boolean supportsWrapping() {
      return true;
    }

    @Override
    public boolean supportsUnwrapping() {
      return true;
    }
  }
}
