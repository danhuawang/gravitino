/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.requests;

import java.util.List;
import org.apache.gravitino.json.JsonUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestLocalUserAddRequest {

  @Test
  public void testSerDe() throws Exception {
    LocalUserAddRequest request = new LocalUserAddRequest("jordan.reyes", List.of("Analyst"), true);

    String serJson = JsonUtils.objectMapper().writeValueAsString(request);
    LocalUserAddRequest deser =
        JsonUtils.objectMapper().readValue(serJson, LocalUserAddRequest.class);

    Assertions.assertEquals(request, deser);
    Assertions.assertEquals("jordan.reyes", deser.getName());
    Assertions.assertEquals(List.of("Analyst"), deser.getRoles());
    Assertions.assertEquals(true, deser.getEnabled());
  }

  @Test
  public void testValidate() {
    Assertions.assertDoesNotThrow(
        () -> new LocalUserAddRequest("alice", List.of("Analyst"), null).validate());
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> new LocalUserAddRequest(null, null, null).validate());
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> new LocalUserAddRequest(" ", null, null).validate());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new LocalUserAddRequest("bad:user", null, null).validate());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new LocalUserAddRequest("alice", List.of(" "), true).validate());
  }
}
