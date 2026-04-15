/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.oracle.operations;

import com.google.common.collect.ImmutableSet;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.gravitino.catalog.jdbc.JdbcSchema;
import org.apache.gravitino.catalog.jdbc.operation.JdbcDatabaseOperations;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.meta.AuditInfo;

/** Database (schema/user) operations for Oracle. */
public class OracleDatabaseOperations extends JdbcDatabaseOperations {

  private static final String LIST_ALL_USERS_SQL =
      "SELECT USERNAME FROM ALL_USERS ORDER BY USERNAME";
  private static final String LOAD_USER_SQL =
      "SELECT USERNAME FROM ALL_USERS WHERE USERNAME = UPPER(?)";

  private static final Set<String> ORACLE_SYSTEM_USERS =
      ImmutableSet.of(
          "sys",
          "system",
          "outln",
          "dba",
          "dbsnmp",
          "mgmt_view",
          "sysman",
          "anonymous",
          "apex_030200",
          "apex_public_user",
          "appqossys",
          "ctxsys",
          "exfsys",
          "flows_files",
          "mdsys",
          "olapsys",
          "oracle_ocm",
          "orddata",
          "ordplugins",
          "ordsys",
          "owbsys",
          "owbsys_audit",
          "wmsys",
          "xdb",
          "xs$null");

  @Override
  protected String generateCreateDatabaseSql(
      String databaseName, String comment, Map<String, String> properties) {
    throw new UnsupportedOperationException(
        "Oracle catalog does not support creating schemas (users).");
  }

  @Override
  protected String generateDropDatabaseSql(String databaseName, boolean cascade) {
    throw new UnsupportedOperationException(
        "Oracle catalog does not support dropping schemas (users).");
  }

  @Override
  public List<String> listDatabases() {
    List<String> databaseNames = new ArrayList<>();
    try (Connection connection = getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(LIST_ALL_USERS_SQL)) {
      while (resultSet.next()) {
        String databaseName = resultSet.getString("USERNAME");
        if (!isSystemDatabase(databaseName)) {
          databaseNames.add(databaseName);
        }
      }
      return databaseNames;
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
  }

  @Override
  public JdbcSchema load(String databaseName) throws NoSuchSchemaException {
    try (Connection connection = getConnection();
        PreparedStatement statement = connection.prepareStatement(LOAD_USER_SQL)) {
      statement.setString(1, databaseName);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new NoSuchSchemaException("Database %s could not be found", databaseName);
        }
        return JdbcSchema.builder()
            .withName(resultSet.getString("USERNAME"))
            .withComment("")
            .withProperties(Map.of())
            .withAuditInfo(AuditInfo.EMPTY)
            .build();
      }
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
  }

  @Override
  protected boolean supportSchemaComment() {
    // Oracle has no COMMENT ON SCHEMA syntax
    return false;
  }

  @Override
  protected Set<String> createSysDatabaseNameSet() {
    return ORACLE_SYSTEM_USERS;
  }
}
