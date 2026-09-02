/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim;

import org.apache.commons.lang3.StringUtils;

/** Shared helpers for SCIM modules. */
public final class ScimUtils {

  /** Path prefix for instance-scoped SCIM resources on port 9201. */
  public static final String SCIM_PREFIX = "/scim/v2/";

  /**
   * Placeholder metalake name for SCIM audit events. OSS SCIM event APIs require a non-empty
   * metalake; instance-scoped SCIM uses this sentinel instead of a real metalake.
   */
  public static final String INSTANCE_AUDIT_METALAKE = "_instance";

  private ScimUtils() {}

  /** Returns the instance-scoped SCIM base path shown in provisioning overview responses. */
  public static String scimBasePath() {
    return SCIM_PREFIX;
  }

  /** Returns {@code null} when {@code value} is null or blank; otherwise returns {@code value}. */
  public static String blankToNull(String value) {
    return StringUtils.isBlank(value) ? null : value;
  }

  /**
   * Returns {@code "unknown"} when {@code value} is null or blank; otherwise returns {@code value}.
   */
  public static String blankToUnknown(String value) {
    return StringUtils.isBlank(value) ? "unknown" : value;
  }
}
