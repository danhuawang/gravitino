/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.listener;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.search.service.SearchService;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.listener.api.event.job.DeleteJobTemplateEvent;
import org.apache.gravitino.listener.api.event.job.JobTemplateEvent;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class TestDataDiscoveryListener {

  @Test
  void testEventsAreSkippedWhileSearchStorageIsUnavailable() throws Exception {
    SearchService service = mock(SearchService.class);
    when(service.skipEventWhileUnavailable()).thenReturn(true);
    DataDiscoveryListener listener = ListenerTestUtils.newListener(service);
    EventHandler handler = mock(EventHandler.class);
    FieldUtils.writeField(
        listener, "eventHandlers", ImmutableMap.of(JobTemplateEvent.class, handler), true);
    DeleteJobTemplateEvent event = new DeleteJobTemplateEvent("user", "lake", "template", true);

    listener.onPostEvent(event);
    InOrder order = inOrder(service);
    order.verify(service).awaitFirstInitializationAttempt();
    order.verify(service).skipEventWhileUnavailable();
    verify(handler, never()).handleEvent(event);

    when(service.skipEventWhileUnavailable()).thenReturn(false);
    listener.onPostEvent(event);
    verify(handler).handleEvent(event);
  }
}
