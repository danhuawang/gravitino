/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.po;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** Persistent object for SCIM group metadata rows in {@code scim_group_meta}. */
@Getter
@EqualsAndHashCode
@ToString
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(setterPrefix = "with")
public class ScimGroupMetaPO {
  private Long groupId;
  private String groupName;
  private String groupComment;
  private String externalId;
  private Long currentVersion;
  private Long lastVersion;
  private Long deletedAt;
}
