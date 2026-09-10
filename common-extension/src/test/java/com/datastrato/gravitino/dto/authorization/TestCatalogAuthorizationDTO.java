/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.annotation.Nullable;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.json.JsonUtils;
import org.junit.jupiter.api.Test;

class TestCatalogAuthorizationDTO {

  @Test
  void testRoundTripWithCatalogDetails() throws Exception {
    CatalogAuthorizationDTO catalog =
        catalogAuthorization("lakehouse-iceberg", Catalog.Type.RELATIONAL);

    String json = JsonUtils.objectMapper().writeValueAsString(catalog);
    assertTrue(json.contains("\"catalogProvider\":\"lakehouse-iceberg\""));
    assertTrue(json.contains("\"catalogType\":\"relational\""));

    CatalogAuthorizationDTO roundTrip =
        JsonUtils.objectMapper().readValue(json, CatalogAuthorizationDTO.class);
    assertEquals("lakehouse-iceberg", roundTrip.getCatalogProvider());
    assertEquals(Catalog.Type.RELATIONAL, roundTrip.getCatalogType());
  }

  @Test
  void testCatalogDetailsAreOptional() {
    CatalogAuthorizationDTO catalog = catalogAuthorization(null, null);

    assertNull(catalog.getCatalogProvider());
    assertNull(catalog.getCatalogType());
  }

  private CatalogAuthorizationDTO catalogAuthorization(
      @Nullable String catalogProvider, @Nullable Catalog.Type catalogType) {
    return new CatalogAuthorizationDTO(
        "catalog",
        catalogProvider,
        catalogType,
        new ObjectAuthorizationDTO[0],
        new String[0],
        new String[0],
        new String[0],
        0);
  }
}
