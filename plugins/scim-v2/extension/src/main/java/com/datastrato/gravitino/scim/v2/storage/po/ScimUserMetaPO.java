/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.storage.po;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** Persistent object for SCIM v2 user metadata rows in {@code scim_user_meta}. */
@Getter
@EqualsAndHashCode
@ToString
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(setterPrefix = "with")
public class ScimUserMetaPO {
  private Long userId;
  private String userName;
  private String externalId;
  private Boolean enabled;
  private Long currentVersion;
  private Long lastVersion;
  private Long deletedAt;
}
