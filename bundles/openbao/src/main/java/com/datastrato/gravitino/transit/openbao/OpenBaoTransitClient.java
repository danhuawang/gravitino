/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.transit.openbao;

import com.datastrato.gravitino.transit.common.TransitConnection;
import com.datastrato.gravitino.transit.kms.TransitKmsClient;
import java.net.URI;
import java.util.Optional;
import org.apache.gravitino.encryption.kms.KmsClient;
import org.apache.gravitino.encryption.kms.KmsKeyProperties;
import org.apache.gravitino.encryption.kms.KmsReference;

final class OpenBaoTransitClient implements KmsClient {

  private final TransitConnection connection;
  private final TransitKmsClient kmsClient;

  OpenBaoTransitClient(
      String provider,
      URI serviceAddress,
      String transitMount,
      String bearerToken,
      boolean allowInsecureHttp) {
    connection =
        new TransitConnection(
            OpenBaoTransitKmsClientFactory.PROVIDER_NAME,
            serviceAddress,
            bearerToken,
            allowInsecureHttp);
    kmsClient = connection.kms(provider, transitMount);
  }

  @Override
  public Optional<KmsKeyProperties> getKeyProperties(KmsReference reference) {
    return kmsClient.getKeyProperties(reference);
  }

  @Override
  public void close() {
    connection.close();
  }
}
