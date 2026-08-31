/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.tag.mapper.DatastratoTagPolicyMetadataObjectMapper;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.TransactionIsolationLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestDatastratoTagPolicyBatchHelper {

  private SqlSessionFactory previousSqlSessionFactory;
  private DatastratoTagPolicyMetadataObjectMapper mapper;

  @BeforeEach
  public void setUp() throws IllegalAccessException {
    previousSqlSessionFactory =
        (SqlSessionFactory)
            FieldUtils.readStaticField(SqlSessionFactoryHelper.class, "sqlSessionFactory", true);
    SqlSessionFactory sqlSessionFactory = mock(SqlSessionFactory.class);
    SqlSession sqlSession = mock(SqlSession.class);
    mapper = mock(DatastratoTagPolicyMetadataObjectMapper.class);
    when(sqlSessionFactory.openSession(any(TransactionIsolationLevel.class)))
        .thenReturn(sqlSession);
    when(sqlSession.getMapper(DatastratoTagPolicyMetadataObjectMapper.class)).thenReturn(mapper);
    FieldUtils.writeStaticField(
        SqlSessionFactoryHelper.class, "sqlSessionFactory", sqlSessionFactory, true);
  }

  @AfterEach
  public void tearDown() throws IllegalAccessException {
    FieldUtils.writeStaticField(
        SqlSessionFactoryHelper.class, "sqlSessionFactory", previousSqlSessionFactory, true);
  }

  @Test
  public void testEmptyBatchFetch() {
    DatastratoTagPolicyBatchHelper.TagPolicyBatchResult result =
        DatastratoTagPolicyBatchHelper.batchFetchDirectTagPolicies(
            "metalake", Collections.emptyList());
    assertTrue(result.tags().isEmpty());
    assertTrue(result.policies().isEmpty());
  }

  @Test
  public void testBatchFetchUsesSharedResolutionAndKeepsUnstoredObjects() {
    String metalake = "metalake";
    Namespace tableNamespace = Namespace.of(metalake, "catalog", "schema");
    MetadataObject storedObject =
        MetadataObjects.of(List.of("catalog", "schema", "stored"), MetadataObject.Type.TABLE);
    MetadataObject unstoredObject =
        MetadataObjects.of(List.of("catalog", "schema", "unstored"), MetadataObject.Type.TABLE);
    List<NameIdentifier> identifiers =
        List.of(
            NameIdentifier.of(tableNamespace, "stored"),
            NameIdentifier.of(tableNamespace, "unstored"));
    TableEntity storedEntity =
        TableEntity.builder()
            .withId(1L)
            .withName("stored")
            .withNamespace(tableNamespace)
            .withAuditInfo(
                AuditInfo.builder().withCreator("creator").withCreateTime(Instant.now()).build())
            .build();
    EntityStore entityStore = mock(EntityStore.class);
    when(entityStore.batchGet(identifiers, Entity.EntityType.TABLE, TableEntity.class))
        .thenReturn(List.of(storedEntity));
    List<Long> ids = List.of(1L);
    when(mapper.batchListTagRelPOsByMetadataObjectIds(ids)).thenReturn(Collections.emptyList());
    when(mapper.batchListPolicyRelPOsByMetadataObjectIds(ids)).thenReturn(Collections.emptyList());

    DatastratoTagPolicyBatchHelper.TagPolicyBatchResult result =
        DatastratoTagPolicyBatchHelper.batchFetchDirectTagPolicies(
            metalake, List.of(storedObject, unstoredObject), Collections.emptyMap(), entityStore);

    assertEquals(2, result.tags().size());
    assertEquals(2, result.policies().size());
    assertEquals(0, result.tags().get(storedObject).length);
    assertEquals(0, result.tags().get(unstoredObject).length);
    assertEquals(0, result.policies().get(storedObject).length);
    assertEquals(0, result.policies().get(unstoredObject).length);
    verify(entityStore).batchGet(identifiers, Entity.EntityType.TABLE, TableEntity.class);
    verify(mapper).batchListTagRelPOsByMetadataObjectIds(ids);
    verify(mapper).batchListPolicyRelPOsByMetadataObjectIds(ids);
  }

  @Test
  public void testBatchFetchReusesKnownIdsAndQueriesAllTypesTogether() {
    MetadataObject table =
        MetadataObjects.of(List.of("catalog", "schema", "table"), MetadataObject.Type.TABLE);
    MetadataObject schema =
        MetadataObjects.of(List.of("catalog", "schema"), MetadataObject.Type.SCHEMA);
    MetadataObject unstoredTable =
        MetadataObjects.of(List.of("catalog", "schema", "unstored"), MetadataObject.Type.TABLE);
    EntityStore entityStore = mock(EntityStore.class);
    List<Long> ids = List.of(1L, 2L);
    when(mapper.batchListTagRelPOsByMetadataObjectIds(ids)).thenReturn(Collections.emptyList());
    when(mapper.batchListPolicyRelPOsByMetadataObjectIds(ids)).thenReturn(Collections.emptyList());

    DatastratoTagPolicyBatchHelper.TagPolicyBatchResult result =
        DatastratoTagPolicyBatchHelper.batchFetchDirectTagPolicies(
            "metalake",
            List.of(table, schema, unstoredTable),
            Map.of(
                table, Optional.of(1L), schema, Optional.of(2L), unstoredTable, Optional.empty()),
            entityStore);

    assertEquals(3, result.tags().size());
    assertEquals(3, result.policies().size());
    verifyNoInteractions(entityStore);
    verify(mapper).batchListTagRelPOsByMetadataObjectIds(ids);
    verify(mapper).batchListPolicyRelPOsByMetadataObjectIds(ids);
  }

  @Test
  public void testBatchFetchPropagatesRelationQueryFailure() {
    MetadataObject table =
        MetadataObjects.of(List.of("catalog", "schema", "table"), MetadataObject.Type.TABLE);
    EntityStore entityStore = mock(EntityStore.class);
    List<Long> ids = List.of(1L);
    when(mapper.batchListTagRelPOsByMetadataObjectIds(ids))
        .thenThrow(new IllegalStateException("Tag relation query failed"));

    assertThrows(
        IllegalStateException.class,
        () ->
            DatastratoTagPolicyBatchHelper.batchFetchDirectTagPolicies(
                "metalake", List.of(table), Map.of(table, Optional.of(1L)), entityStore));

    verifyNoInteractions(entityStore);
  }
}
