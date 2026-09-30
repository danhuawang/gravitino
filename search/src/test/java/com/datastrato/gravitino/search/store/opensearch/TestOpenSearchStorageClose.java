/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.store.opensearch;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.jupiter.api.Test;
import org.opensearch.client.RestClient;
import org.opensearch.client.opensearch.OpenSearchClient;

class TestOpenSearchStorageClose {

  @Test
  void testCloseReleasesRestClientAndSearchFailsWithoutNullPointer() throws Exception {
    OpenSearchStorage storage = new OpenSearchStorage();
    RestClient restClient = mock(RestClient.class);
    FieldUtils.writeField(storage, "client", mock(OpenSearchClient.class), true);
    FieldUtils.writeField(storage, "restClient", restClient, true);
    FieldUtils.writeField(storage, "queryExecutor", Executors.newSingleThreadExecutor(), true);

    storage.close();

    verify(restClient).close();
    assertThrows(
        RejectedExecutionException.class,
        () -> storage.search("lake", null, null, ImmutableList.of(), 10, 0));
  }
}
