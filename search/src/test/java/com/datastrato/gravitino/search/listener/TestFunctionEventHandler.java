/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.listener;

import com.datastrato.gravitino.search.service.SearchService;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.function.FunctionChange;
import org.apache.gravitino.listener.api.event.function.AlterFunctionEvent;
import org.apache.gravitino.listener.api.event.function.DropFunctionEvent;
import org.apache.gravitino.listener.api.event.function.GetFunctionEvent;
import org.apache.gravitino.listener.api.event.function.RegisterFunctionEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestFunctionEventHandler {

  private static final String USER = "tester";
  private static final NameIdentifier FUNCTION_IDENT =
      NameIdentifier.of("test_metalake", "c1", "s1", "f1");

  private SearchService searchService;
  private FunctionEventHandler handler;

  @BeforeEach
  void setUp() {
    searchService = Mockito.mock(SearchService.class);
    handler = new FunctionEventHandler(searchService);
  }

  @Test
  void testRegisterFunctionIsSynchronized() {
    handler.handleEvent(new RegisterFunctionEvent(USER, FUNCTION_IDENT, null));

    Mockito.verify(searchService).synchronizeMetadata(FUNCTION_IDENT, EntityType.FUNCTION, false);
    Mockito.verifyNoMoreInteractions(searchService);
  }

  @Test
  void testAlterFunctionIsSynchronized() {
    handler.handleEvent(
        new AlterFunctionEvent(
            USER,
            FUNCTION_IDENT,
            new FunctionChange[] {FunctionChange.updateComment("new comment")},
            null));

    Mockito.verify(searchService).synchronizeMetadata(FUNCTION_IDENT, EntityType.FUNCTION, false);
  }

  @Test
  void testDropFunctionIsRemoved() {
    handler.handleEvent(new DropFunctionEvent(USER, FUNCTION_IDENT, true));

    Mockito.verify(searchService).removeMetadata(FUNCTION_IDENT, EntityType.FUNCTION, false);
    Mockito.verifyNoMoreInteractions(searchService);
  }

  @Test
  void testReadOnlyEventIsIgnored() {
    handler.handleEvent(new GetFunctionEvent(USER, FUNCTION_IDENT, null));

    Mockito.verifyNoInteractions(searchService);
  }
}
