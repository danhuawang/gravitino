/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.sqlserver.operation;

import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Unit tests for SqlServerSchemaOperations SQL generation and system schema filtering. */
public class TestSqlServerSchemaOperations {

  private final SqlServerSchemaOperations schemaOps = new SqlServerSchemaOperations();

  @Test
  void testGenerateCreateDatabaseSql() {
    String sql = schemaOps.generateCreateDatabaseSql("test_schema", null, null);
    Assertions.assertEquals("CREATE SCHEMA [test_schema] AUTHORIZATION [dbo]", sql);
  }

  @Test
  void testGenerateCreateDatabaseSqlWithSpecialChars() {
    String sql = schemaOps.generateCreateDatabaseSql("my_schema#1", null, null);
    Assertions.assertEquals("CREATE SCHEMA [my_schema#1] AUTHORIZATION [dbo]", sql);
  }

  @Test
  void testGenerateCreateDatabaseSqlRejectsProperties() {
    Assertions.assertThrows(
        UnsupportedOperationException.class,
        () ->
            schemaOps.generateCreateDatabaseSql(
                "test_schema", null, Collections.singletonMap("key", "value")));
  }

  @Test
  void testGenerateDropDatabaseSql() {
    String sql = schemaOps.generateDropDatabaseSql("test_schema", false);
    Assertions.assertEquals("DROP SCHEMA [test_schema]", sql);
  }

  @Test
  void testGenerateDropDatabaseSqlCascadeIgnored() {
    // SQL Server does not support CASCADE; the flag is ignored
    String sql = schemaOps.generateDropDatabaseSql("test_schema", true);
    Assertions.assertEquals("DROP SCHEMA [test_schema]", sql);
  }

  @Test
  void testSystemSchemaSetContainsAllSystemSchemas() {
    Set<String> sysSchemas = schemaOps.createSysDatabaseNameSet();
    Assertions.assertEquals(12, sysSchemas.size());

    // Verify all 12 system schemas are present
    Assertions.assertTrue(sysSchemas.contains("guest"));
    Assertions.assertTrue(sysSchemas.contains("information_schema"));
    Assertions.assertTrue(sysSchemas.contains("sys"));
    Assertions.assertTrue(sysSchemas.contains("db_owner"));
    Assertions.assertTrue(sysSchemas.contains("db_accessadmin"));
    Assertions.assertTrue(sysSchemas.contains("db_securityadmin"));
    Assertions.assertTrue(sysSchemas.contains("db_ddladmin"));
    Assertions.assertTrue(sysSchemas.contains("db_backupoperator"));
    Assertions.assertTrue(sysSchemas.contains("db_datareader"));
    Assertions.assertTrue(sysSchemas.contains("db_datawriter"));
    Assertions.assertTrue(sysSchemas.contains("db_denydatareader"));
    Assertions.assertTrue(sysSchemas.contains("db_denydatawriter"));
  }

  @Test
  void testSystemSchemaSetDoesNotContainDbo() {
    Set<String> sysSchemas = schemaOps.createSysDatabaseNameSet();
    Assertions.assertFalse(sysSchemas.contains("dbo"));
  }

  @Test
  void testSupportSchemaComment() {
    Assertions.assertFalse(schemaOps.supportSchemaComment());
  }
}
