/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.store.opensearch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.indices.GetIndexTemplateRequest;
import org.opensearch.client.opensearch.indices.GetIndexTemplateResponse;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.opensearch.indices.get_index_template.IndexTemplateItem;

class TestOpenSearchIndexTemplateCheck {

  @Test
  void testRetries429AndSucceeds() throws Exception {
    OpenSearchIndicesClient indices = mock(OpenSearchIndicesClient.class);
    OpenSearchException overloaded = mock(OpenSearchException.class);
    when(overloaded.status()).thenReturn(429);
    when(indices.getIndexTemplate(any(GetIndexTemplateRequest.class)))
        .thenThrow(overloaded)
        .thenReturn(completeTemplates());

    storageWithIndices(indices, 3).checkIndexTemplate();

    verify(indices, times(2)).getIndexTemplate(any(GetIndexTemplateRequest.class));
  }

  @Test
  void testRetriesConnectionFailureAndSucceeds() throws Exception {
    OpenSearchIndicesClient indices = mock(OpenSearchIndicesClient.class);
    when(indices.getIndexTemplate(any(GetIndexTemplateRequest.class)))
        .thenThrow(new IOException("connection refused"))
        .thenReturn(completeTemplates());

    storageWithIndices(indices, 3).checkIndexTemplate();

    verify(indices, times(2)).getIndexTemplate(any(GetIndexTemplateRequest.class));
  }

  @Test
  void testExhausted503PreservesCause() throws Exception {
    OpenSearchIndicesClient indices = mock(OpenSearchIndicesClient.class);
    OpenSearchException unavailable = mock(OpenSearchException.class);
    when(unavailable.status()).thenReturn(503);
    when(indices.getIndexTemplate(any(GetIndexTemplateRequest.class))).thenThrow(unavailable);

    RuntimeException error =
        assertThrows(RuntimeException.class, storageWithIndices(indices, 2)::checkIndexTemplate);

    assertTrue(error.getCause() == unavailable);
    assertTrue(error.getMessage().contains("unavailable or overloaded"));
    verify(indices, times(2)).getIndexTemplate(any(GetIndexTemplateRequest.class));
  }

  @Test
  void testMissingTemplateIsNotRetried() throws Exception {
    OpenSearchIndicesClient indices = mock(OpenSearchIndicesClient.class);
    GetIndexTemplateResponse missing =
        GetIndexTemplateResponse.of(builder -> builder.indexTemplates(List.of()));
    when(indices.getIndexTemplate(any(GetIndexTemplateRequest.class))).thenReturn(missing);

    OpenSearchStorage.MissingIndexTemplateException error =
        assertThrows(
            OpenSearchStorage.MissingIndexTemplateException.class,
            storageWithIndices(indices, 3)::checkIndexTemplate);

    assertTrue(error.getMessage().contains("is missing"));
    verify(indices).getIndexTemplate(any(GetIndexTemplateRequest.class));
  }

  @Test
  void testNonRetryableFailureIsNotRetried() throws Exception {
    OpenSearchIndicesClient indices = mock(OpenSearchIndicesClient.class);
    OpenSearchException unauthorized = mock(OpenSearchException.class);
    when(unauthorized.status()).thenReturn(401);
    when(indices.getIndexTemplate(any(GetIndexTemplateRequest.class))).thenThrow(unauthorized);

    RuntimeException error =
        assertThrows(RuntimeException.class, storageWithIndices(indices, 3)::checkIndexTemplate);

    assertTrue(error.getCause() == unauthorized);
    assertTrue(error.getMessage().startsWith("Failed to check OpenSearch index templates"));
    verify(indices).getIndexTemplate(any(GetIndexTemplateRequest.class));
  }

  @Test
  void testClientIllegalStateIsNotReportedAsMissingTemplate() throws Exception {
    OpenSearchIndicesClient indices = mock(OpenSearchIndicesClient.class);
    IllegalStateException clientFailure = new IllegalStateException("bad response");
    when(indices.getIndexTemplate(any(GetIndexTemplateRequest.class))).thenThrow(clientFailure);

    RuntimeException error =
        assertThrows(RuntimeException.class, storageWithIndices(indices, 3)::checkIndexTemplate);

    assertFalse(error instanceof OpenSearchStorage.MissingIndexTemplateException);
    assertTrue(error.getCause() == clientFailure);
    assertTrue(error.getMessage().startsWith("Failed to check OpenSearch index templates"));
  }

  private static GetIndexTemplateResponse completeTemplates() {
    List<IndexTemplateItem> items =
        OpenSearchStorage.ENTITY_TYPE_TO_INDEX_SUFFIX.values().stream()
            .map(
                suffix ->
                    IndexTemplateItem.of(
                        builder ->
                            builder
                                .name(suffix + "_template_v1")
                                .indexTemplate(
                                    template ->
                                        template
                                            .indexPatterns(suffix + "*")
                                            .composedOf(List.of()))))
            .collect(Collectors.toList());
    return GetIndexTemplateResponse.of(builder -> builder.indexTemplates(items));
  }

  private static OpenSearchStorage storageWithIndices(OpenSearchIndicesClient indices, int retries)
      throws Exception {
    OpenSearchStorage storage = new OpenSearchStorage();
    OpenSearchClient client = mock(OpenSearchClient.class);
    when(client.indices()).thenReturn(indices);
    FieldUtils.writeField(storage, "client", client, true);
    FieldUtils.writeField(storage, "maxRetries", retries, true);
    FieldUtils.writeField(storage, "retryBackoffMs", 0L, true);
    return storage;
  }
}
