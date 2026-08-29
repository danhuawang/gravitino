/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datastrato.gravitino.dto.responses.ConnectionOverviewResponse;
import java.time.Instant;
import javax.annotation.Nullable;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.dto.AuditDTO;
import org.junit.jupiter.api.Test;

class TestConnectionOverviewDTO {

  @Test
  void testValidStatusShapes() {
    ConnectionTestStatusDTO unsupported = new ConnectionTestStatusDTO(false, null, null, null);
    ConnectionTestStatusDTO notTested =
        new ConnectionTestStatusDTO(true, ConnectionTestStatusDTO.NOT_TESTED, null, null);
    ConnectionTestStatusDTO passed =
        new ConnectionTestStatusDTO(true, ConnectionTestStatusDTO.PASSED, Instant.EPOCH, null);
    ConnectionTestStatusDTO failed =
        new ConnectionTestStatusDTO(
            true,
            ConnectionTestStatusDTO.FAILED,
            Instant.EPOCH,
            new ConnectionTestErrorDTO(
                1007, "ConnectionFailedException", "Failed to connect to the catalog"));

    assertDoesNotThrow(unsupported::validate);
    assertDoesNotThrow(notTested::validate);
    assertDoesNotThrow(passed::validate);
    assertDoesNotThrow(failed::validate);
  }

  @Test
  void testInvalidStatusShapes() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ConnectionTestStatusDTO(false, ConnectionTestStatusDTO.NOT_TESTED, null, null)
                .validate());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ConnectionTestStatusDTO(true, ConnectionTestStatusDTO.PASSED, null, null)
                .validate());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ConnectionTestStatusDTO(true, ConnectionTestStatusDTO.FAILED, Instant.EPOCH, null)
                .validate());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ConnectionTestStatusDTO(
                    true,
                    ConnectionTestStatusDTO.PASSED,
                    Instant.EPOCH,
                    new ConnectionTestErrorDTO(1007, "ConnectionFailedException", "Unexpected"))
                .validate());
  }

  @Test
  void testOverviewResponseValidation() {
    ConnectionTestStatusDTO notTested =
        new ConnectionTestStatusDTO(true, ConnectionTestStatusDTO.NOT_TESTED, null, null);
    ConnectionOverviewDTO overview =
        newOverview(
            new CredentialProviderStatusDTO[] {
              new CredentialProviderStatusDTO("s3-token", notTested)
            });

    assertDoesNotThrow(() -> new ConnectionOverviewResponse(overview).validate());
    assertThrows(
        IllegalArgumentException.class,
        () -> new CredentialProviderStatusDTO(" ", notTested).validate());
    assertThrows(
        IllegalArgumentException.class,
        () -> new CredentialProviderStatusDTO("s3-token", null).validate());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConnectionOverviewResponse(newOverview(null)).validate());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ConnectionOverviewResponse(newOverview(new CredentialProviderStatusDTO[] {null}))
                .validate());
    assertThrows(IllegalArgumentException.class, () -> new ConnectionOverviewResponse().validate());
  }

  private ConnectionOverviewDTO newOverview(
      @Nullable CredentialProviderStatusDTO[] credentialProviders) {
    return new ConnectionOverviewDTO(
        "catalog",
        Catalog.Type.RELATIONAL,
        "jdbc-mysql",
        null,
        "aws",
        "us-east-1",
        AuditDTO.builder().build(),
        "jdbc:mysql://host/db",
        new ConnectionTestStatusDTO(true, ConnectionTestStatusDTO.NOT_TESTED, null, null),
        credentialProviders);
  }
}
