/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.tag.mapper.DatastratoTagPolicyMetadataObjectMapper;
import com.datastrato.gravitino.tag.po.DatastratoTagRelPO;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.EntityCombinedTable;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.rel.ColumnDTO;
import org.apache.gravitino.dto.rel.TableDTO;
import org.apache.gravitino.dto.tag.TagDTO;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.json.JsonUtils;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.ColumnEntity;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.meta.TagEntity;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.storage.relational.session.SqlSessions;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** Tests {@link ColumnTagResolver}. */
public class TestColumnTagResolver {

  private static final String METALAKE = "metalake";
  private static final String CATALOG = "catalog";
  private static final String SCHEMA = "schema";
  private static final String TABLE = "table";

  /**
   * Tests managed-table tags through the real batch helper when the load result omits entity IDs.
   */
  @Test
  public void testManagedTableResolvesStoredTags() throws IOException {
    EntityCombinedTable loadedTable = EntityCombinedTable.of(tableDTO()).withImported(true);
    Assertions.assertNull(loadedTable.tableFromGravitino());
    EntityStore entityStore = mock(EntityStore.class);
    NameIdentifier identifier = NameIdentifierUtil.ofTable(METALAKE, CATALOG, SCHEMA, TABLE);
    when(entityStore.get(identifier, Entity.EntityType.TABLE, TableEntity.class))
        .thenReturn(((EntityCombinedTable) tableWithStoredColumnIds()).tableFromGravitino());
    GravitinoEnv env = mock(GravitinoEnv.class);
    when(env.entityStore()).thenReturn(entityStore);
    DatastratoTagPolicyMetadataObjectMapper mapper =
        mock(DatastratoTagPolicyMetadataObjectMapper.class);
    List<DatastratoTagRelPO> relations =
        List.of(
            tagRelation(11L, MetadataObject.Type.COLUMN, 101L, "PII"),
            tagRelation(10L, MetadataObject.Type.TABLE, 101L, "PII"),
            tagRelation(10L, MetadataObject.Type.TABLE, 102L, "Sensitive"));
    when(mapper.batchListTagRelPOsByMetadataObjectIds(
            argThat(ids -> ids != null && Set.copyOf(ids).equals(Set.of(10L, 11L, 12L)))))
        .thenReturn(relations);

    try (MockedStatic<GravitinoEnv> environment = mockStatic(GravitinoEnv.class);
        MockedStatic<SqlSessions> sessions = mockStatic(SqlSessions.class)) {
      environment.when(GravitinoEnv::getInstance).thenReturn(env);
      sessions
          .when(() -> SqlSessions.getMapper(DatastratoTagPolicyMetadataObjectMapper.class))
          .thenReturn(mapper);

      Map<String, TagDTO[]> result =
          new ColumnTagResolver()
              .resolve(METALAKE, CATALOG, SCHEMA, TABLE, loadedTable, tags -> tags);

      assertTags(
          result.get("email"), new String[] {"PII", "Sensitive"}, new boolean[] {false, true});
      assertTags(
          result.get("region"), new String[] {"PII", "Sensitive"}, new boolean[] {true, true});
      verify(entityStore).get(identifier, Entity.EntityType.TABLE, TableEntity.class);
      verify(mapper)
          .batchListTagRelPOsByMetadataObjectIds(
              argThat(ids -> Set.copyOf(ids).equals(Set.of(10L, 11L, 12L))));
      verify(mapper, never()).batchListPolicyRelPOsByMetadataObjectIds(any());
    }
  }

  /** Tests effective inheritance, authorization filtering, ordering, and known entity IDs. */
  @Test
  public void testResolveVisibleEffectiveTags() {
    Table table = tableWithStoredColumnIds();
    EntityStore entityStore = mock(EntityStore.class);
    MetadataObject catalogObject = object(MetadataObject.Type.CATALOG, CATALOG);
    MetadataObject schemaObject = object(MetadataObject.Type.SCHEMA, CATALOG, SCHEMA);
    MetadataObject tableObject = object(MetadataObject.Type.TABLE, CATALOG, SCHEMA, TABLE);
    MetadataObject emailObject =
        object(MetadataObject.Type.COLUMN, CATALOG, SCHEMA, TABLE, "email");

    Map<MetadataObject, TagEntity[]> directTags = new LinkedHashMap<>();
    directTags.put(emailObject, tags("column", "shared"));
    directTags.put(tableObject, tags("table", "shared"));
    directTags.put(schemaObject, tags("schema", "hidden"));
    directTags.put(catalogObject, tags("catalog"));
    AtomicReference<List<MetadataObject>> loadedObjects = new AtomicReference<>();
    AtomicReference<Map<MetadataObject, Optional<Long>>> knownIds = new AtomicReference<>();
    ColumnTagResolver resolver =
        new ColumnTagResolver(
            entityStore,
            (metalake, objects, entityIds) -> {
              Assertions.assertEquals(METALAKE, metalake);
              loadedObjects.set(new ArrayList<>(objects));
              knownIds.set(new LinkedHashMap<>(entityIds));
              return directTags;
            });

    Map<String, TagDTO[]> result =
        resolver.resolve(
            METALAKE,
            CATALOG,
            SCHEMA,
            TABLE,
            table,
            tags ->
                Arrays.stream(tags)
                    .filter(tag -> !tag.name().equals("hidden"))
                    .toArray(TagEntity[]::new));

    assertTags(
        result.get("email"),
        new String[] {"column", "shared", "table", "schema", "catalog"},
        new boolean[] {false, false, true, true, true});
    assertTags(
        result.get("region"),
        new String[] {"table", "shared", "schema", "catalog"},
        new boolean[] {true, true, true, true});
    Assertions.assertEquals(5, loadedObjects.get().size());
    Assertions.assertEquals(Optional.of(10L), knownIds.get().get(tableObject));
    Assertions.assertEquals(Optional.of(11L), knownIds.get().get(emailObject));
    Assertions.assertEquals(
        Optional.of(12L),
        knownIds.get().get(object(MetadataObject.Type.COLUMN, CATALOG, SCHEMA, TABLE, "region")));
    verifyNoInteractions(entityStore);
  }

  /** Tests that unstored columns remain present and can inherit tags from stored ancestors. */
  @Test
  public void testResolveForUnstoredColumn() throws IOException {
    Table table = tableDTO();
    EntityStore entityStore = mock(EntityStore.class);
    NameIdentifier identifier = NameIdentifierUtil.ofTable(METALAKE, CATALOG, SCHEMA, TABLE);
    when(entityStore.get(identifier, Entity.EntityType.TABLE, TableEntity.class))
        .thenThrow(new NoSuchEntityException("Table is not stored"));
    MetadataObject tableObject = object(MetadataObject.Type.TABLE, CATALOG, SCHEMA, TABLE);
    MetadataObject columnObject =
        object(MetadataObject.Type.COLUMN, CATALOG, SCHEMA, TABLE, "email");
    MetadataObject catalogObject = object(MetadataObject.Type.CATALOG, CATALOG);
    AtomicReference<Map<MetadataObject, Optional<Long>>> knownIds = new AtomicReference<>();
    ColumnTagResolver resolver =
        new ColumnTagResolver(
            entityStore,
            (metalake, objects, entityIds) -> {
              knownIds.set(new LinkedHashMap<>(entityIds));
              return Collections.singletonMap(catalogObject, tags("catalog"));
            });

    Map<String, TagDTO[]> result =
        resolver.resolve(METALAKE, CATALOG, SCHEMA, TABLE, table, tags -> tags);

    assertTags(result.get("email"), new String[] {"catalog"}, new boolean[] {true});
    Assertions.assertEquals(Optional.empty(), knownIds.get().get(tableObject));
    Assertions.assertEquals(Optional.empty(), knownIds.get().get(columnObject));
    verify(entityStore).get(identifier, Entity.EntityType.TABLE, TableEntity.class);
  }

  /** Tests that storage IO failures are not treated as missing table metadata. */
  @Test
  public void testStoredTableLookupPropagatesIOException() throws IOException {
    EntityStore entityStore = mock(EntityStore.class);
    NameIdentifier identifier = NameIdentifierUtil.ofTable(METALAKE, CATALOG, SCHEMA, TABLE);
    IOException failure = new IOException("Store unavailable");
    when(entityStore.get(identifier, Entity.EntityType.TABLE, TableEntity.class))
        .thenThrow(failure);
    ColumnTagResolver.DirectTagLoader directTagLoader =
        mock(ColumnTagResolver.DirectTagLoader.class);
    ColumnTagResolver resolver = new ColumnTagResolver(entityStore, directTagLoader);

    UncheckedIOException exception =
        Assertions.assertThrows(
            UncheckedIOException.class,
            () -> resolver.resolve(METALAKE, CATALOG, SCHEMA, TABLE, tableDTO(), tags -> tags));

    Assertions.assertSame(failure, exception.getCause());
    verifyNoInteractions(directTagLoader);
  }

  /** Tests that unexpected storage failures propagate instead of returning empty tags. */
  @Test
  public void testStoredTableLookupPropagatesRuntimeException() throws IOException {
    EntityStore entityStore = mock(EntityStore.class);
    NameIdentifier identifier = NameIdentifierUtil.ofTable(METALAKE, CATALOG, SCHEMA, TABLE);
    IllegalStateException failure = new IllegalStateException("Store unavailable");
    when(entityStore.get(identifier, Entity.EntityType.TABLE, TableEntity.class))
        .thenThrow(failure);
    ColumnTagResolver.DirectTagLoader directTagLoader =
        mock(ColumnTagResolver.DirectTagLoader.class);
    ColumnTagResolver resolver = new ColumnTagResolver(entityStore, directTagLoader);

    IllegalStateException exception =
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> resolver.resolve(METALAKE, CATALOG, SCHEMA, TABLE, tableDTO(), tags -> tags));

    Assertions.assertSame(failure, exception);
    verifyNoInteractions(directTagLoader);
  }

  /** Tests that an empty table avoids a tag-store query. */
  @Test
  public void testEmptyColumnsSkipTagLoading() {
    EntityStore entityStore = mock(EntityStore.class);
    TableDTO table =
        TableDTO.builder()
            .withName(TABLE)
            .withColumns(new ColumnDTO[0])
            .withAudit(AuditDTO.builder().build())
            .build();
    AtomicBoolean invoked = new AtomicBoolean(false);
    ColumnTagResolver resolver =
        new ColumnTagResolver(
            entityStore,
            (metalake, objects, entityIds) -> {
              invoked.set(true);
              return new LinkedHashMap<>();
            });

    Map<String, TagDTO[]> result =
        resolver.resolve(METALAKE, CATALOG, SCHEMA, TABLE, table, tags -> tags);

    Assertions.assertTrue(result.isEmpty());
    Assertions.assertFalse(invoked.get());
    verifyNoInteractions(entityStore);
  }

  private static Table tableWithStoredColumnIds() {
    AuditInfo audit = auditInfo();
    TableEntity tableEntity =
        TableEntity.builder()
            .withId(10L)
            .withName(TABLE)
            .withNamespace(Namespace.of(METALAKE, CATALOG, SCHEMA))
            .withColumns(
                List.of(
                    columnEntity(11L, "email", 0, audit), columnEntity(12L, "region", 1, audit)))
            .withAuditInfo(audit)
            .build();
    return EntityCombinedTable.of(tableDTO(), tableEntity);
  }

  private static TableDTO tableDTO() {
    return TableDTO.builder()
        .withName(TABLE)
        .withColumns(
            new ColumnDTO[] {
              ColumnDTO.builder().withName("email").withDataType(Types.StringType.get()).build(),
              ColumnDTO.builder().withName("region").withDataType(Types.StringType.get()).build()
            })
        .withAudit(AuditDTO.builder().build())
        .build();
  }

  private static ColumnEntity columnEntity(long id, String name, int position, AuditInfo audit) {
    return ColumnEntity.builder()
        .withId(id)
        .withName(name)
        .withPosition(position)
        .withDataType(Types.StringType.get())
        .withNullable(true)
        .withAutoIncrement(false)
        .withAuditInfo(audit)
        .build();
  }

  private static AuditInfo auditInfo() {
    return AuditInfo.builder().withCreator("creator").withCreateTime(Instant.now()).build();
  }

  private static MetadataObject object(MetadataObject.Type type, String... names) {
    return MetadataObjects.of(List.of(names), type);
  }

  private static TagEntity[] tags(String... names) {
    TagEntity[] tags = new TagEntity[names.length];
    for (int i = 0; i < names.length; i++) {
      tags[i] =
          TagEntity.builder()
              .withId((long) i + 1)
              .withName(names[i])
              .withNamespace(Namespace.of(METALAKE))
              .withComment("")
              .withProperties(Collections.emptyMap())
              .withAuditInfo(auditInfo())
              .build();
    }
    return tags;
  }

  private static DatastratoTagRelPO tagRelation(
      long objectId, MetadataObject.Type type, long tagId, String name)
      throws JsonProcessingException {
    DatastratoTagRelPO relation = mock(DatastratoTagRelPO.class);
    when(relation.getMetadataObjectId()).thenReturn(objectId);
    when(relation.getMetadataObjectType()).thenReturn(type.name());
    when(relation.getTagId()).thenReturn(tagId);
    when(relation.getTagName()).thenReturn(name);
    when(relation.getMetalakeId()).thenReturn(1L);
    when(relation.getProperties()).thenReturn("{}");
    when(relation.getAuditInfo())
        .thenReturn(JsonUtils.anyFieldMapper().writeValueAsString(auditInfo()));
    when(relation.getCurrentVersion()).thenReturn(0L);
    when(relation.getLastVersion()).thenReturn(0L);
    when(relation.getDeletedAt()).thenReturn(0L);
    return relation;
  }

  private static void assertTags(TagDTO[] tags, String[] names, boolean[] inherited) {
    Assertions.assertArrayEquals(
        names, Arrays.stream(tags).map(TagDTO::name).toArray(String[]::new));
    Assertions.assertEquals(names.length, inherited.length);
    for (int i = 0; i < tags.length; i++) {
      Assertions.assertEquals(Optional.of(inherited[i]), tags[i].inherited());
    }
  }
}
