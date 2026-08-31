/*
 * Copyright 2026 Datastrato Inc.
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

class TestScimGroupFilter {

  private ScimConfig scimConfig;

  @BeforeEach
  void setUp() {
    scimConfig = new ScimConfig(Map.of(), new Config() {});
  }

  @Test
  void testIdEq() throws Exception {
    ScimGroupFilter criteria = ScimGroupFilter.convert(Filter.decode("id eq \"123\""), scimConfig);
    assertEquals("123", criteria.id().orElseThrow());
    assertFalse(criteria.displayName().isPresent());
    assertFalse(criteria.externalId().isPresent());
  }

  @Test
  void testIdAndDisplayName() throws Exception {
    ScimGroupFilter criteria =
        ScimGroupFilter.convert(
            Filter.decode("id eq \"1\" and displayName eq \"engineers\""), scimConfig);
    assertEquals("1", criteria.id().orElseThrow());
    assertEquals("engineers", criteria.displayName().orElseThrow());
  }

  @Test
  void testExternalIdEq() throws Exception {
    ScimGroupFilter criteria =
        ScimGroupFilter.convert(Filter.decode("externalId eq \"ext-g1\""), scimConfig);
    assertEquals("ext-g1", criteria.externalId().orElseThrow());
    assertFalse(criteria.displayName().isPresent());
  }

  @Test
  void testDisplayNameEq() throws Exception {
    ScimGroupFilter criteria =
        ScimGroupFilter.convert(Filter.decode("displayName eq \"engineers\""), scimConfig);
    assertEquals("engineers", criteria.displayName().orElseThrow());
    assertFalse(criteria.externalId().isPresent());
  }

  @Test
  void testAndFilter() throws Exception {
    ScimGroupFilter criteria =
        ScimGroupFilter.convert(
            Filter.decode("externalId eq \"ext-g1\" and displayName eq \"engineers\""), scimConfig);
    assertEquals("ext-g1", criteria.externalId().orElseThrow());
    assertEquals("engineers", criteria.displayName().orElseThrow());
  }

  @Test
  void testUnsupportedOp() {
    assertThrows(
        UnsupportedFilterException.class,
        () -> ScimGroupFilter.convert(Filter.decode("externalId co \"abc\""), scimConfig));
  }

  @Test
  void testUserNameUnsupported() {
    assertThrows(
        UnsupportedFilterException.class,
        () -> ScimGroupFilter.convert(Filter.decode("userName eq \"alice\""), scimConfig));
  }
}
