/*
 * Copyright 2024 Datastrato Inc.
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
import org.apache.gravitino.catalog.FunctionDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestSearchEntitySourceCreation {

  private static final NameIdentifier SCHEMA_IDENT = NameIdentifier.of("test", "c1", "s1");
  private static final Namespace SCHEMA_NAMESPACE = Namespace.of("test", "c1", "s1");

  private Object originalTableDispatcher;
  private Object originalViewDispatcher;
  private Object originalFunctionDispatcher;

  @BeforeEach
  void setUp() throws IllegalAccessException {
    GravitinoEnv env = GravitinoEnv.getInstance();
    originalTableDispatcher = FieldUtils.readField(env, "internalTableDispatcher", true);
    originalViewDispatcher = FieldUtils.readField(env, "internalViewDispatcher", true);
    originalFunctionDispatcher = FieldUtils.readField(env, "internalFunctionDispatcher", true);

    TableDispatcher tableDispatcher = Mockito.mock(TableDispatcher.class);
    Mockito.when(tableDispatcher.listTables(SCHEMA_NAMESPACE))
        .thenReturn(new NameIdentifier[] {NameIdentifier.of(SCHEMA_NAMESPACE, "t1")});
    FieldUtils.writeField(env, "internalTableDispatcher", tableDispatcher, true);
    FieldUtils.writeField(env, "internalViewDispatcher", null, true);
    FieldUtils.writeField(env, "internalFunctionDispatcher", null, true);
  }

  @AfterEach
  void tearDown() throws IllegalAccessException {
    GravitinoEnv env = GravitinoEnv.getInstance();
    FieldUtils.writeField(env, "internalTableDispatcher", originalTableDispatcher, true);
    FieldUtils.writeField(env, "internalViewDispatcher", originalViewDispatcher, true);
    FieldUtils.writeField(env, "internalFunctionDispatcher", originalFunctionDispatcher, true);
  }

  @Test
  void testRelationalSchemaSourcesIncludeViewsAndFunctions() throws IllegalAccessException {
    mockViewDispatcher(new NameIdentifier[] {NameIdentifier.of(SCHEMA_NAMESPACE, "v1")});
    mockFunctionDispatcher(new NameIdentifier[] {NameIdentifier.of(SCHEMA_NAMESPACE, "f1")});

    List<SearchEntitySource> sources =
        SearchEntitySource.createSearchEntitySourceBySchema(SCHEMA_IDENT, Catalog.Type.RELATIONAL);

    assertEquals(3, sources.size());
    assertInstanceOf(TableSearchEntitySource.class, sources.get(0));
    assertInstanceOf(ViewSearchEntitySource.class, sources.get(1));
    assertEquals(1, sources.get(1).approximateEntityCount());
    assertInstanceOf(FunctionSearchEntitySource.class, sources.get(2));
    assertEquals(1, sources.get(2).approximateEntityCount());
  }

  @Test
  void testCatalogsWithoutViewSupportAreSkipped() throws IllegalAccessException {
    ViewDispatcher viewDispatcher = Mockito.mock(ViewDispatcher.class);
    Mockito.when(viewDispatcher.listViews(SCHEMA_NAMESPACE))
        .thenThrow(new UnsupportedOperationException("listViews is not supported"));
    FieldUtils.writeField(
        GravitinoEnv.getInstance(), "internalViewDispatcher", viewDispatcher, true);

    List<SearchEntitySource> sources =
        SearchEntitySource.createSearchEntitySourceBySchema(SCHEMA_IDENT, Catalog.Type.RELATIONAL);

    assertEquals(1, sources.size());
    assertInstanceOf(TableSearchEntitySource.class, sources.get(0));
  }

  @Test
  void testViewsAreSkippedWhenDispatcherIsAbsent() throws IllegalAccessException {
    // A server that never initialized the view dispatcher must still sync tables.
    FieldUtils.writeField(GravitinoEnv.getInstance(), "internalViewDispatcher", null, true);

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
    FieldUtils.writeField(
        GravitinoEnv.getInstance(), "internalViewDispatcher", viewDispatcher, true);

    assertThrows(
        RuntimeException.class,
        () ->
            SearchEntitySource.createSearchEntitySourceBySchema(
                SCHEMA_IDENT, Catalog.Type.RELATIONAL));
  }

  @Test
  void testCatalogsWithoutFunctionSupportAreSkipped() throws IllegalAccessException {
    FunctionDispatcher functionDispatcher = Mockito.mock(FunctionDispatcher.class);
    Mockito.when(functionDispatcher.listFunctions(SCHEMA_NAMESPACE))
        .thenThrow(new UnsupportedOperationException("listFunctions is not supported"));
    FieldUtils.writeField(
        GravitinoEnv.getInstance(), "internalFunctionDispatcher", functionDispatcher, true);

    List<SearchEntitySource> sources =
        SearchEntitySource.createSearchEntitySourceBySchema(SCHEMA_IDENT, Catalog.Type.RELATIONAL);

    assertEquals(1, sources.size());
    assertInstanceOf(TableSearchEntitySource.class, sources.get(0));
  }

  @Test
  void testListFunctionsFailureIsNotSwallowed() throws IllegalAccessException {
    FunctionDispatcher functionDispatcher = Mockito.mock(FunctionDispatcher.class);
    Mockito.when(functionDispatcher.listFunctions(SCHEMA_NAMESPACE))
        .thenThrow(new RuntimeException("Function catalog is unavailable"));
    FieldUtils.writeField(
        GravitinoEnv.getInstance(), "internalFunctionDispatcher", functionDispatcher, true);

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
    assertInstanceOf(
        UserSearchEntitySource.class,
        SearchEntitySource.createSearchEntitySource(
            SearchEntityIdentifier.of(
                NameIdentifierUtil.ofUser("test", "alice"), Entity.EntityType.USER),
            false));
    assertInstanceOf(
        GroupSearchEntitySource.class,
        SearchEntitySource.createSearchEntitySource(
            SearchEntityIdentifier.of(
                NameIdentifierUtil.ofGroup("test", "engineers"), Entity.EntityType.GROUP),
            false));
    assertInstanceOf(
        FunctionSearchEntitySource.class,
        SearchEntitySource.createSearchEntitySource(
            SearchEntityIdentifier.of(
                NameIdentifier.of(SCHEMA_NAMESPACE, "f1"), Entity.EntityType.FUNCTION),
            false));
    assertInstanceOf(
        RoleSearchEntitySource.class,
        SearchEntitySource.createSearchEntitySource(
            SearchEntityIdentifier.of(
                NameIdentifierUtil.ofRole("test", "table_reader"), Entity.EntityType.ROLE),
            false));
  }

  private void mockViewDispatcher(NameIdentifier[] views) throws IllegalAccessException {
    ViewDispatcher viewDispatcher = Mockito.mock(ViewDispatcher.class);
    Mockito.when(viewDispatcher.listViews(SCHEMA_NAMESPACE)).thenReturn(views);
    FieldUtils.writeField(
        GravitinoEnv.getInstance(), "internalViewDispatcher", viewDispatcher, true);
  }

  private void mockFunctionDispatcher(NameIdentifier[] functions) throws IllegalAccessException {
    FunctionDispatcher functionDispatcher = Mockito.mock(FunctionDispatcher.class);
    Mockito.when(functionDispatcher.listFunctions(SCHEMA_NAMESPACE)).thenReturn(functions);
    FieldUtils.writeField(
        GravitinoEnv.getInstance(), "internalFunctionDispatcher", functionDispatcher, true);
  }
}
