/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Tests for {@link IdentityLookupValidator}. */
public class TestIdentityLookupValidator {

  @Test
  public void testValidateUserGroupsLookup() {
    Assertions.assertDoesNotThrow(
        () -> IdentityLookupValidator.validateUserGroupsLookup("alice", IdentityType.PROVISIONED));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> IdentityLookupValidator.validateUserGroupsLookup(null, IdentityType.LOCAL));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> IdentityLookupValidator.validateUserGroupsLookup(" ", IdentityType.LOCAL));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> IdentityLookupValidator.validateUserGroupsLookup("bad:user", IdentityType.LOCAL));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> IdentityLookupValidator.validateUserGroupsLookup("alice", null));
  }

  @Test
  public void testValidateUserGroupsLookupUsernameLengthBoundary() {
    String maxLengthUsername = "a".repeat(128);
    Assertions.assertDoesNotThrow(
        () ->
            IdentityLookupValidator.validateUserGroupsLookup(
                maxLengthUsername, IdentityType.LOCAL));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            IdentityLookupValidator.validateUserGroupsLookup("a".repeat(129), IdentityType.LOCAL));
  }

  @Test
  public void testValidateGroupLookup() {
    Assertions.assertDoesNotThrow(
        () -> IdentityLookupValidator.validateGroupLookup("contractors", IdentityType.PROVISIONED));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> IdentityLookupValidator.validateGroupLookup(null, IdentityType.LOCAL));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> IdentityLookupValidator.validateGroupLookup(" ", IdentityType.LOCAL));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> IdentityLookupValidator.validateGroupLookup("contractors", null));
  }

  @Test
  public void testValidateGroupLookupGroupNameLengthBoundary() {
    String maxLengthGroupName = "g".repeat(128);
    Assertions.assertDoesNotThrow(
        () -> IdentityLookupValidator.validateGroupLookup(maxLengthGroupName, IdentityType.LOCAL));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> IdentityLookupValidator.validateGroupLookup("g".repeat(129), IdentityType.LOCAL));
  }
}
