/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.oracle.operations;

import com.google.common.collect.ImmutableSet;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
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

  private static final int ORACLE_MAINTAINED_COLUMN_MIN_VERSION = 12;
  private static final String LIST_ALL_USERS_SQL =
      "SELECT USERNAME FROM ALL_USERS ORDER BY USERNAME";
  private static final String LIST_ALL_USERS_WITH_ORACLE_MAINTAINED_SQL =
      "SELECT USERNAME, ORACLE_MAINTAINED FROM ALL_USERS ORDER BY USERNAME";
  // databaseName is already normalized to its canonical physical form by
  // OracleCatalogCapability.normalizeName before reaching load(), so this must not fold case again
  // (e.g. via UPPER()) or a quoted, case-sensitive schema name would be looked up as the wrong,
  // uppercase physical object.
  private static final String LOAD_USER_SQL = "SELECT USERNAME FROM ALL_USERS WHERE USERNAME = ?";

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
          "xs$null",
          // Oracle 12c/18c/19c/21c/23ai built-in accounts.
          "audsys",
          "baassys",
          "dbsfwuser",
          "dgpdb_int",
          "dip",
          "dvf",
          "dvsys",
          "ggsharedcap",
          "ggsys",
          "gsmadmin_internal",
          "gsmcatuser",
          "gsmuser",
          "lbacsys",
          "pdbadmin",
          "remote_scheduler_agent",
          "sys$umf",
          "sysbackup",
          "sysdg",
          "syskm",
          "sysrac",
          "vecsys");

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
    // Database (schema/user) names are returned exactly as Oracle stores them, with no synthetic
    // quoting added: Capability.normalizeName is not idempotent for a catalog whose folding depends
    // on whether the name was originally quoted, so this must not be run back through it (core's
    // SchemaNormalizeDispatcher.listSchemas deliberately does not re-normalize this result).
    List<String> databaseNames = new ArrayList<>();
    try (Connection connection = getConnection()) {
      DatabaseMetaData metadata = connection.getMetaData();
      boolean supportsOracleMaintained =
          metadata.getDatabaseMajorVersion() >= ORACLE_MAINTAINED_COLUMN_MIN_VERSION;
      String listUsersSql =
          supportsOracleMaintained ? LIST_ALL_USERS_WITH_ORACLE_MAINTAINED_SQL : LIST_ALL_USERS_SQL;
      try (Statement statement = connection.createStatement();
          ResultSet resultSet = statement.executeQuery(listUsersSql)) {
        while (resultSet.next()) {
          String databaseName = resultSet.getString("USERNAME");
          boolean oracleMaintained =
              supportsOracleMaintained
                  && "Y".equalsIgnoreCase(resultSet.getString("ORACLE_MAINTAINED"));
          // ORACLE_MAINTAINED covers accounts installed by optional Oracle components. Keep the
          // known-name check for Oracle 11g, which does not provide that column, and as a fallback
          // when an account is not marked consistently.
          if (!oracleMaintained && !isSystemDatabase(databaseName)) {
            databaseNames.add(databaseName);
          }
        }
      }
      return databaseNames;
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
  }

  @Override
  public JdbcSchema load(String databaseName) throws NoSuchSchemaException {
    // databaseName has already been normalized to its canonical physical form by
    // OracleCatalogCapability.normalizeName before reaching this method, so it can be used as-is.
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
