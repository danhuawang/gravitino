/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.store;

import lombok.Builder;
import lombok.Getter;

/**
 * The WriteContext class is used to encapsulate the context of a write operation in the search
 * storage.
 */
@Builder(toBuilder = true, builderClassName = "WriteContextBuilder", setterPrefix = "with")
@Getter
public class WriteContext {

  public static final WriteContext DEFAULT = WriteContext.builder().build();
  // TransactionId is used to track the transaction for which the write operation is being
  // performed. if this is null, it means the write operation is not part of any transaction.
  private final Long transactionId;
}
