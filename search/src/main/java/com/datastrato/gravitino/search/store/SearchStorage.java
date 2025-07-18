/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.store;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.parser.Condition;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import java.io.Closeable;
import java.util.List;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;

public interface SearchStorage extends Closeable {

  /**
   * Initialize the storage. This method should be called before any other methods are called.
   *
   * @param config The configuration to be used for initialization.
   */
  void initialize(Config config);

  /**
   * Write a list of entities to the storage. The entities will be indexed in OpenSearch or stored
   * in other storage like JDBC.
   *
   * @param entities The entity list to be written to the storage.
   */
  void write(List<SearchEntityPO> entities, WriteContext writeContext);

  /**
   * Search the storage for entities that match the given keyword and filter.
   *
   * @param metalake The metalake to search in.
   * @param keyword The keyword to search for.
   * @param filter The filter to apply to the search.
   * @param fields The fields to return in the search results. If null or empty, all fields will be
   *     returned.
   * @param pageSize The number of results to return per a page.
   * @param pageNum The page number to return.
   */
  List<SearchEntitiesDTO> search(
      String metalake,
      String keyword,
      Condition filter,
      List<String> fields,
      int pageSize,
      int pageNum);

  /**
   * Search the storage for entities that match the given keyword and filter.
   *
   * @param metalake The metalake to search in.
   * @param keyword The keyword to search for.
   * @param filter The filter to apply to the search.
   * @param fields The fields to return in the search results. If null or empty, all fields will be
   * @return a SearchDataSource object that contains the search results.
   */
  SearchDataSource search(String metalake, String keyword, Condition filter, List<String> fields);

  /**
   * Delete entities from the storage based on their IDs, metalake, and entity type.
   *
   * @param metalake The metalake from which to delete entities.
   * @param entityIds The list of entity IDs to be deleted.
   * @param entityType The type of entities to be deleted, such as TABLE, CATALOG, SCHEMA, etc.
   */
  void delete(String metalake, List<Long> entityIds, Entity.EntityType entityType);

  /**
   * Begin a transaction. By default, the storage is in auto-commit mode. If we want to do a
   * transaction, we need to call this method to begin a transaction. This is helpful when you are
   * doing rolling upgrades or when you want to switch the alias of the indices to a new one.
   */
  default long beginTransaction(String metalake) {
    return -1;
  }

  /** Commit the changes in a transaction to the storage */
  default void commit(long transactionId) {}

  default void rollback(long transactionId) {}
}
