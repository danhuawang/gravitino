/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto.requests;

import java.util.Collections;
import java.util.List;
import org.apache.gravitino.json.JsonUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestRoleAssignmentRequest {

  @Test
  public void testSerDe() throws Exception {
    RoleAssignmentRequest request =
        new RoleAssignmentRequest(List.of("alice", "bob"), List.of("analysts"));

    String json = JsonUtils.objectMapper().writeValueAsString(request);
    RoleAssignmentRequest deserialized =
        JsonUtils.objectMapper().readValue(json, RoleAssignmentRequest.class);

    Assertions.assertEquals(request, deserialized);
    Assertions.assertEquals(List.of("alice", "bob"), deserialized.getUsers());
    Assertions.assertEquals(List.of("analysts"), deserialized.getGroups());
  }

  @Test
  public void testValidate() {
    Assertions.assertDoesNotThrow(
        () -> new RoleAssignmentRequest(List.of("alice"), null).validate());
    Assertions.assertDoesNotThrow(
        () -> new RoleAssignmentRequest(null, List.of("analysts")).validate());
    Assertions.assertDoesNotThrow(
        () -> new RoleAssignmentRequest(List.of("alice"), List.of("analysts")).validate());

    Assertions.assertThrows(
        IllegalArgumentException.class, () -> new RoleAssignmentRequest(null, null).validate());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            new RoleAssignmentRequest(Collections.emptyList(), Collections.emptyList()).validate());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new RoleAssignmentRequest(List.of(" "), null).validate());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new RoleAssignmentRequest(Collections.singletonList(null), null).validate());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new RoleAssignmentRequest(List.of("alice", "alice"), null).validate());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new RoleAssignmentRequest(null, List.of("analysts", "analysts")).validate());
  }
}
