/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import static com.datastrato.gravitino.search.utils.FilterConditionUtils.createRemovedEntityCondition;
import static com.datastrato.gravitino.search.utils.FilterConditionUtils.createUpdateTimeCondition;

import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RemoveDeletedMetadata implements SyncTaskFinishedHandler {
  private static final Logger LOG = LoggerFactory.getLogger(RemoveDeletedMetadata.class);

  @Override
  public boolean onTaskFinished(SyncTask task) {
    try {
      if (!task.source.getProcessFailedEntities().isEmpty()) {
        LOG.warn(
            "Task {} finished with failure, do not remove the metadata for {}",
            task.taskId,
            task.searchEntityIdentifier);
        return true;
      }

      NameIdentifier nameIdentifier = task.searchEntityIdentifier.entityIdent();
      Entity.EntityType entityType = task.searchEntityIdentifier.entityType();
      String metalake = NameIdentifierUtil.getMetalake(nameIdentifier);

      task.service.removeMetadataByQuery(
          metalake,
          entityType == Entity.EntityType.METALAKE
              ? createUpdateTimeCondition(task.createTime)
              : createRemovedEntityCondition(nameIdentifier, task.cascade, task.createTime));
    } catch (Exception e) {
      LOG.error(
          "Error removing deleted data from storage for task {}: {}",
          task.taskId,
          task.searchEntityIdentifier,
          e);
      return false;
    }

    return true;
  }
}
