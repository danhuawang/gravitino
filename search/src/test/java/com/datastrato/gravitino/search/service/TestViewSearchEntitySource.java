/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.EntityCombinedView;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.dto.rel.ColumnDTO;
import org.apache.gravitino.exceptions.NoSuchViewException;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.ViewEntity;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Representation;
import org.apache.gravitino.rel.SQLRepresentation;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.tag.Tag;
import org.apache.gravitino.tag.TagDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class TestViewSearchEntitySource {

  private static final Namespace SCHEMA_NAMESPACE = Namespace.of("test", "c1", "s1");
  private static final NameIdentifier VIEW_IDENT = NameIdentifier.of(SCHEMA_NAMESPACE, "v1");

  private Object originalViewDispatcher;
  private Object originalTagDispatcher;
  private Object originalPublicViewDispatcher;
  private Object originalPublicTagDispatcher;

  @BeforeEach
  void setUp() throws IllegalAccessException {
    GravitinoEnv env = GravitinoEnv.getInstance();
    originalViewDispatcher = FieldUtils.readField(env, "internalViewDispatcher", true);
    originalTagDispatcher = FieldUtils.readField(env, "internalTagDispatcher", true);
    originalPublicViewDispatcher = FieldUtils.readField(env, "viewDispatcher", true);
    originalPublicTagDispatcher = FieldUtils.readField(env, "tagDispatcher", true);

    TagDispatcher tagDispatcher = Mockito.mock(TagDispatcher.class);
    Mockito.when(
            tagDispatcher.listTagsInfoForMetadataObject(
                ArgumentMatchers.anyString(), ArgumentMatchers.any()))
        .thenReturn(new Tag[0]);
    FieldUtils.writeField(env, "internalTagDispatcher", tagDispatcher, true);
  }

  @AfterEach
  void tearDown() throws IllegalAccessException {
    GravitinoEnv env = GravitinoEnv.getInstance();
    FieldUtils.writeField(env, "internalViewDispatcher", originalViewDispatcher, true);
    FieldUtils.writeField(env, "internalTagDispatcher", originalTagDispatcher, true);
    FieldUtils.writeField(env, "viewDispatcher", originalPublicViewDispatcher, true);
    FieldUtils.writeField(env, "tagDispatcher", originalPublicTagDispatcher, true);
  }

  @Test
  void testLoadedViewIsConverted() throws IllegalAccessException {
    ViewEntity viewEntity = newViewEntity();
    ViewDispatcher dispatcher = Mockito.mock(ViewDispatcher.class);
    Mockito.when(dispatcher.loadView(VIEW_IDENT))
        .thenReturn(EntityCombinedView.of(viewEntity, viewEntity));
    FieldUtils.writeField(GravitinoEnv.getInstance(), "internalViewDispatcher", dispatcher, true);
    ViewDispatcher publicViewDispatcher = Mockito.mock(ViewDispatcher.class);
    TagDispatcher publicTagDispatcher = Mockito.mock(TagDispatcher.class);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "viewDispatcher", publicViewDispatcher, true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "tagDispatcher", publicTagDispatcher, true);

    ViewSearchEntitySource source =
        new ViewSearchEntitySource(
            ImmutableList.of(SearchEntityIdentifier.of(VIEW_IDENT, Entity.EntityType.VIEW)));

    List<SearchEntityPO> batch = source.nextBatch(10);

    assertEquals(1, batch.size());
    assertEquals("v1", batch.get(0).getEntityName());
    assertEquals(Entity.EntityType.VIEW, batch.get(0).getEntityType());
    assertTrue(source.getProcessFailedEntities().isEmpty());
    Mockito.verifyNoInteractions(publicViewDispatcher, publicTagDispatcher);
  }

  @Test
  void testFailedViewIsRecordedInsteadOfFailingTheBatch() throws IllegalAccessException {
    ViewEntity viewEntity = newViewEntity();
    NameIdentifier brokenIdent = NameIdentifier.of(SCHEMA_NAMESPACE, "broken");
    ViewDispatcher dispatcher = Mockito.mock(ViewDispatcher.class);
    Mockito.when(dispatcher.loadView(brokenIdent))
        .thenThrow(new NoSuchViewException("View does not exist"));
    Mockito.when(dispatcher.loadView(VIEW_IDENT))
        .thenReturn(EntityCombinedView.of(viewEntity, viewEntity));
    FieldUtils.writeField(GravitinoEnv.getInstance(), "internalViewDispatcher", dispatcher, true);

    ViewSearchEntitySource source =
        new ViewSearchEntitySource(
            ImmutableList.of(
                SearchEntityIdentifier.of(brokenIdent, Entity.EntityType.VIEW),
                SearchEntityIdentifier.of(VIEW_IDENT, Entity.EntityType.VIEW)));

    List<SearchEntityPO> batch = source.nextBatch(10);

    // The healthy view is still indexed, the broken one is reported as failed so that the sync
    // task does not delete metadata it failed to read.
    assertEquals(1, batch.size());
    assertEquals("v1", batch.get(0).getEntityName());
    assertEquals(1, source.getProcessFailedEntities().size());
    assertEquals(brokenIdent, source.getProcessFailedEntities().get(0).entityIdent());
  }

  private ViewEntity newViewEntity() {
    return ViewEntity.builder()
        .withId(1000L)
        .withName("v1")
        .withNamespace(SCHEMA_NAMESPACE)
        .withComment("demo view")
        .withColumns(
            new Column[] {
              ColumnDTO.builder().withName("id").withDataType(Types.IntegerType.get()).build()
            })
        .withRepresentations(
            new Representation[] {
              SQLRepresentation.builder().withDialect("trino").withSql("SELECT 1").build()
            })
        .withProperties(ImmutableMap.of())
        .withAuditInfo(AuditInfo.EMPTY)
        .build();
  }
}
