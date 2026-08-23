/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.tag.mapper;

import com.datastrato.gravitino.tag.mapper.provider.base.DatastratoTagPolicyMetadataObjectBaseSQLProvider;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.ibatis.annotations.Param;

/** SQL provider factory for enterprise batch tag and policy queries. */
public class DatastratoTagPolicyMetadataObjectSQLProviderFactory {

  private static final Map<JDBCBackendType, DatastratoTagPolicyMetadataObjectBaseSQLProvider>
      PROVIDERS =
          ImmutableMap.of(
              JDBCBackendType.MYSQL, new DatastratoTagPolicyMetadataObjectMySQLProvider(),
              JDBCBackendType.H2, new DatastratoTagPolicyMetadataObjectH2Provider(),
              JDBCBackendType.POSTGRESQL,
                  new DatastratoTagPolicyMetadataObjectPostgreSQLProvider());

  private DatastratoTagPolicyMetadataObjectSQLProviderFactory() {}

  /**
   * Batch lists tag relations SQL.
   *
   * @param metadataObjectIds The metadata object ids.
   * @return The SQL string.
   */
  public static String batchListTagRelPOsByMetadataObjectIds(
      @Param("metadataObjectIds") List<Long> metadataObjectIds) {
    return getProvider().batchListTagRelPOsByMetadataObjectIds(metadataObjectIds);
  }

  /**
   * Batch lists policy relations SQL.
   *
   * @param metadataObjectIds The metadata object ids.
   * @return The SQL string.
   */
  public static String batchListPolicyRelPOsByMetadataObjectIds(
      @Param("metadataObjectIds") List<Long> metadataObjectIds) {
    return getProvider().batchListPolicyRelPOsByMetadataObjectIds(metadataObjectIds);
  }

  private static DatastratoTagPolicyMetadataObjectBaseSQLProvider getProvider() {
    String databaseId =
        SqlSessionFactoryHelper.getInstance()
            .getSqlSessionFactory()
            .getConfiguration()
            .getDatabaseId();
    return PROVIDERS.get(JDBCBackendType.fromString(databaseId));
  }

  static class DatastratoTagPolicyMetadataObjectMySQLProvider
      extends DatastratoTagPolicyMetadataObjectBaseSQLProvider {}

  static class DatastratoTagPolicyMetadataObjectH2Provider
      extends DatastratoTagPolicyMetadataObjectBaseSQLProvider {}

  static class DatastratoTagPolicyMetadataObjectPostgreSQLProvider
      extends DatastratoTagPolicyMetadataObjectBaseSQLProvider {}
}
