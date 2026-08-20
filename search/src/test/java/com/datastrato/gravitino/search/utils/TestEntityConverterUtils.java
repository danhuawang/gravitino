/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.po.SearchViewEntityPO;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.EntityCombinedView;
import org.apache.gravitino.dto.rel.ColumnDTO;
import org.apache.gravitino.function.FunctionDefinition;
import org.apache.gravitino.function.FunctionType;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.FunctionEntity;
import org.apache.gravitino.meta.ViewEntity;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Representation;
import org.apache.gravitino.rel.SQLRepresentation;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.tag.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestEntityConverterUtils {

  private static final Namespace VIEW_NAMESPACE = Namespace.of("test", "c1", "s1");

  @Test
  void testToFunctionSearchEntityPOUsesV2Projection() {
    NameIdentifier identifier = NameIdentifier.of(VIEW_NAMESPACE, "mask_email");
    FunctionEntity function =
        FunctionEntity.builder()
            .withId(2000L)
            .withName(identifier.name())
            .withNamespace(identifier.namespace())
            .withComment("masks sensitive email addresses")
            .withFunctionType(FunctionType.SCALAR)
            .withDeterministic(true)
            .withDefinitions(new FunctionDefinition[0])
            .withAuditInfo(AuditInfo.EMPTY)
            .build();
    Tag tag = Mockito.mock(Tag.class);
    Mockito.when(tag.name()).thenReturn("pii");

    SearchEntityPO po =
        EntityConverterUtils.toFunctionSearchEntityPO(function, new Tag[] {tag}, identifier);

    assertEquals(2000L, po.getEntityId());
    assertEquals(Entity.EntityType.FUNCTION, po.getEntityType());
    assertEquals("test", po.getMetalake());
    assertEquals("c1", po.getCatalogName());
    assertEquals("mask_email", po.getEntityName());
    assertEquals("masks sensitive email addresses", po.getEntityComment());
    assertEquals("c1.s1.mask_email", po.getFullQualifiedName());
    assertTrue(po.isInUse());
    assertEquals(1, po.getTags().size());
    assertEquals("pii", po.getTags().get(0).getTagName());
  }

  @Test
  void testToViewSearchEntityPO() {
    ViewEntity viewEntity = newViewEntity("v1", ImmutableMap.of());
    NameIdentifier identifier = NameIdentifier.of(VIEW_NAMESPACE, "v1");

    SearchViewEntityPO po =
        EntityConverterUtils.toViewSearchEntityPO(
            EntityCombinedView.of(viewEntity, viewEntity), new Tag[0], identifier);

    assertEquals(viewEntity.id(), po.getEntityId());
    assertEquals(Entity.EntityType.VIEW, po.getEntityType());
    assertEquals("test", po.getMetalake());
    assertEquals("c1", po.getCatalogName());
    assertEquals("v1", po.getEntityName());
    assertEquals("demo view", po.getEntityComment());
    assertEquals("c1.s1.v1", po.getFullQualifiedName());
    assertTrue(po.isInUse());

    assertEquals(1, po.getColumns().size());
    assertEquals("id", po.getColumns().get(0).getColumnName());
    assertEquals("the id", po.getColumns().get(0).getColumnComment());
  }

  @Test
  void testToViewSearchEntityPOHonorsInUseProperty() {
    ViewEntity viewEntity = newViewEntity("v2", ImmutableMap.of("in-use", "false"));

    SearchViewEntityPO po =
        EntityConverterUtils.toViewSearchEntityPO(
            EntityCombinedView.of(viewEntity, viewEntity),
            new Tag[0],
            NameIdentifier.of(VIEW_NAMESPACE, "v2"));

    assertFalse(po.isInUse());
    assertEquals(1, po.getEntityProperties().size());
    assertEquals("in-use", po.getEntityProperties().get(0).getKey());
  }

  @Test
  void testToViewSearchEntityPOReadsIdFromPropertiesWhenNotImported() {
    // A view that Gravitino has not imported yet carries no ViewEntity, its id then comes from
    // the "gravitino.identifier" property set by the catalog.
    ViewEntity viewEntity =
        newViewEntity("v3", ImmutableMap.of("gravitino.identifier", "gravitino.v1.uid4242"));

    SearchViewEntityPO po =
        EntityConverterUtils.toViewSearchEntityPO(
            EntityCombinedView.of(viewEntity), new Tag[0], NameIdentifier.of(VIEW_NAMESPACE, "v3"));

    assertEquals(4242L, po.getEntityId());
  }

  @Test
  void testToViewSearchEntityPOFailsWhenIdCannotBeResolved() {
    ViewEntity viewEntity = newViewEntity("v4", ImmutableMap.of());

    assertThrows(
        RuntimeException.class,
        () ->
            EntityConverterUtils.toViewSearchEntityPO(
                EntityCombinedView.of(viewEntity),
                new Tag[0],
                NameIdentifier.of(VIEW_NAMESPACE, "v4")));
  }

  @Test
  void testToViewSearchEntityPOKeepsTags() {
    ViewEntity viewEntity = newViewEntity("v5", ImmutableMap.of());
    Tag tag = Mockito.mock(Tag.class);
    Mockito.when(tag.name()).thenReturn("pii");
    Mockito.when(tag.comment()).thenReturn("sensitive");

    SearchViewEntityPO po =
        EntityConverterUtils.toViewSearchEntityPO(
            EntityCombinedView.of(viewEntity, viewEntity),
            new Tag[] {tag},
            NameIdentifier.of(VIEW_NAMESPACE, "v5"));

    assertEquals(1, po.getTags().size());
    assertEquals("pii", po.getTags().get(0).getTagName());
  }

  @Test
  void testToViewSearchEntityPOWithoutColumns() {
    ViewEntity viewEntity =
        ViewEntity.builder()
            .withId(1001L)
            .withName("v6")
            .withNamespace(VIEW_NAMESPACE)
            .withColumns(new Column[0])
            .withRepresentations(
                new Representation[] {
                  SQLRepresentation.builder().withDialect("trino").withSql("SELECT 1").build()
                })
            .withProperties(ImmutableMap.of())
            .withAuditInfo(AuditInfo.EMPTY)
            .build();

    SearchViewEntityPO po =
        EntityConverterUtils.toViewSearchEntityPO(
            EntityCombinedView.of(viewEntity, viewEntity),
            new Tag[0],
            NameIdentifier.of(VIEW_NAMESPACE, "v6"));

    assertTrue(po.getColumns().isEmpty());
  }

  private ViewEntity newViewEntity(String name, Map<String, String> properties) {
    return ViewEntity.builder()
        .withId(1000L)
        .withName(name)
        .withNamespace(VIEW_NAMESPACE)
        .withComment("demo view")
        .withColumns(
            new Column[] {
              ColumnDTO.builder()
                  .withName("id")
                  .withDataType(Types.IntegerType.get())
                  .withComment("the id")
                  .build()
            })
        .withRepresentations(
            new Representation[] {
              SQLRepresentation.builder().withDialect("trino").withSql("SELECT id FROM t1").build()
            })
        .withDefaultCatalog("hive_catalog")
        .withDefaultSchema("default")
        .withProperties(properties)
        .withAuditInfo(AuditInfo.EMPTY)
        .build();
  }
}
