/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.requests;

import java.util.List;
import org.apache.gravitino.json.JsonUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestLocalGroupAddRequest {

  @Test
  public void testSerDe() throws Exception {
    LocalGroupAddRequest request = new LocalGroupAddRequest("contractors", List.of("Analyst"));

    String serJson = JsonUtils.objectMapper().writeValueAsString(request);
    LocalGroupAddRequest deser =
        JsonUtils.objectMapper().readValue(serJson, LocalGroupAddRequest.class);

    Assertions.assertEquals(request, deser);
    Assertions.assertEquals("contractors", deser.getName());
    Assertions.assertEquals(List.of("Analyst"), deser.getRoles());
  }

  @Test
  public void testValidate() {
    Assertions.assertDoesNotThrow(
        () -> new LocalGroupAddRequest("contractors", List.of("Analyst")).validate());
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> new LocalGroupAddRequest(null, null).validate());
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> new LocalGroupAddRequest(" ", null).validate());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new LocalGroupAddRequest("g", List.of(" ")).validate());
  }
}
