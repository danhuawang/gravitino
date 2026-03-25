/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

abstract class LeafSearchEntitySource implements SearchEntitySource {
  private static final Logger LOG = LoggerFactory.getLogger(LeafSearchEntitySource.class);

  protected List<SearchEntityIdentifier> metadataList;
  protected boolean finished = false;
  protected int currentIndex = 0;
  protected List<SearchEntityIdentifier> processFailedList = new ArrayList<>();

  protected LeafSearchEntitySource(List<SearchEntityIdentifier> metadataList) {
    this.metadataList = metadataList;
  }

  @Override
  public List<SearchEntityPO> nextBatch(int batchSize) {
    int startIndex = -1;

    synchronized (this) {
      if (finished) {
        return new ArrayList<>();
      }

      // check if we have already processed all the metadata
      if (currentIndex >= metadataList.size()) {
        finished = true;
        return new ArrayList<>();
      }
      // consume the current batch and move the next index
      startIndex = currentIndex;
      currentIndex += batchSize;
    }

    List<SearchEntityPO> batch = new ArrayList<>();
    for (int i = startIndex; i < Math.min(startIndex + batchSize, metadataList.size()); i++) {
      SearchEntityIdentifier searchEntityIdentifier = metadataList.get(i);
      try {
        batch.add(getSearchEntityPO(searchEntityIdentifier));
      } catch (Exception e) {
        synchronized (this) {
          processFailedList.add(searchEntityIdentifier);
        }
        LOG.error("Failed to process metadata: {}", searchEntityIdentifier.entityIdent(), e);
      }
    }

    return batch;
  }

  @Override
  public int approximateEntityCount() {
    return metadataList.size();
  }

  @Override
  public boolean finished() {
    return finished;
  }

  @Override
  public List<SearchEntityIdentifier> getProcessFailedEntities() {
    return processFailedList;
  }

  protected abstract SearchEntityPO getSearchEntityPO(
      SearchEntityIdentifier searchEntityIdentifier);
}
