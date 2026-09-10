/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.search.store.SearchStorage;
import java.util.List;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.junit.jupiter.api.Test;

/** Verifies invalid queries cannot reach storage after parser error recovery. */
class TestSearchQueryValidation {
  @Test
  void testMalformedQueryDoesNotReachStorage() throws Exception {
    GravitinoEnv env = GravitinoEnv.getInstance();
    Object previous = FieldUtils.readField(env, "metalakeDispatcher", true);
    MetalakeDispatcher dispatcher = mock(MetalakeDispatcher.class);
    when(dispatcher.metalakeExists(any())).thenReturn(true);
    SearchService service = mock(SearchService.class, CALLS_REAL_METHODS);
    SearchStorage storage = mock(SearchStorage.class);
    FieldUtils.writeField(service, "storage", storage, true);
    FieldUtils.writeField(env, "metalakeDispatcher", dispatcher, true);
    try {
      for (String query : List.of("valid @ invalid", "name:", "valid AND", "(valid", "\"open")) {
        assertThrows(IllegalArgumentException.class, () -> service.query("lake", query, 0, 10));
      }
      verifyNoInteractions(storage);
    } finally {
      FieldUtils.writeField(env, "metalakeDispatcher", previous, true);
    }
  }
}
