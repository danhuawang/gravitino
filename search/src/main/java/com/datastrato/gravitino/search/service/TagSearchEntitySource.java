/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import java.util.ArrayList;
import java.util.List;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class TagSearchEntitySource extends ParentEntitySource {
  private static final Logger LOG = LoggerFactory.getLogger(TagSearchEntitySource.class);

  private final String metalake;
  private final String tagName;

  public TagSearchEntitySource(String metalake, String tagname) {
    // We use NameIdentifier.of(metalake) to create the search entity identifier for the Tag.
    super(SearchEntityIdentifier.of(NameIdentifier.of(metalake), EntityType.METALAKE), true);
    this.metalake = metalake;
    this.tagName = tagname;
  }

  @Override
  protected List<SearchEntitySource> createChildEntitySources() {
    try {
      MetadataObject[] objects =
          GravitinoEnv.getInstance().tagDispatcher().listMetadataObjectsForTag(metalake, tagName);

      List<SearchEntitySource> searchEntitySources = new ArrayList<>();
      for (MetadataObject object : objects) {
        SearchEntityIdentifier identifier = new SearchEntityIdentifier(object, metalake);
        SearchEntitySource source = SearchEntitySource.createSearchEntitySource(identifier, true);
        searchEntitySources.add(source);
      }

      return searchEntitySources;
    } catch (Exception e) {
      LOG.error(
          "Failed to initialize child sources for tag: {} in metalake: {}", tagName, metalake, e);
      // The exception will be caught by addChildEntitySources.
      throw e;
    }
  }

  @Override
  protected SearchEntityPO getSearchEntityPO(SearchEntityIdentifier searchEntityIdentifier) {
    throw new GravitinoRuntimeException("Should never come here");
  }

  @Override
  protected boolean hasSelfSearchEntityPO() {
    return false;
  }

  protected boolean addChildEntitySources() {
    try {
      childSources = createChildEntitySources();
      currentChildIndex = 0;
      synchronized (entityCountMap) {
        int total = 0;
        for (SearchEntitySource childSource : childSources) {
          total += childSource.approximateEntityCount();
        }

        entityCountMap.put(composeKey(), total);
      }
      return !childSources.isEmpty();
    } catch (Exception e) {
      processFailedList.add(searchEntityIdentifier);
      LOG.error("Failed to create source from entity for metadata: {}", composeKey(), e);
    }
    return false;
  }

  private String composeKey() {
    return metalake + "@" + tagName;
  }

  @Override
  public int approximateEntityCount() {
    synchronized (entityCountMap) {
      return entityCountMap.getOrDefault(composeKey(), Integer.MAX_VALUE);
    }
  }
}
