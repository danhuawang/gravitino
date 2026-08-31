/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.mapper.provider.base;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.apache.gravitino.storage.relational.mapper.SecurableObjectMapper;
import org.junit.jupiter.api.Test;

public class TestDatastratoSecurableObjectBaseSQLProvider {

  @Test
  public void testListSecurableObjectsByRoleIds() {
    String sql =
        new DatastratoSecurableObjectBaseSQLProvider()
            .listSecurableObjectsByRoleIds(List.of(1L, 2L));

    assertTrue(sql.contains("FROM " + SecurableObjectMapper.SECURABLE_OBJECT_TABLE_NAME));
    assertTrue(sql.contains("WHERE role_id IN"));
    assertTrue(sql.contains("<foreach collection='roleIds'"));
    assertTrue(sql.contains("AND deleted_at = 0"));
  }
}
