/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.sqlserver;

import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.connector.capability.CapabilityResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for SqlServerCatalogCapability. Validates identifier name validation and reserved
 * schema name handling.
 */
public class TestSqlServerCatalogCapability {

  private final SqlServerCatalogCapability capability = new SqlServerCatalogCapability();

  @Test
  void testValidNames() {
    for (Capability.Scope scope : Capability.Scope.values()) {
      CapabilityResult result = capability.specificationOnName(scope, "test_table");
      Assertions.assertTrue(result.supported());

      result = capability.specificationOnName(scope, "TestTable123");
      Assertions.assertTrue(result.supported());

      result = capability.specificationOnName(scope, "_underscore_start");
      Assertions.assertTrue(result.supported());
    }
  }

  @Test
  void testSqlServerSpecialStartChars() {
    // SQL Server allows @, #, $ as starting characters
    for (Capability.Scope scope : Capability.Scope.values()) {
      CapabilityResult result = capability.specificationOnName(scope, "@at_start");
      Assertions.assertTrue(result.supported());

      result = capability.specificationOnName(scope, "#hash_start");
      Assertions.assertTrue(result.supported());

      result = capability.specificationOnName(scope, "$dollar_start");
      Assertions.assertFalse(result.supported());
    }
  }

  @Test
  void testUnicodeNames() {
    CapabilityResult result = capability.specificationOnName(Capability.Scope.TABLE, "测试表");
    Assertions.assertTrue(result.supported());

    result = capability.specificationOnName(Capability.Scope.TABLE, "tableção");
    Assertions.assertTrue(result.supported());

    result = capability.specificationOnName(Capability.Scope.TABLE, "αβγ_table");
    Assertions.assertTrue(result.supported());
  }

  @Test
  void testMaxLength128() {
    String maxLengthName = "a".repeat(128);
    CapabilityResult result = capability.specificationOnName(Capability.Scope.TABLE, maxLengthName);
    Assertions.assertTrue(result.supported());

    String tooLongName = "a".repeat(129);
    result = capability.specificationOnName(Capability.Scope.TABLE, tooLongName);
    Assertions.assertFalse(result.supported());
    Assertions.assertTrue(result.unsupportedMessage().contains("illegal"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "1_starts_with_digit", "table with space", "table.with.dot"})
  void testInvalidNames(String name) {
    CapabilityResult result = capability.specificationOnName(Capability.Scope.TABLE, name);
    Assertions.assertFalse(result.supported());
    Assertions.assertTrue(result.unsupportedMessage().contains("illegal"));
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
  void testReservedSchemaNames(String reservedName) {
    CapabilityResult result = capability.specificationOnName(Capability.Scope.SCHEMA, reservedName);
    Assertions.assertFalse(result.supported());
    Assertions.assertTrue(result.unsupportedMessage().contains("reserved"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"GUEST", "SYS", "Information_Schema", "DB_OWNER"})
  void testReservedSchemaNamesAreCaseInsensitive(String reservedName) {
    CapabilityResult result = capability.specificationOnName(Capability.Scope.SCHEMA, reservedName);
    Assertions.assertFalse(result.supported());
    Assertions.assertTrue(result.unsupportedMessage().contains("reserved"));
  }

  @Test
  void testReservedNamesNotAppliedToOtherScopes() {
    // Reserved schema names should not affect other scopes
    CapabilityResult result = capability.specificationOnName(Capability.Scope.COLUMN, "sys");
    Assertions.assertTrue(result.supported());

    result = capability.specificationOnName(Capability.Scope.TABLE, "guest");
    Assertions.assertTrue(result.supported());
  }

  @Test
  void testCaseSensitiveOnNameUnsupported() {
    for (Capability.Scope scope : Capability.Scope.values()) {
      CapabilityResult result = capability.caseSensitiveOnName(scope);
      Assertions.assertFalse(result.supported());
      Assertions.assertTrue(result.unsupportedMessage().contains("case-insensitive"));
    }
  }

  @Test
  void testMinimumLengthName() {
    CapabilityResult result = capability.specificationOnName(Capability.Scope.TABLE, "a");
    Assertions.assertTrue(result.supported());
  }

  @ParameterizedTest
  @ValueSource(strings = {"table%percent", "table!exclaim", "table^caret", "table&ampersand"})
  void testInvalidSpecialChars(String name) {
    CapabilityResult result = capability.specificationOnName(Capability.Scope.TABLE, name);
    Assertions.assertFalse(result.supported());
  }
}
