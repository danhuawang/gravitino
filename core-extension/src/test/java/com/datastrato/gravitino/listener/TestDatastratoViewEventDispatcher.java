/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.listener;

import com.datastrato.gravitino.catalog.DatastratoViewDispatcher;
import java.util.Arrays;
import java.util.List;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.api.event.view.ListViewEvent;
import org.apache.gravitino.listener.api.event.view.ListViewFailureEvent;
import org.apache.gravitino.meta.ViewEntity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestDatastratoViewEventDispatcher {

  @Test
  public void testListEntitiesSuccessDispatchEvent() {
    EventBus eventBus = Mockito.mock(EventBus.class);
    DatastratoViewDispatcher dispatcher = Mockito.mock(DatastratoViewDispatcher.class);
    DatastratoViewEventDispatcher eventDispatcher =
        new DatastratoViewEventDispatcher(eventBus, dispatcher);
    Namespace namespace = Namespace.of("metalake", "catalog", "schema");

    List<ViewEntity> entities =
        Arrays.asList(Mockito.mock(ViewEntity.class), Mockito.mock(ViewEntity.class));
    Mockito.when(dispatcher.listEntities(namespace)).thenReturn(entities);

    List<ViewEntity> actual = eventDispatcher.listEntities(namespace);

    Assertions.assertEquals(entities, actual);
    Mockito.verify(dispatcher).listEntities(namespace);
    Mockito.verify(eventBus).dispatchEvent(Mockito.any(ListViewEvent.class));
  }

  @Test
  public void testListEntitiesFailureDispatchFailureEvent() {
    EventBus eventBus = Mockito.mock(EventBus.class);
    DatastratoViewDispatcher dispatcher = Mockito.mock(DatastratoViewDispatcher.class);
    DatastratoViewEventDispatcher eventDispatcher =
        new DatastratoViewEventDispatcher(eventBus, dispatcher);
    Namespace namespace = Namespace.of("metalake", "catalog", "schema");

    RuntimeException expected = new RuntimeException("mock failure");
    Mockito.when(dispatcher.listEntities(namespace)).thenThrow(expected);

    RuntimeException actual =
        Assertions.assertThrows(
            RuntimeException.class, () -> eventDispatcher.listEntities(namespace));

    Assertions.assertSame(expected, actual);
    Mockito.verify(dispatcher).listEntities(namespace);
    Mockito.verify(eventBus).dispatchEvent(Mockito.any(ListViewFailureEvent.class));
  }
}
