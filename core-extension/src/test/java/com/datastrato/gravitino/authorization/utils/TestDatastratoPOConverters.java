/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
