/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.sqlserver.operation;

import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Property-style tests for SqlServerSchemaOperations. Validates schema DDL SQL generation syntax
 * and system schema filtering behavior.
 */
public class TestSqlServerSchemaOperationsProperty {

  private final SqlServerSchemaOperations schemaOps = new SqlServerSchemaOperations();

  /** Provides valid schema names for property testing. */
  static Stream<String> validSchemaNames() {
    return Stream.of(
        "a",
        "test_schema",
        "MySchema123",
        "schema_with_underscore",
        "#temp_schema",
        "@at_schema",
        "$dollar_schema",
        "a".repeat(128));
  }

  @ParameterizedTest
  @MethodSource("validSchemaNames")
  void testCreateDatabaseSqlContainsCorrectSyntax(String schemaName) {
    String sql = schemaOps.generateCreateDatabaseSql(schemaName, null, null);
    Assertions.assertTrue(
        sql.contains("CREATE SCHEMA [" + schemaName + "] AUTHORIZATION [dbo]"),
        "CREATE SCHEMA SQL should use square bracket quoting: " + sql);
  }

  @ParameterizedTest
  @MethodSource("validSchemaNames")
  void testDropDatabaseSqlContainsCorrectSyntax(String schemaName) {
    String sql = schemaOps.generateDropDatabaseSql(schemaName, false);
    Assertions.assertTrue(
        sql.contains("DROP SCHEMA [" + schemaName + "]"),
        "DROP SCHEMA SQL should use square bracket quoting: " + sql);
  }

  @Test
  void testSystemSchemaSetContainsAll12SystemSchemas() {
    Set<String> sysSchemas = schemaOps.createSysDatabaseNameSet();
    String[] expectedSystemSchemas = {
      "guest",
      "information_schema",
      "sys",
      "db_owner",
      "db_accessadmin",
      "db_securityadmin",
      "db_ddladmin",
      "db_backupoperator",
      "db_datareader",
      "db_datawriter",
      "db_denydatareader",
      "db_denydatawriter"
    };
    Assertions.assertEquals(12, sysSchemas.size());
    for (String schema : expectedSystemSchemas) {
      Assertions.assertTrue(
          sysSchemas.contains(schema), "System schema set should contain: " + schema);
    }
  }

  @Test
  void testDboNotInSystemSchemaSet() {
    Set<String> sysSchemas = schemaOps.createSysDatabaseNameSet();
    Assertions.assertFalse(sysSchemas.contains("dbo"), "dbo should NOT be in system schema set");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "guest",
        "information_schema",
        "sys",
        "db_owner",
        "db_accessadmin",
        "db_securityadmin",
        "db_ddladmin",
        "db_backupoperator",
        "db_datareader",
        "db_datawriter",
        "db_denydatareader",
        "db_denydatawriter"
      })
  void testSystemSchemaSetContainsAllSystemSchemas(String systemSchema) {
    Set<String> sysSchemas = schemaOps.createSysDatabaseNameSet();
    Assertions.assertTrue(
        sysSchemas.contains(systemSchema), systemSchema + " should be in the system schema set");
  }

  @ParameterizedTest
  @ValueSource(strings = {"dbo", "my_schema", "public", "test"})
  void testSystemSchemaSetDoesNotContainUserSchemas(String userSchema) {
    Set<String> sysSchemas = schemaOps.createSysDatabaseNameSet();
    Assertions.assertFalse(
        sysSchemas.contains(userSchema), userSchema + " should NOT be in the system schema set");
  }
}
