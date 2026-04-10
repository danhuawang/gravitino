/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.oracle.operations;

import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.util.Set;
import org.apache.gravitino.catalog.jdbc.JdbcSchema;
import org.apache.gravitino.catalog.jdbc.operation.JdbcDatabaseOperations;
import org.apache.gravitino.exceptions.NoSuchSchemaException;

/** Database (schema/user) operations for Oracle. */
public class OracleDatabaseOperations extends JdbcDatabaseOperations {

  private static final Set<String> ORACLE_SYSTEM_USERS =
      ImmutableSet.of(
          "SYS",
          "SYSTEM",
          "OUTLN",
          "DBA",
          "DBSNMP",
          "MGMT_VIEW",
          "SYSMAN",
          "ANONYMOUS",
          "APEX_030200",
          "APEX_PUBLIC_USER",
          "APPQOSSYS",
          "CTXSYS",
          "EXFSYS",
          "FLOWS_FILES",
          "MDSYS",
          "OLAPSYS",
          "ORACLE_OCM",
          "ORDDATA",
          "ORDPLUGINS",
          "ORDSYS",
          "OWBSYS",
          "OWBSYS_AUDIT",
          "WMSYS",
          "XDB",
          "XS$NULL");

  @Override
  protected String generateCreateDatabaseSql(
      String databaseName, String comment, Map<String, String> properties) {
    throw new UnsupportedOperationException("To be implemented in the future");
  }

  @Override
  protected String generateDropDatabaseSql(String databaseName, boolean cascade) {
    throw new UnsupportedOperationException("To be implemented in the future");
  }

  @Override
  public JdbcSchema load(String databaseName) throws NoSuchSchemaException {
    throw new UnsupportedOperationException("To be implemented in the future");
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
