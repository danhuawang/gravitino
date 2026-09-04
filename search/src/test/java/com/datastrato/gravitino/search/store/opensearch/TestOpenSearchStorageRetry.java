/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.store.opensearch;

import java.util.Collections;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Conflicts;
import org.opensearch.client.opensearch.core.UpdateByQueryRequest;
import org.opensearch.client.opensearch.core.UpdateByQueryResponse;
import org.opensearch.client.opensearch.indices.DeleteIndexRequest;
import org.opensearch.client.opensearch.indices.DeleteIndexResponse;
import org.opensearch.client.opensearch.indices.GetIndexRequest;
import org.opensearch.client.opensearch.indices.GetIndexResponse;
import org.opensearch.client.opensearch.indices.IndexState;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;

class TestOpenSearchStorageRetry {

  @Test
  void testUpdateMetalakeInUseRetriesVersionConflicts() throws Exception {
    OpenSearchStorage storage = newStorageWithMockClient();
    OpenSearchClient client = storage.getClient();
    Mockito.when(client.updateByQuery(Mockito.any(UpdateByQueryRequest.class)))
        .thenReturn(updateResponse(1L), updateResponse(0L));

    storage.updateMetalakeInUse("test", false);

    Mockito.verify(client, Mockito.times(2))
        .updateByQuery(
            Mockito.<UpdateByQueryRequest>argThat(
                request -> request.conflicts() == Conflicts.Proceed));
  }

  @Test
  void testUpdateMetalakeInUseFailsAfterPersistentVersionConflicts() throws Exception {
    OpenSearchStorage storage = newStorageWithMockClient();
    OpenSearchClient client = storage.getClient();
    Mockito.when(client.updateByQuery(Mockito.any(UpdateByQueryRequest.class)))
        .thenReturn(updateResponse(1L));

    RuntimeException exception =
        Assertions.assertThrows(
            RuntimeException.class, () -> storage.updateMetalakeInUse("test", false));

    Assertions.assertTrue(exception.getCause().getMessage().contains("version conflicts: 1"));
    Mockito.verify(client, Mockito.times(2)).updateByQuery(Mockito.any(UpdateByQueryRequest.class));
  }

  @Test
  void testDeleteMetalakeReportsUnacknowledgedIndices() throws Exception {
    OpenSearchStorage storage = newStorageWithMockClient();
    OpenSearchClient client = storage.getClient();
    OpenSearchIndicesClient indicesClient = Mockito.mock(OpenSearchIndicesClient.class);
    GetIndexResponse getIndexResponse = Mockito.mock(GetIndexResponse.class);
    DeleteIndexResponse deleteIndexResponse =
        DeleteIndexResponse.of(builder -> builder.acknowledged(false));
    String metalake = "test";
    String indexName = "test_catalog_entity_index_1";
    Mockito.when(client.indices()).thenReturn(indicesClient);
    Mockito.when(getIndexResponse.result())
        .thenReturn(Collections.singletonMap(indexName, Mockito.mock(IndexState.class)));
    Mockito.when(indicesClient.get(Mockito.any(GetIndexRequest.class)))
        .thenReturn(getIndexResponse);
    Mockito.when(indicesClient.delete(Mockito.any(DeleteIndexRequest.class)))
        .thenReturn(deleteIndexResponse);
    RuntimeException exception =
        Assertions.assertThrows(RuntimeException.class, () -> storage.deleteMetalake(metalake));

    Assertions.assertTrue(exception.getMessage().contains(metalake));
    Assertions.assertTrue(exception.getMessage().contains(indexName));
  }

  private OpenSearchStorage newStorageWithMockClient() throws IllegalAccessException {
    OpenSearchStorage storage = new OpenSearchStorage();
    FieldUtils.writeField(storage, "client", Mockito.mock(OpenSearchClient.class), true);
    FieldUtils.writeField(storage, "maxRetries", 2, true);
    FieldUtils.writeField(storage, "retryBackoffMs", 0L, true);
    return storage;
  }

  private UpdateByQueryResponse updateResponse(long versionConflicts) {
    return UpdateByQueryResponse.of(
        builder ->
            builder
                .timedOut(false)
                .failures(Collections.emptyList())
                .versionConflicts(versionConflicts));
  }
}
