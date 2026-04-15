/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.sqlserver;

import java.util.Locale;
import java.util.Set;
import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.connector.capability.CapabilityResult;

/**
 * Capability definition for SQL Server catalog. Defines SQL Server-specific naming rules and
 * reserved schema names.
 */
public class SqlServerCatalogCapability implements Capability {

  /**
   * SQL Server identifiers: up to 128 characters, start with letter/underscore/@/#, contain
   * letters, digits, underscores, @, #, $.
   */
  public static final String SQLSERVER_NAME_PATTERN = "^[a-zA-Z_\\p{L}@#][\\w\\p{L}@#$]{0,127}$";

  private static final Set<String> RESERVED_SCHEMAS =
      Set.of(
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
          "db_denydatawriter");

  @Override
  public CapabilityResult specificationOnName(Scope scope, String name) {
    if (!name.matches(SQLSERVER_NAME_PATTERN)) {
      return CapabilityResult.unsupported(
          String.format("The %s name '%s' is illegal.", scope, name));
    }
    if (scope == Scope.SCHEMA && RESERVED_SCHEMAS.contains(name.toLowerCase(Locale.ROOT))) {
      return CapabilityResult.unsupported(
          String.format("The %s name '%s' is reserved.", scope, name));
    }
    return CapabilityResult.SUPPORTED;
  }

  @Override
  public CapabilityResult caseSensitiveOnName(Scope scope) {
    return CapabilityResult.unsupported("SQL Server is case-insensitive by default.");
  }
}
