/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.jdbc;

import java.util.Map;
import org.apache.gravitino.connector.authorization.AuthorizationPlugin;
import org.apache.gravitino.connector.authorization.BaseAuthorization;

public class JdbcEnterpriseAuthorization extends BaseAuthorization<JdbcEnterpriseAuthorization> {

  public String shortName() {
    return "jdbc-enterprise";
  }

  public AuthorizationPlugin newPlugin(
      String metalake, String catalogProvider, Map<String, String> config) {
    switch (catalogProvider) {
      case "hive":
        return new HiveJdbcAuthorizationPlugin(config);
      default:
        throw new IllegalArgumentException("Unknown catalog provider: " + catalogProvider);
    }
  }
}
