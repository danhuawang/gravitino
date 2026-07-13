/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datastrato.gravitino.scim.service.ScimConfig;
import java.util.Map;
import org.apache.directory.scim.spec.exception.UnsupportedFilterException;
import org.apache.directory.scim.spec.filter.Filter;
import org.apache.gravitino.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestScimUserFilter {

  private ScimConfig scimConfig;

  @BeforeEach
  void setUp() {
    scimConfig = new ScimConfig(Map.of(), new Config() {});
  }

  @Test
  void testExternalIdEq() throws Exception {
    ScimUserFilter criteria =
        ScimUserFilter.convert(Filter.decode("externalId eq \"abc\""), scimConfig);
    assertEquals("abc", criteria.externalId().orElseThrow());
    assertFalse(criteria.userName().isPresent());
  }

  @Test
  void testUserNameEq() throws Exception {
    ScimUserFilter criteria =
        ScimUserFilter.convert(Filter.decode("userName eq \"alice\""), scimConfig);
    assertEquals("alice", criteria.userName().orElseThrow());
  }

  @Test
  void testAndFilter() throws Exception {
    ScimUserFilter criteria =
        ScimUserFilter.convert(
            Filter.decode("externalId eq \"u1\" and userName eq \"alice\""), scimConfig);
    assertEquals("u1", criteria.externalId().orElseThrow());
    assertEquals("alice", criteria.userName().orElseThrow());
  }

  @Test
  void testUnsupportedOp() {
    assertThrows(
        UnsupportedFilterException.class,
        () -> ScimUserFilter.convert(Filter.decode("externalId co \"abc\""), scimConfig));
  }

  @Test
  void testDisplayNameUnsupported() {
    assertThrows(
        UnsupportedFilterException.class,
        () -> ScimUserFilter.convert(Filter.decode("displayName eq \"engineers\""), scimConfig));
  }
}
