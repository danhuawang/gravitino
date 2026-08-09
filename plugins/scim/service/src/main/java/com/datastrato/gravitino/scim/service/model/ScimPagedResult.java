/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

/** SCIM list/filter query result for repository adapters. */
@Getter
@ToString
@EqualsAndHashCode
@AllArgsConstructor
@Accessors(fluent = true)
public final class ScimPagedResult<T> {

  private final long totalCount;
  private final List<T> items;
}
