/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestSearchEntitySourceCreation {

  private static final NameIdentifier SCHEMA_IDENT = NameIdentifier.of("test", "c1", "s1");
  private static final Namespace SCHEMA_NAMESPACE = Namespace.of("test", "c1", "s1");

  private Object originalTableDispatcher;
  private Object originalViewDispatcher;

  @BeforeEach
  void setUp() throws IllegalAccessException {
    GravitinoEnv env = GravitinoEnv.getInstance();
    originalTableDispatcher = FieldUtils.readField(env, "tableDispatcher", true);
    originalViewDispatcher = FieldUtils.readField(env, "viewDispatcher", true);

    TableDispatcher tableDispatcher = Mockito.mock(TableDispatcher.class);
    Mockito.when(tableDispatcher.listTables(SCHEMA_NAMESPACE))
        .thenReturn(new NameIdentifier[] {NameIdentifier.of(SCHEMA_NAMESPACE, "t1")});
    FieldUtils.writeField(env, "tableDispatcher", tableDispatcher, true);
  }

  @AfterEach
  void tearDown() throws IllegalAccessException {
    GravitinoEnv env = GravitinoEnv.getInstance();
    FieldUtils.writeField(env, "tableDispatcher", originalTableDispatcher, true);
    FieldUtils.writeField(env, "viewDispatcher", originalViewDispatcher, true);
  }

  @Test
  void testRelationalSchemaSourcesIncludeViews() throws IllegalAccessException {
    mockViewDispatcher(new NameIdentifier[] {NameIdentifier.of(SCHEMA_NAMESPACE, "v1")});

    List<SearchEntitySource> sources =
        SearchEntitySource.createSearchEntitySourceBySchema(SCHEMA_IDENT, Catalog.Type.RELATIONAL);

    assertEquals(2, sources.size());
    assertInstanceOf(TableSearchEntitySource.class, sources.get(0));
    assertInstanceOf(ViewSearchEntitySource.class, sources.get(1));
    assertEquals(1, sources.get(1).approximateEntityCount());
  }

  @Test
  void testCatalogsWithoutViewSupportAreSkipped() throws IllegalAccessException {
    ViewDispatcher viewDispatcher = Mockito.mock(ViewDispatcher.class);
    Mockito.when(viewDispatcher.listViews(SCHEMA_NAMESPACE))
        .thenThrow(new UnsupportedOperationException("listViews is not supported"));
    FieldUtils.writeField(GravitinoEnv.getInstance(), "viewDispatcher", viewDispatcher, true);

    List<SearchEntitySource> sources =
        SearchEntitySource.createSearchEntitySourceBySchema(SCHEMA_IDENT, Catalog.Type.RELATIONAL);

    assertEquals(1, sources.size());
    assertInstanceOf(TableSearchEntitySource.class, sources.get(0));
  }

  @Test
  void testViewsAreSkippedWhenDispatcherIsAbsent() throws IllegalAccessException {
    // A server that never initialized the view dispatcher must still sync tables.
    FieldUtils.writeField(GravitinoEnv.getInstance(), "viewDispatcher", null, true);

    List<SearchEntitySource> sources =
        SearchEntitySource.createSearchEntitySourceBySchema(SCHEMA_IDENT, Catalog.Type.RELATIONAL);

    assertEquals(1, sources.size());
    assertInstanceOf(TableSearchEntitySource.class, sources.get(0));
  }

  @Test
  void testListViewsFailureIsNotSwallowed() throws IllegalAccessException {
    // Only "views are not supported" is tolerated, a real failure must surface instead of
    // silently indexing a schema without its views.
    ViewDispatcher viewDispatcher = Mockito.mock(ViewDispatcher.class);
    Mockito.when(viewDispatcher.listViews(SCHEMA_NAMESPACE))
        .thenThrow(new RuntimeException("OpenSearch is down"));
    FieldUtils.writeField(GravitinoEnv.getInstance(), "viewDispatcher", viewDispatcher, true);

    assertThrows(
        RuntimeException.class,
        () ->
            SearchEntitySource.createSearchEntitySourceBySchema(
                SCHEMA_IDENT, Catalog.Type.RELATIONAL));
  }

  @Test
  void testCreateLeafSourceByEntityType() {
    assertInstanceOf(
        ViewSearchEntitySource.class,
        SearchEntitySource.createSearchEntitySource(
            SearchEntityIdentifier.of(
                NameIdentifier.of(SCHEMA_NAMESPACE, "v1"), Entity.EntityType.VIEW),
            false));
  }

  private void mockViewDispatcher(NameIdentifier[] views) throws IllegalAccessException {
    ViewDispatcher viewDispatcher = Mockito.mock(ViewDispatcher.class);
    Mockito.when(viewDispatcher.listViews(SCHEMA_NAMESPACE)).thenReturn(views);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "viewDispatcher", viewDispatcher, true);
  }
}
