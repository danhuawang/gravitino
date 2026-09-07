/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.requests;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for {@link IdpMembershipAddRequest}. */
public class TestIdpMembershipAddRequest {

  @Test
  public void testValidateSuccess() {
    assertDoesNotThrow(
        () ->
            new IdpMembershipAddRequest(List.of("alice", "bob"), List.of("ops", "governance"))
                .validate());
  }

  @Test
  public void testValidateRejectsInvalidFields() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new IdpMembershipAddRequest(null, List.of("ops")).validate());
    assertThrows(
        IllegalArgumentException.class,
        () -> new IdpMembershipAddRequest(Collections.emptyList(), List.of("ops")).validate());
    assertThrows(
        IllegalArgumentException.class,
        () -> new IdpMembershipAddRequest(List.of("alice"), null).validate());
    assertThrows(
        IllegalArgumentException.class,
        () -> new IdpMembershipAddRequest(List.of("alice"), Collections.emptyList()).validate());
    assertThrows(
        IllegalArgumentException.class,
        () -> new IdpMembershipAddRequest(List.of(" "), List.of("ops")).validate());
    assertThrows(
        IllegalArgumentException.class,
        () -> new IdpMembershipAddRequest(List.of("bad:user"), List.of("ops")).validate());
    assertThrows(
        IllegalArgumentException.class,
        () -> new IdpMembershipAddRequest(List.of("alice"), List.of(" ")).validate());
  }
}
