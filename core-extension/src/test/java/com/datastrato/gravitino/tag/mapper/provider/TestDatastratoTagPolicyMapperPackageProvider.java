/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.tag.mapper.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.tag.mapper.DatastratoTagPolicyMetadataObjectMapper;
import com.datastrato.gravitino.tag.mapper.DatastratoTagPolicyMetadataObjectSQLProviderFactory;
import com.datastrato.gravitino.tag.mapper.provider.base.DatastratoTagPolicyMetadataObjectBaseSQLProvider;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;

public class TestDatastratoTagPolicyMapperPackageProvider {

  @Test
  public void testGetMapperClasses() {
    List<Class<?>> mapperClasses =
        new DatastratoTagPolicyMapperPackageProvider().getMapperClasses();

    assertEquals(List.of(DatastratoTagPolicyMetadataObjectMapper.class), mapperClasses);
  }

  @Test
  public void testBatchRelationQueriesUseIds() {
    DatastratoTagPolicyMetadataObjectBaseSQLProvider provider =
        new DatastratoTagPolicyMetadataObjectBaseSQLProvider();
    List<Long> metadataObjectIds = List.of(1L, 2L, 3L);

    String tagSql = provider.batchListTagRelPOsByMetadataObjectIds(metadataObjectIds);
    String policySql = provider.batchListPolicyRelPOsByMetadataObjectIds(metadataObjectIds);

    assertTrue(tagSql.contains("te.metadata_object_id IN"));
    assertTrue(policySql.contains("pe.metadata_object_id IN"));
    assertFalse(tagSql.contains("allowed_values"));
    assertFalse(tagSql.contains("tag_value"));

    assertExpandedSql(tagSql, metadataObjectIds);
    assertExpandedSql(policySql, metadataObjectIds);
  }

  @Test
  public void testFactorySelectsEverySupportedDialect() throws IllegalAccessException {
    SqlSessionFactory previousFactory =
        (SqlSessionFactory)
            FieldUtils.readStaticField(SqlSessionFactoryHelper.class, "sqlSessionFactory", true);
    try {
      for (String databaseId : List.of("h2", "mysql", "postgresql")) {
        SqlSessionFactory factory = mock(SqlSessionFactory.class);
        Configuration configuration = new Configuration();
        configuration.setDatabaseId(databaseId);
        when(factory.getConfiguration()).thenReturn(configuration);
        FieldUtils.writeStaticField(
            SqlSessionFactoryHelper.class, "sqlSessionFactory", factory, true);

        String sql =
            DatastratoTagPolicyMetadataObjectSQLProviderFactory
                .batchListTagRelPOsByMetadataObjectIds(List.of(1L));
        assertTrue(sql.contains("te.metadata_object_id IN"));
        String policySql =
            DatastratoTagPolicyMetadataObjectSQLProviderFactory
                .batchListPolicyRelPOsByMetadataObjectIds(List.of(1L));
        assertTrue(policySql.contains("pe.metadata_object_id IN"));
      }
    } finally {
      FieldUtils.writeStaticField(
          SqlSessionFactoryHelper.class, "sqlSessionFactory", previousFactory, true);
    }
  }

  private void assertExpandedSql(String sql, List<Long> metadataObjectIds) {
    Configuration configuration = new Configuration();
    SqlSource sqlSource = new XMLLanguageDriver().createSqlSource(configuration, sql, Map.class);
    BoundSql boundSql = sqlSource.getBoundSql(Map.of("metadataObjectIds", metadataObjectIds));

    assertTrue(boundSql.getSql().contains("metadata_object_id IN"));
    assertEquals(
        metadataObjectIds.size(),
        boundSql.getSql().chars().filter(character -> character == '?').count());
    assertEquals(metadataObjectIds.size(), boundSql.getParameterMappings().size());
  }
}
