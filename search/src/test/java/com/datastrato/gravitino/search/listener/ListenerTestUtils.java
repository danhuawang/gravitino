/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.listener;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.search.service.SearchService;
import org.apache.commons.lang3.reflect.FieldUtils;

final class ListenerTestUtils {

  private ListenerTestUtils() {}

  /** Creates a listener bound to a search service whose storage is available. */
  static DataDiscoveryListener newListener() throws IllegalAccessException {
    SearchService service = mock(SearchService.class);
    when(service.isAvailable()).thenReturn(true);
    return newListener(service);
  }

  /** Creates a listener bound to the given search service. */
  static DataDiscoveryListener newListener(SearchService service) throws IllegalAccessException {
    DataDiscoveryListener listener = new DataDiscoveryListener();
    FieldUtils.writeField(listener, "searchService", service, true);
    return listener;
  }
}
