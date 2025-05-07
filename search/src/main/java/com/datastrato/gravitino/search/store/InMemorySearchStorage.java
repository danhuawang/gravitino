/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.store;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.parser.Condition;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.util.List;
import org.apache.gravitino.Config;

/**
 * InMemorySearchStorage is an in-memory implementation of SearchStorage. It is used for development
 * and tests.
 */
public class InMemorySearchStorage implements SearchStorage {

  private List<SearchEntityPO> searchEntities;

  public List<SearchEntityPO> getSearchEntities() {
    return searchEntities;
  }

  @Override
  public void initialize(Config config) {
    searchEntities = Lists.newArrayList();
  }

  @Override
  public void write(List<SearchEntityPO> entities) {
    searchEntities.addAll(entities);
  }

  @Override
  public void close() throws IOException {}

  @VisibleForTesting
  public void clear() {
    searchEntities.clear();
  }

  @Override
  public List<SearchEntitiesDTO> search(
      String keyword, Condition filter, int pageSize, int pageNum) {
    return null;
  }

  @Override
  public void beginTransaction() {}

  @Override
  public void commit() {}
}
