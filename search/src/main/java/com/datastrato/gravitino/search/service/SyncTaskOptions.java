/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import lombok.Builder;
import lombok.Getter;

/** SyncTaskOptions is used to encapsulate options for synchronization tasks. */
@Builder(toBuilder = true, builderClassName = "SyncTaskOptionsBuilder", setterPrefix = "with")
@Getter
public class SyncTaskOptions {
  public static final SyncTaskOptions DEFAULT = SyncTaskOptions.builder().build();

  private final Long transactionId;
}
