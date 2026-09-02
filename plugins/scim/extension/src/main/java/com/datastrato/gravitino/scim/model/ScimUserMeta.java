/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.model;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/** SCIM user metadata domain object. */
@Getter
@EqualsAndHashCode
@ToString
@Builder(setterPrefix = "with")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ScimUserMeta {
  private final long userId;
  private final String userName;
  private final String externalId;
  private final boolean enabled;
}
