/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider.postgresql;

import com.datastrato.gravitino.scim.storage.mapper.ScimUserMetaMapper;
import com.datastrato.gravitino.scim.storage.po.ScimUserMetaPO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestScimUserMetaPostgreSQLProvider {

  @Test
  void testInsertCastsEnabledToSmallInt() {
    ScimUserMetaPostgreSQLProvider provider = new ScimUserMetaPostgreSQLProvider();
    String sql = provider.insert(ScimUserMetaPO.builder().withEnabled(true).build());

    Assertions.assertTrue(sql.contains(ScimUserMetaMapper.TABLE_NAME));
    Assertions.assertTrue(
        sql.contains("CASE WHEN #{userMeta.enabled} THEN 1 ELSE 0 END"),
        "PostgreSQL insert must map enabled boolean to SMALLINT");
  }

  @Test
  void testUpdateEnabledCastsToSmallInt() {
    ScimUserMetaPostgreSQLProvider provider = new ScimUserMetaPostgreSQLProvider();

    String byExternalId = provider.updateEnabled("ext-1", true);
    Assertions.assertTrue(
        byExternalId.contains("SET enabled = CASE WHEN #{enabled} THEN 1 ELSE 0 END"));

    String byUserId = provider.updateEnabledByUserId(42L, false);
    Assertions.assertTrue(
        byUserId.contains("SET enabled = CASE WHEN #{enabled} THEN 1 ELSE 0 END"));
  }
}
