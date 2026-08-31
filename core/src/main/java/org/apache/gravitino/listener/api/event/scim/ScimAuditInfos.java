/*
 * Copyright 2026 Datastrato Inc.
 */

package org.apache.gravitino.listener.api.event.scim;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;

/** Shared customInfo keys/values for SCIM audit events. */
final class ScimAuditInfos {

  static final String INFO_RESOURCE_TYPE = "resourceType";
  static final String INFO_ID = "id";
  static final String INFO_EXTERNAL_ID = "externalId";
  static final String INFO_SOURCE = "source";
  static final String INFO_REASON = "reason";
  static final String INFO_CHANGES = "changes";
  static final String INFO_MEMBERS_ADDED = "membersAdded";
  static final String INFO_MEMBERS_REMOVED = "membersRemoved";
  static final String INFO_COUNT = "count";

  static final String RESOURCE_USER = "User";
  static final String RESOURCE_GROUP = "Group";
  static final String SOURCE_SCIM = "scim";

  private static final int MAX_REASON_LENGTH = 512;

  private ScimAuditInfos() {}

  static Map<String, String> of(
      String resourceType, @Nullable String resourceId, @Nullable String externalId) {
    return of(resourceType, resourceId, externalId, null);
  }

  static Map<String, String> of(
      String resourceType,
      @Nullable String resourceId,
      @Nullable String externalId,
      @Nullable Map<String, String> extras) {
    ImmutableMap.Builder<String, String> builder =
        ImmutableMap.<String, String>builder()
            .put(INFO_SOURCE, SOURCE_SCIM)
            .put(INFO_RESOURCE_TYPE, resourceType);
    if (StringUtils.isNotBlank(resourceId)) {
      builder.put(INFO_ID, resourceId);
    }
    if (StringUtils.isNotBlank(externalId)) {
      builder.put(INFO_EXTERNAL_ID, externalId);
    }
    if (extras != null) {
      extras.forEach(
          (key, value) -> {
            if (StringUtils.isNotBlank(key) && StringUtils.isNotBlank(value)) {
              builder.put(key, value);
            }
          });
    }
    return builder.build();
  }

  static Map<String, String> ofFailure(
      String resourceType,
      @Nullable String resourceId,
      @Nullable String externalId,
      @Nullable Exception exception) {
    String reason = reasonFrom(exception);
    if (reason == null) {
      return of(resourceType, resourceId, externalId);
    }
    return of(resourceType, resourceId, externalId, ImmutableMap.of(INFO_REASON, reason));
  }

  @Nullable
  static String reasonFrom(@Nullable Exception exception) {
    if (exception == null) {
      return null;
    }
    String message = exception.getMessage();
    if (StringUtils.isNotBlank(message)) {
      return truncate(message.trim());
    }
    return exception.getClass().getSimpleName();
  }

  private static String truncate(String value) {
    if (value.length() <= MAX_REASON_LENGTH) {
      return value;
    }
    return value.substring(0, MAX_REASON_LENGTH);
  }
}
