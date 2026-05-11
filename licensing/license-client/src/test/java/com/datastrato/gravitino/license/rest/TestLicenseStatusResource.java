/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.license.rest;

import com.datastrato.gravitino.license.LicenseManager;
import com.datastrato.gravitino.license.dto.LicenseStatusDTO;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestLicenseStatusResource extends JerseyTest {

  private LicenseManager mockManager;

  @Override
  protected Application configure() {
    mockManager = Mockito.mock(LicenseManager.class);
    ResourceConfig config = new ResourceConfig();
    config.register(new LicenseStatusResource(mockManager));
    return config;
  }

  @BeforeEach
  void resetMock() {
    Mockito.reset(mockManager);
  }

  private static LicenseManager.LicenseStatus buildStatus(
      LicenseManager.StatusCode code, int maxNodes) {
    LicenseManager.LicenseStatus s =
        new LicenseManager.LicenseStatus(
            code, "Acme Corp", "test-id-123", 20265L, 47L, 30, maxNodes);
    s.setActiveNodes(3);
    return s;
  }

  @Test
  void testGetStatus() {
    Mockito.when(mockManager.getStatus())
        .thenReturn(buildStatus(LicenseManager.StatusCode.VALID, 5));

    Response resp = target("/license/status").request(MediaType.APPLICATION_JSON_TYPE).get();
    Assertions.assertEquals(Response.Status.OK, resp.getStatusInfo().toEnum());
    LicenseStatusDTO dto = resp.readEntity(LicenseStatusDTO.class);
    Assertions.assertEquals("Acme Corp", dto.getIssuedTo());
    Assertions.assertEquals("VALID", dto.getStatus());
    Assertions.assertEquals(47L, dto.getDaysRemaining());
    Assertions.assertEquals(5, dto.getMaxNodes());
    Assertions.assertEquals(3, dto.getActiveNodes());
  }

  @Test
  void testGetStatusWhenManagerThrows() {
    Mockito.when(mockManager.getStatus()).thenThrow(new RuntimeException("DB unavailable"));

    Response resp = target("/license/status").request(MediaType.APPLICATION_JSON_TYPE).get();
    Assertions.assertEquals(Response.Status.INTERNAL_SERVER_ERROR, resp.getStatusInfo().toEnum());
  }

  @Test
  void testGetStatusWhenStatusIsNull() {
    Mockito.when(mockManager.getStatus()).thenReturn(null);

    Response resp = target("/license/status").request(MediaType.APPLICATION_JSON_TYPE).get();
    Assertions.assertEquals(Response.Status.INTERNAL_SERVER_ERROR, resp.getStatusInfo().toEnum());
  }

  @Test
  void testPostMethodNotAllowed() {
    Response resp =
        target("/license/status").request(MediaType.APPLICATION_JSON_TYPE).post(Entity.json("{}"));
    Assertions.assertEquals(Response.Status.METHOD_NOT_ALLOWED, resp.getStatusInfo().toEnum());
  }

  @Test
  void testUnknownPathReturns404() {
    Response resp = target("/license/unknown").request(MediaType.APPLICATION_JSON_TYPE).get();
    Assertions.assertEquals(Response.Status.NOT_FOUND, resp.getStatusInfo().toEnum());
  }

  @Test
  void testExpiredStatus() {
    Mockito.when(mockManager.getStatus())
        .thenReturn(buildStatus(LicenseManager.StatusCode.EXPIRED, 5));

    Response resp = target("/license/status").request(MediaType.APPLICATION_JSON_TYPE).get();
    Assertions.assertEquals(Response.Status.OK, resp.getStatusInfo().toEnum());
    LicenseStatusDTO dto = resp.readEntity(LicenseStatusDTO.class);
    Assertions.assertEquals("EXPIRED", dto.getStatus());
  }

  @Test
  void testInGracePeriodStatus() {
    Mockito.when(mockManager.getStatus())
        .thenReturn(buildStatus(LicenseManager.StatusCode.IN_GRACE_PERIOD, 5));

    Response resp = target("/license/status").request(MediaType.APPLICATION_JSON_TYPE).get();
    Assertions.assertEquals(Response.Status.OK, resp.getStatusInfo().toEnum());
    LicenseStatusDTO dto = resp.readEntity(LicenseStatusDTO.class);
    Assertions.assertEquals("IN_GRACE_PERIOD", dto.getStatus());
  }

  @Test
  void testExpiringSoonStatus() {
    Mockito.when(mockManager.getStatus())
        .thenReturn(buildStatus(LicenseManager.StatusCode.EXPIRING_SOON, 5));

    Response resp = target("/license/status").request(MediaType.APPLICATION_JSON_TYPE).get();
    Assertions.assertEquals(Response.Status.OK, resp.getStatusInfo().toEnum());
    LicenseStatusDTO dto = resp.readEntity(LicenseStatusDTO.class);
    Assertions.assertEquals("EXPIRING_SOON", dto.getStatus());
  }

  @Test
  void testUnlimitedNodes() {
    Mockito.when(mockManager.getStatus())
        .thenReturn(buildStatus(LicenseManager.StatusCode.VALID, -1));

    Response resp = target("/license/status").request(MediaType.APPLICATION_JSON_TYPE).get();
    Assertions.assertEquals(Response.Status.OK, resp.getStatusInfo().toEnum());
    LicenseStatusDTO dto = resp.readEntity(LicenseStatusDTO.class);
    Assertions.assertEquals(-1, dto.getMaxNodes());
  }
}
