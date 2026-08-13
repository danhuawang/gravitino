/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim;

import org.apache.commons.lang3.StringUtils;

/** Shared helpers for SCIM modules. */
public final class ScimUtils {

  private ScimUtils() {}

  /**
   * Returns {@code null} when {@code value} is null or blank; otherwise returns {@code value}.
   *
   * @param value input string
   * @return normalized string, or null
   */
  public static String blankToNull(String value) {
    return StringUtils.isBlank(value) ? null : value;
  }
}
