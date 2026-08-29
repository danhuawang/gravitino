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
        new RoleAssignmentRequest(
            List.of("reader", "writer"), List.of("alice", "bob"), List.of("analysts"));

    String json = JsonUtils.objectMapper().writeValueAsString(request);
    RoleAssignmentRequest deserialized =
        JsonUtils.objectMapper().readValue(json, RoleAssignmentRequest.class);

    Assertions.assertEquals(request, deserialized);
    Assertions.assertEquals(List.of("reader", "writer"), deserialized.getRoles());
    Assertions.assertEquals(List.of("alice", "bob"), deserialized.getUsers());
    Assertions.assertEquals(List.of("analysts"), deserialized.getGroups());
  }

  @Test
  public void testValidate() {
    Assertions.assertDoesNotThrow(
        () -> new RoleAssignmentRequest(List.of("reader"), List.of("alice"), null).validate());
    Assertions.assertDoesNotThrow(
        () -> new RoleAssignmentRequest(List.of("reader"), null, List.of("analysts")).validate());
    Assertions.assertDoesNotThrow(
        () ->
            new RoleAssignmentRequest(
                    List.of("reader", "writer"), List.of("alice"), List.of("analysts"))
                .validate());

    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new RoleAssignmentRequest(null, List.of("alice"), null).validate());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            new RoleAssignmentRequest(Collections.emptyList(), List.of("alice"), null).validate());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new RoleAssignmentRequest(List.of("reader"), null, null).validate());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            new RoleAssignmentRequest(
                    List.of("reader"), Collections.emptyList(), Collections.emptyList())
                .validate());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new RoleAssignmentRequest(List.of("reader"), List.of(" "), null).validate());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            new RoleAssignmentRequest(List.of("reader"), null, List.of("analysts", "analysts"))
                .validate());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new RoleAssignmentRequest(List.of(" "), List.of("alice"), null).validate());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            new RoleAssignmentRequest(List.of("reader", "reader"), List.of("alice"), null)
                .validate());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            new RoleAssignmentRequest(List.of("reader"), List.of("alice", "alice"), null)
                .validate());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            new RoleAssignmentRequest(Collections.singletonList(null), List.of("alice"), null)
                .validate());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            new RoleAssignmentRequest(List.of("reader"), Collections.singletonList(null), null)
                .validate());
  }
}
