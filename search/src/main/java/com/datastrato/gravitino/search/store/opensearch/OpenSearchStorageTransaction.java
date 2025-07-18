/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.store.opensearch;

import java.util.Set;

public class OpenSearchStorageTransaction {
  private final long transactionId;
  private final String metalake;
  private final Set<String> indexNames;

  public OpenSearchStorageTransaction(long transactionId, String metalake, Set<String> indexNames) {
    this.transactionId = transactionId;
    this.indexNames = indexNames;
    this.metalake = metalake;
  }

  public long getTransactionId() {
    return transactionId;
  }

  public Set<String> getIndexNames() {
    return indexNames;
  }

  public String getMetalake() {
    return metalake;
  }
}
