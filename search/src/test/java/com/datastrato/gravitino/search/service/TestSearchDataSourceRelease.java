/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.search.parser.Condition;
import com.datastrato.gravitino.search.store.SearchDataSource;
import com.datastrato.gravitino.search.store.SearchStorage;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.jupiter.api.Test;

/**
 * Verifies every consumer of a backend cursor releases it, so an OpenSearch scroll context is never
 * left alive until its timeout.
 */
class TestSearchDataSourceRelease {
  private static final String METALAKE = "lake";

  @Test
  void testRemovingMetadataByQueryReleasesItsCursor() throws Exception {
    SearchDataSource source = exhaustedSource();
    SearchStorage storage = storageReturning(source);
    SearchService service = mock(SearchService.class, CALLS_REAL_METHODS);
    FieldUtils.writeField(service, "storage", storage, true);

    service.removeMetadataByQuery(METALAKE, Condition.greater("update_time", "1"));

    verify(source).close();
  }

  @Test
  void testFindingEntitiesByTagReleasesItsCursor() {
    SearchDataSource source = exhaustedSource();

    TagAssociationSearchEntitySource.ofIndexedEntities(METALAKE, "tag", storageReturning(source))
        .addChildEntitySources();

    verify(source).close();
  }

  @Test
  void testFindingEntitiesByPolicyReleasesItsCursor() {
    SearchDataSource source = exhaustedSource();

    PolicyAssociationSearchEntitySource.ofIndexedEntities(
            METALAKE, "policy", storageReturning(source))
        .addChildEntitySources();

    verify(source).close();
  }

  private static SearchDataSource exhaustedSource() {
    SearchDataSource source = mock(SearchDataSource.class);
    when(source.nextBatch()).thenReturn(SearchDataSource.Result.empty());
    return source;
  }

  private static SearchStorage storageReturning(SearchDataSource source) {
    SearchStorage storage = mock(SearchStorage.class);
    when(storage.search(eq(METALAKE), any(), any(), any())).thenReturn(source);
    return storage;
  }
}
