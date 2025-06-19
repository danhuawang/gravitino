/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

abstract class ParentEntitySource implements SearchEntitySource {
  private static final Logger LOG = LoggerFactory.getLogger(ParentEntitySource.class);

  // The static map records the total number of entities per identifier (including children).
  // It is initially empty and populated after the first sync, so the count may be inaccurate.
  // The sync task splits based on this number and task concurrency.
  // todo Need to improve it by using Gravitino server metrics, if available.
  protected static final TreeMap<String, Integer> entityCountMap = new TreeMap<>();
  private static long lastCleanupTime = System.currentTimeMillis();
  private static final int ENTITY_COUNT_MAP_CLEAN_INTERVAL = 24 * 3600 * 1000; // 24 hours

  protected final SearchEntityIdentifier searchEntityIdentifier;
  protected final boolean cascading;
  protected boolean finished = false;
  protected List<SearchEntityIdentifier> processFailedList = new ArrayList<>();

  protected List<SearchEntitySource> childSources = new ArrayList<>();
  protected int currentChildIndex = -1;

  public ParentEntitySource(SearchEntityIdentifier searchEntityIdentifier, boolean cascading) {
    this.searchEntityIdentifier = searchEntityIdentifier;
    this.cascading = cascading;
  }

  @Override
  public List<SearchEntityPO> nextBatch(int batchSize) {
    int index = -1;
    List<SearchEntityPO> batch = new ArrayList<>(batchSize);
    synchronized (this) {
      if (finished) {
        return batch;
      }

      if (!cascading) {
        addSelfEntityPOToList(batch);
        finished = true;
        return batch;
      }

      // the first time we call this method, we need to initialize the child sources, and put self
      // entity po to batch
      if (currentChildIndex == -1) {
        if (!addSelfEntityPOToList(batch)) {
          // if we fail to get the self entity po, we need to return empty batch
          finished = true;
          return batch;
        }

        if (!addChildEntitySources()) {
          // if we fail to initialize the child sources, we need to return empty batch
          finished = true;
          return batch;
        }
      }
      index = currentChildIndex;
    }

    addChildEntitySource(index, batchSize, batch);
    return batch;
  }

  // get the self entity po and add it to the batch, if there is an exception return false,
  // otherwise return true
  private boolean addSelfEntityPOToList(List<SearchEntityPO> batch) {
    try {
      if (hasSelfSearchEntityPO()) {
        batch.add(getSearchEntityPO(searchEntityIdentifier));
      }
      return true;
    } catch (Exception e) {
      processFailedList.add(searchEntityIdentifier);
      LOG.error(
          "Failed to retrieve the current entity po for metadata: {}",
          searchEntityIdentifier.entityIdent(),
          e);
    }
    return false;
  }

  // Initialize the child entity sources, if there is an exception return false, otherwise return
  // true
  protected boolean addChildEntitySources() {
    try {
      childSources = createChildEntitySources();
      currentChildIndex = 0;
      synchronized (entityCountMap) {
        entityCountMap.put(searchEntityIdentifier.entityIdent().toString(), childSources.size());
      }
      return !childSources.isEmpty();
    } catch (Exception e) {
      processFailedList.add(searchEntityIdentifier);
      LOG.error(
          "Failed to create source from entity for metadata: {}",
          searchEntityIdentifier.entityIdent(),
          e);
    }
    return false;
  }

  private void addChildEntitySource(int startIndex, int batchSize, List<SearchEntityPO> batch) {
    do {
      // check if the current child source is finished
      SearchEntitySource source = childSources.get(startIndex);
      if (source.finished()) {
        // child source is finished, so we need to move to the next one
        synchronized (this) {
          // there are multiple threads accessing this method, so we need to make sure the index it
          // not move back
          currentChildIndex = Math.max(startIndex + 1, currentChildIndex);
          if (currentChildIndex >= childSources.size()) {
            finished = true;
            return;
          }
          startIndex = currentChildIndex;
        }
        source = childSources.get(startIndex);
      }

      List<SearchEntityPO> nextBatch = source.nextBatch(batchSize - batch.size());
      batch.addAll(nextBatch);
    } while (!finished && batch.size() < batchSize);
  }

  @Override
  public int approximateEntityCount() {
    synchronized (entityCountMap) {
      String key = searchEntityIdentifier.entityIdent().toString();
      int total = entityCountMap.getOrDefault(key, Integer.MAX_VALUE);
      if (total == Integer.MAX_VALUE) {
        // not found in the map, return the max value
        return total;
      }

      if (cascading) {
        // If cascading, find the count of all child entities by the prefix.
        String prefix = key + ".";
        for (Map.Entry<String, Integer> entry : entityCountMap.tailMap(prefix).entrySet()) {
          total += entry.getValue();
        }
      }

      if (System.currentTimeMillis() - lastCleanupTime > ENTITY_COUNT_MAP_CLEAN_INTERVAL) {
        // Clean up the map every 24 hours
        entityCountMap.clear();
        lastCleanupTime = System.currentTimeMillis();
      }
      return total;
    }
  }

  @Override
  public boolean finished() {
    return finished;
  }

  @Override
  public List<SearchEntityIdentifier> getProcessFailedEntities() {
    return processFailedList;
  }

  protected boolean hasSelfSearchEntityPO() {
    return true;
  }

  protected abstract List<SearchEntitySource> createChildEntitySources();

  protected abstract SearchEntityPO getSearchEntityPO(
      SearchEntityIdentifier searchEntityIdentifier);
}
