/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.store.opensearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.apache.gravitino.Entity.EntityType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.ClearScrollRequest;
import org.opensearch.client.opensearch.core.ScrollRequest;
import org.opensearch.client.opensearch.core.ScrollResponse;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;

/** Verifies search scroll cleanup on exhaustion and early close. */
class TestOpenSearchQueryDataSource {
  @Test
  void testExhaustionClearsScroll() throws Exception {
    OpenSearchClient client = mock(OpenSearchClient.class);
    OpenSearchQueryDataSource source = source(client);
    when(client.search(any(SearchRequest.class), eq(SearchEntityPO.class)))
        .thenReturn(response(true, "first"));
    when(client.scroll(any(ScrollRequest.class), eq(SearchEntityPO.class)))
        .thenReturn(
            new ScrollResponse.Builder<SearchEntityPO>()
                .took(1)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .scrollId("last")
                .hits(h -> h.hits(List.of()))
                .build());
    assertFalse(source.nextBatch().isEmpty());
    assertTrue(source.nextBatch().isEmpty());
    source.close();
    assertCleared(client, "last");
  }

  @Test
  void testEarlyCloseClearsScrollAndStopsIteration() throws Exception {
    OpenSearchClient client = mock(OpenSearchClient.class);
    OpenSearchQueryDataSource source = source(client);
    when(client.search(any(SearchRequest.class), eq(SearchEntityPO.class)))
        .thenReturn(response(true, "first"));
    assertFalse(source.nextBatch().isEmpty());
    source.close();
    source.close();
    assertTrue(source.nextBatch().isEmpty());
    assertCleared(client, "first");
  }

  @Test
  void testClearFailureDoesNotMaskOriginalFailure() throws Exception {
    OpenSearchClient client = mock(OpenSearchClient.class);
    OpenSearchQueryDataSource source = source(client);
    when(client.search(any(SearchRequest.class), eq(SearchEntityPO.class)))
        .thenReturn(response(true, "first"));
    when(client.clearScroll(any(ClearScrollRequest.class)))
        .thenThrow(new IOException("unavailable"));
    assertFalse(source.nextBatch().isEmpty());
    source.close();
    assertTrue(source.nextBatch().isEmpty());
  }

  @Test
  void testNonemptyResponseWithoutScrollIdFailsInsteadOfRepeatingSearch() throws Exception {
    OpenSearchClient client = mock(OpenSearchClient.class);
    try (OpenSearchQueryDataSource source = source(client)) {
      when(client.search(any(SearchRequest.class), eq(SearchEntityPO.class)))
          .thenReturn(response(true, null));
      RuntimeException failure = assertThrows(RuntimeException.class, source::nextBatch);
      assertTrue(failure.getCause().getMessage().contains("missing a scroll ID"));
      verify(client, times(1)).search(any(SearchRequest.class), eq(SearchEntityPO.class));
    }
  }

  @Test
  void testEmptyResponseWithoutScrollIdFinishes() throws Exception {
    OpenSearchClient client = mock(OpenSearchClient.class);
    try (OpenSearchQueryDataSource source = source(client)) {
      when(client.search(any(SearchRequest.class), eq(SearchEntityPO.class)))
          .thenReturn(response(false, null));
      assertTrue(source.nextBatch().isEmpty());
      assertTrue(source.nextBatch().isEmpty());
      verify(client, times(1)).search(any(SearchRequest.class), eq(SearchEntityPO.class));
    }
  }

  @Test
  void testScrollFailureClosesCursorWithoutMaskingFailure() throws Exception {
    OpenSearchClient client = mock(OpenSearchClient.class);
    IOException backendFailure = new IOException("scroll unavailable");
    when(client.search(any(SearchRequest.class), eq(SearchEntityPO.class)))
        .thenReturn(response(true, "first"));
    when(client.scroll(any(ScrollRequest.class), eq(SearchEntityPO.class)))
        .thenThrow(backendFailure);
    when(client.clearScroll(any(ClearScrollRequest.class)))
        .thenThrow(new IOException("cleanup unavailable"));
    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () -> {
              try (OpenSearchQueryDataSource source = source(client)) {
                assertFalse(source.nextBatch().isEmpty());
                source.nextBatch();
              }
            });
    assertSame(backendFailure, failure.getCause());
    assertCleared(client, "first");
  }

  @Test
  void testScrollFailureClearsScrollWithoutAnExplicitClose() throws Exception {
    OpenSearchClient client = mock(OpenSearchClient.class);
    IOException backendFailure = new IOException("scroll unavailable");
    when(client.search(any(SearchRequest.class), eq(SearchEntityPO.class)))
        .thenReturn(response(true, "first"));
    when(client.scroll(any(ScrollRequest.class), eq(SearchEntityPO.class)))
        .thenThrow(backendFailure);
    OpenSearchQueryDataSource source = source(client);
    assertFalse(source.nextBatch().isEmpty());

    RuntimeException failure = assertThrows(RuntimeException.class, source::nextBatch);

    assertSame(backendFailure, failure.getCause());
    // The caller never closes the source, so the failure path itself must release the scroll.
    assertCleared(client, "first");
  }

  @Test
  void testAFailedCursorCannotBeResumed() throws Exception {
    OpenSearchClient client = mock(OpenSearchClient.class);
    when(client.search(any(SearchRequest.class), eq(SearchEntityPO.class)))
        .thenReturn(response(true, "first"));
    when(client.scroll(any(ScrollRequest.class), eq(SearchEntityPO.class)))
        .thenThrow(new IOException("scroll unavailable"));
    OpenSearchQueryDataSource source = source(client);
    assertFalse(source.nextBatch().isEmpty());
    assertThrows(RuntimeException.class, source::nextBatch);

    // Resuming would restart the scroll of this type and re-emit the entities already consumed.
    assertThrows(IllegalStateException.class, source::nextBatch);
    verify(client, times(1)).search(any(SearchRequest.class), eq(SearchEntityPO.class));
  }

  @Test
  void testSkippingTheCurrentEntityTypeClearsItsScrollAndFinishes() throws Exception {
    OpenSearchClient client = mock(OpenSearchClient.class);
    when(client.search(any(SearchRequest.class), eq(SearchEntityPO.class)))
        .thenReturn(response(true, "first"));
    try (OpenSearchQueryDataSource source = source(client)) {
      assertFalse(source.nextBatch().isEmpty());
      source.skipCurrentEntityType();

      // The only indexed type is abandoned, so the cursor is exhausted without another scroll.
      assertTrue(source.nextBatch().isEmpty());
    }
    verify(client, never()).scroll(any(ScrollRequest.class), eq(SearchEntityPO.class));
    assertCleared(client, "first");
  }

  @Test
  void testMissingFinalScrollIdRetainsPreviousIdForCleanup() throws Exception {
    OpenSearchClient client = mock(OpenSearchClient.class);
    when(client.search(any(SearchRequest.class), eq(SearchEntityPO.class)))
        .thenReturn(response(true, "first"));
    when(client.scroll(any(ScrollRequest.class), eq(SearchEntityPO.class)))
        .thenReturn(
            new ScrollResponse.Builder<SearchEntityPO>()
                .took(1)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(List.of()))
                .build());
    try (OpenSearchQueryDataSource source = source(client)) {
      assertFalse(source.nextBatch().isEmpty());
      assertTrue(source.nextBatch().isEmpty());
    }
    assertCleared(client, "first");
  }

  private static OpenSearchQueryDataSource source(OpenSearchClient client) {
    OpenSearchStorage storage = mock(OpenSearchStorage.class);
    when(storage.getClient()).thenReturn(client);
    when(storage.getIndicesAliasName(EntityType.TAG, "lake")).thenReturn("tags");
    when(storage.getBackgroundQueryTimeout()).thenReturn("1m");
    return new OpenSearchQueryDataSource(
        storage, Set.of(EntityType.TAG), "lake", null, null, List.of());
  }

  private static SearchResponse<SearchEntityPO> response(boolean hit, String scrollId) {
    SearchEntityPO entity =
        SearchEntityPO.SearchEntityPOBuilder.builder()
            .withEntityId(1)
            .withEntityType(EntityType.TAG)
            .withMetalake("lake")
            .withEntityName("tag")
            .withFullQualifiedName("tag")
            .build();
    return new SearchResponse.Builder<SearchEntityPO>()
        .took(1)
        .timedOut(false)
        .shards(s -> s.total(1).successful(1).failed(0))
        .scrollId(scrollId)
        .hits(h -> hit ? h.hits(b -> b.index("tags").id("1").source(entity)) : h.hits(List.of()))
        .build();
  }

  private static void assertCleared(OpenSearchClient client, String id) throws Exception {
    ArgumentCaptor<ClearScrollRequest> captor = ArgumentCaptor.forClass(ClearScrollRequest.class);
    verify(client, times(1)).clearScroll(captor.capture());
    assertEquals(List.of(id), captor.getValue().scrollId());
  }
}
