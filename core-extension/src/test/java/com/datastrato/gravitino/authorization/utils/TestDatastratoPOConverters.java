/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
