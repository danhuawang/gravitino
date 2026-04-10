/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.oracle.operations;

import java.util.Collections;
import java.util.Set;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestOracleDatabaseOperations {

  private static final String UNSUPPORTED_MSG = "To be implemented in the future";

  private static final class OracleDatabaseOperationsForTest extends OracleDatabaseOperations {
    String generateCreateDatabaseSqlForTest(String databaseName) {
      return super.generateCreateDatabaseSql(databaseName, null, Collections.emptyMap());
    }

    String generateDropDatabaseSqlForTest(String databaseName) {
      return super.generateDropDatabaseSql(databaseName, false);
    }

    boolean supportSchemaCommentForTest() {
      return super.supportSchemaComment();
    }

    Set<String> createSysDatabaseNameSetForTest() {
      return super.createSysDatabaseNameSet();
    }
  }

  private final OracleDatabaseOperationsForTest operations = new OracleDatabaseOperationsForTest();

  @Test
  void testSchemaCommentAndSystemUsers() throws NoSuchSchemaException {
    Assertions.assertFalse(operations.supportSchemaCommentForTest());

    Set<String> systemUsers = operations.createSysDatabaseNameSetForTest();
    Assertions.assertTrue(systemUsers.contains("SYS"));
    Assertions.assertTrue(systemUsers.contains("SYSTEM"));
    Assertions.assertTrue(systemUsers.contains("ANONYMOUS"));
    Assertions.assertTrue(systemUsers.contains("XS$NULL"));
    Assertions.assertFalse(systemUsers.contains("APP_USER"));
    Assertions.assertTrue(systemUsers.stream().allMatch(name -> name.equals(name.toUpperCase())));
  }
}
