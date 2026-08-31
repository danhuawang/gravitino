/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.parser.Condition;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.store.SearchDataSource;
import com.datastrato.gravitino.search.store.WriteContext;
import com.datastrato.gravitino.search.utils.SearchEntityCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RebuildMetadataFinishHandler implements SyncTaskFinishedHandler {
  private static final Logger LOG = LoggerFactory.getLogger(RebuildMetadataFinishHandler.class);

  @Override
  public boolean onTaskFinished(SyncTask task) {
    NameIdentifier nameIdentifier = task.searchEntityIdentifier.entityIdent();
    String metalake = NameIdentifierUtil.getMetalake(nameIdentifier);
    long transId = task.options.getTransactionId();
    try {
      // Search all entities with update_time greater than txId
      SearchDataSource source =
          task.service.query(
              metalake, null, Condition.greater("update_time", String.valueOf(transId)), null);

      SearchDataSource.Result result = source.nextBatch();
      while (!result.isEmpty()) {
        // Write the data to the new index
        Entity.EntityType entityType = result.entityType();
        Class<? extends SearchEntityPO> cl = SearchEntityCodec.ENTITY_TYPE_TO_CLASS.get(entityType);
        List<SearchEntityPO> allEntities =
            new ArrayList<>(
                result.entities().stream()
                    .map(SearchEntityCodec.INSTANCE::serialize)
                    .map(e -> SearchEntityCodec.INSTANCE.deserialize(e, cl))
                    .collect(Collectors.toList()));
        task.service.write(allEntities, WriteContext.builder().withTransactionId(transId).build());
        result = source.nextBatch();
      }

      task.service.commit(transId);
    } catch (Exception e) {
      LOG.error("Failed to rebuild metadata for metalake: {}", metalake, e);
      task.service.rollback(transId);
      throw e;
    }
    return true;
  }
}
