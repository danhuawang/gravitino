/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.storage.mapper;

import java.util.Map;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;

final class SQLProviderFactoryHelper {
  private SQLProviderFactoryHelper() {}

  static <T> T getProvider(
      String databaseId, Map<JDBCBackendType, T> providerMap, Class<?> providerFactoryClass) {
    if (databaseId == null) {
      throw new IllegalStateException(
          String.format(
              "MyBatis databaseId is not configured for %s.",
              providerFactoryClass.getSimpleName()));
    }

    try {
      JDBCBackendType jdbcBackendType = JDBCBackendType.fromString(databaseId);
      T provider = providerMap.get(jdbcBackendType);
      if (provider != null) {
        return provider;
      }

      throw new IllegalStateException(
          String.format(
              "No %s registered for backend %s (databaseId: %s)",
              providerFactoryClass.getSimpleName(), jdbcBackendType, databaseId));
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          String.format(
              "Unsupported %s databaseId: %s, supported backends: %s",
              providerFactoryClass.getSimpleName(), databaseId, providerMap.keySet()),
          e);
    }
  }

  static <T> T currentProvider(Map<JDBCBackendType, T> providerMap, Class<?> providerFactoryClass) {
    return getProvider(currentDatabaseId(), providerMap, providerFactoryClass);
  }

  static String currentDatabaseId() {
    return SqlSessionFactoryHelper.getInstance()
        .getSqlSessionFactory()
        .getConfiguration()
        .getDatabaseId();
  }
}
