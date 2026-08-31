/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim;

import org.apache.commons.lang3.StringUtils;

/** Shared helpers for SCIM modules. */
public final class ScimUtils {

  /** Path prefix for metalake-scoped SCIM resources on port 9201. */
  public static final String METALAKE_SCIM_PREFIX = "/scim/v2/metalakes/";

  private ScimUtils() {}

  /**
   * Returns the metalake-scoped SCIM base path shown in provisioning overview responses.
   *
   * @param metalakeName metalake name
   * @return path such as {@code /scim/v2/metalakes/acme/}
   */
  public static String metalakeBasePath(String metalakeName) {
    return METALAKE_SCIM_PREFIX + metalakeName + "/";
  }

  /**
   * Returns {@code null} when {@code value} is null or blank; otherwise returns {@code value}.
   *
   * @param value input string
   * @return normalized string, or null
   */
  public static String blankToNull(String value) {
    return StringUtils.isBlank(value) ? null : value;
  }

  /**
   * Returns {@code "unknown"} when {@code value} is null or blank; otherwise returns {@code value}.
   *
   * @param value input string
   * @return non-blank string suitable for identifiers
   */
  public static String blankToUnknown(String value) {
    return StringUtils.isBlank(value) ? "unknown" : value;
  }
}
