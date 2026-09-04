/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.exceptions.NoSuchCatalogException;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class TestPolicyAssociationSearchEntitySource {

  private static final String METALAKE = "test_metalake";
  private static final String POLICY_NAME = "test_policy";
  private static final NameIdentifier TABLE_IDENT =
      NameIdentifier.of(METALAKE, "c1", "s1", "table1");
  private static final NameIdentifier CATALOG_IDENT = NameIdentifier.of(METALAKE, "c1");
  private static final NameIdentifier DROPPED_SCHEMA_IDENT =
      NameIdentifier.of(METALAKE, "dropped_catalog", "s1");

  private Object originalCatalogDispatcher;

  @BeforeEach
  void setUp() throws IllegalAccessException {
    originalCatalogDispatcher =
        FieldUtils.readField(GravitinoEnv.getInstance(), "internalCatalogDispatcher", true);
  }

  @AfterEach
  void tearDown() throws IllegalAccessException {
    FieldUtils.writeField(
        GravitinoEnv.getInstance(), "internalCatalogDispatcher", originalCatalogDispatcher, true);
  }

  @Test
  void testIndexedEntitiesAreLoadedLazilyWithoutCascading() {
    AtomicInteger loadCount = new AtomicInteger();
    PolicyAssociationSearchEntitySource source =
        PolicyAssociationSearchEntitySource.ofIndexedEntities(
            METALAKE,
            POLICY_NAME,
            () -> {
              loadCount.incrementAndGet();
              return ImmutableList.of(
                  SearchEntityIdentifier.of(CATALOG_IDENT, Entity.EntityType.CATALOG));
            });

    assertEquals(0, loadCount.get());
    assertTrue(source.addChildEntitySources());
    assertEquals(1, loadCount.get());
    assertEquals(1, source.childSources.size());
    assertFalse(((ParentEntitySource) source.childSources.get(0)).cascading);
  }

  @Test
  void testMissingIndexedIdentifiersAreSkipped() throws IllegalAccessException {
    assertMissingIdentifierIsSkipped(new NoSuchCatalogException("Catalog does not exist"));
    assertMissingIdentifierIsSkipped(new NoSuchEntityException("Catalog does not exist"));
  }

  @Test
  void testUnexpectedFailureDoesNotAbortRemainingEntities() throws IllegalAccessException {
    CatalogDispatcher catalogDispatcher = Mockito.mock(CatalogDispatcher.class);
    Mockito.when(catalogDispatcher.loadCatalog(ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("Catalog loading failed"));
    FieldUtils.writeField(
        GravitinoEnv.getInstance(), "internalCatalogDispatcher", catalogDispatcher, true);

    PolicyAssociationSearchEntitySource source =
        indexedSource(
            ImmutableList.of(
                SearchEntityIdentifier.of(DROPPED_SCHEMA_IDENT, Entity.EntityType.SCHEMA),
                SearchEntityIdentifier.of(TABLE_IDENT, Entity.EntityType.TABLE)));

    assertTrue(source.addChildEntitySources());
    assertEquals(1, source.childSources.size());

    List<SearchEntityIdentifier> failed = source.getProcessFailedEntities();
    assertEquals(1, failed.size());
    assertEquals(DROPPED_SCHEMA_IDENT, failed.get(0).entityIdent());
  }

  @Test
  void testEveryIdentifierFailingIsReported() throws IllegalAccessException {
    CatalogDispatcher catalogDispatcher = Mockito.mock(CatalogDispatcher.class);
    Mockito.when(catalogDispatcher.loadCatalog(ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("Catalog loading failed"));
    FieldUtils.writeField(
        GravitinoEnv.getInstance(), "internalCatalogDispatcher", catalogDispatcher, true);

    PolicyAssociationSearchEntitySource source =
        indexedSource(
            ImmutableList.of(
                SearchEntityIdentifier.of(DROPPED_SCHEMA_IDENT, Entity.EntityType.SCHEMA)));

    assertFalse(source.addChildEntitySources());
    assertEquals(1, source.getProcessFailedEntities().size());
  }

  private void assertMissingIdentifierIsSkipped(RuntimeException exception)
      throws IllegalAccessException {
    CatalogDispatcher catalogDispatcher = Mockito.mock(CatalogDispatcher.class);
    Mockito.when(catalogDispatcher.loadCatalog(ArgumentMatchers.any())).thenThrow(exception);
    FieldUtils.writeField(
        GravitinoEnv.getInstance(), "internalCatalogDispatcher", catalogDispatcher, true);

    PolicyAssociationSearchEntitySource source =
        indexedSource(
            ImmutableList.of(
                SearchEntityIdentifier.of(DROPPED_SCHEMA_IDENT, Entity.EntityType.SCHEMA),
                SearchEntityIdentifier.of(TABLE_IDENT, Entity.EntityType.TABLE)));

    assertTrue(source.addChildEntitySources());
    assertEquals(1, source.childSources.size());
    assertTrue(source.getProcessFailedEntities().isEmpty());
  }

  private static PolicyAssociationSearchEntitySource indexedSource(
      List<SearchEntityIdentifier> identifiers) {
    return PolicyAssociationSearchEntitySource.ofIndexedEntities(
        METALAKE, POLICY_NAME, () -> identifiers);
  }
}
