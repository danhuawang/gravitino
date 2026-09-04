/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.store.opensearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.BulkIndexByScrollFailure;
import org.opensearch.client.opensearch._types.Conflicts;
import org.opensearch.client.opensearch.core.UpdateByQueryRequest;
import org.opensearch.client.opensearch.core.UpdateByQueryResponse;

class TestOpenSearchStorageCleanup {

  @Test
  void testCleanupRetriesUntilResponseIsComplete() throws Exception {
    OpenSearchClient client = mock(OpenSearchClient.class);
    UpdateByQueryResponse conflict = cleanupResponse(1, false, false);
    UpdateByQueryResponse timedOut = cleanupResponse(0, true, false);
    UpdateByQueryResponse failed = cleanupResponse(0, false, true);
    UpdateByQueryResponse success = cleanupResponse(0, false, false);
    when(client.updateByQuery(any(UpdateByQueryRequest.class)))
        .thenThrow(new IOException("temporary transport failure"))
        .thenReturn(conflict, timedOut, failed, success);

    OpenSearchStorage storage = storageWithClient(client, 5);
    storage.removeCatalogPropertiesFromExistingIndices();

    ArgumentCaptor<UpdateByQueryRequest> requestCaptor =
        ArgumentCaptor.forClass(UpdateByQueryRequest.class);
    verify(client, times(5)).updateByQuery(requestCaptor.capture());
    UpdateByQueryRequest request = requestCaptor.getValue();
    assertEquals(ImmutableList.of("*_catalog_entity_index*"), request.index());
    assertEquals(Conflicts.Proceed, request.conflicts());
    assertTrue(request.allowNoIndices());
    assertTrue(request.ignoreUnavailable());
    assertTrue(request.refresh());
    assertTrue(request.query().isNested());
    assertEquals("entity_properties", request.query().nested().path());
    assertTrue(request.query().nested().ignoreUnmapped());
    assertTrue(request.query().nested().query().isExists());
    assertEquals("entity_properties.key", request.query().nested().query().exists().field());
    assertEquals("ctx._source.remove('entity_properties')", request.script().inline().source());
  }

  @Test
  void testCleanupFailsWhenVersionConflictsPersist() throws Exception {
    OpenSearchClient client = mock(OpenSearchClient.class);
    when(client.updateByQuery(any(UpdateByQueryRequest.class)))
        .thenReturn(cleanupResponse(1, false, false));

    OpenSearchStorage storage = storageWithClient(client, 2);

    assertThrows(RuntimeException.class, storage::removeCatalogPropertiesFromExistingIndices);
    verify(client, times(2)).updateByQuery(any(UpdateByQueryRequest.class));
  }

  private static UpdateByQueryResponse cleanupResponse(
      long versionConflicts, boolean timedOut, boolean failed) {
    ImmutableList<BulkIndexByScrollFailure> failures =
        failed ? ImmutableList.of(mock(BulkIndexByScrollFailure.class)) : ImmutableList.of();
    return new UpdateByQueryResponse.Builder()
        .versionConflicts(versionConflicts)
        .timedOut(timedOut)
        .failures(failures)
        .build();
  }

  private static OpenSearchStorage storageWithClient(OpenSearchClient client, int maxRetries)
      throws Exception {
    OpenSearchStorage storage = new OpenSearchStorage();
    setField(storage, "client", client);
    setField(storage, "maxRetries", maxRetries);
    setField(storage, "retryBackoffMs", 0L);
    return storage;
  }

  private static void setField(OpenSearchStorage storage, String fieldName, Object value)
      throws Exception {
    Field field = OpenSearchStorage.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(storage, value);
  }
}
