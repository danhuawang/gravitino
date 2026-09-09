/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics.storage.relational;

import lombok.Getter;

/** Stores the number of distinct active associations for one catalog. */
@Getter
public class CatalogAssociationCountPO {
  private Long catalogId;
  private Long associatedCount;
}
