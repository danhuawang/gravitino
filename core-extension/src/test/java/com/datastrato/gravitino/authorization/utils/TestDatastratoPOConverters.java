/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for {@link DatastratoPOConverters}. */
public class TestDatastratoPOConverters {

  /** Verifies null and blank JSON array entries are filtered out. */
  @Test
  public void testParseNameArrayFiltersNullAndBlank() {
    assertEquals(
        List.of("analysts", "contractors"),
        DatastratoPOConverters.parseNameArray("[\"analysts\", null, \"\", \"contractors\"]"));
  }

  /** Verifies blank or missing JSON input yields an empty list. */
  @Test
  public void testParseNameArrayBlankInput() {
    assertEquals(Collections.emptyList(), DatastratoPOConverters.parseNameArray(null));
    assertEquals(Collections.emptyList(), DatastratoPOConverters.parseNameArray(""));
  }

  /** Verifies Directory Overview {@code createTime} is read from audit_info JSON. */
  @Test
  public void testParseCreateTimeFromAuditInfo() {
    Instant createTime = Instant.parse("2025-09-14T00:00:00Z");
    assertEquals(
        createTime,
        DatastratoPOConverters.parseCreateTime(
            "{\"creator\":\"admin\",\"createTime\":\"2025-09-14T00:00:00Z\"}"));
  }

  /** Verifies blank or invalid audit_info does not break Overview. */
  @Test
  public void testParseCreateTimeBlankOrInvalid() {
    assertNull(DatastratoPOConverters.parseCreateTime(null));
    assertNull(DatastratoPOConverters.parseCreateTime(""));
    assertNull(DatastratoPOConverters.parseCreateTime("not-json"));
    assertNull(DatastratoPOConverters.parseCreateTime("{\"creator\":\"admin\"}"));
  }

  /** Verifies metalake-only Created picks the earliest audit_info across metalakes. */
  @Test
  public void testAuditInfoWithEarliestCreateTime() {
    String later = "{\"createTime\":\"2026-09-18T05:46:00Z\"}";
    String earlier = "{\"creator\":\"admin\",\"createTime\":\"2026-01-01T00:00:00Z\"}";
    assertEquals(
        earlier, DatastratoPOConverters.auditInfoWithEarliestCreateTime(List.of(later, earlier)));
    assertEquals(
        earlier,
        DatastratoPOConverters.auditInfoWithEarliestCreateTime(List.of("not-json", earlier)));
    assertNull(DatastratoPOConverters.auditInfoWithEarliestCreateTime(List.of()));
    assertNull(DatastratoPOConverters.auditInfoWithEarliestCreateTime(null));
  }

  /** Verifies Directory Local create path can serialize create audit_info. */
  @Test
  public void testNewCreateAuditInfoJson() {
    String json = DatastratoPOConverters.newCreateAuditInfoJson();
    assertNotNull(DatastratoPOConverters.parseCreateTime(json));
    assertTrue(json.contains("creator"));
  }
}
