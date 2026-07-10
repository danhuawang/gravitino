/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider.base;

import com.datastrato.gravitino.scim.storage.mapper.provider.postgresql.ScimUserGroupRelPostgreSQLProvider;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestScimUserGroupRelBaseSQLProvider {

  @Test
  void testSoftDeleteGroupUsersEmpty() {
    ScimUserGroupRelBaseSQLProvider provider = new ScimUserGroupRelBaseSQLProvider();
    String script =
        provider.softDeleteMembersByGroupAndUserExternalIds(
            "test_metalake", "group-ext-1", Collections.emptyList());

    SqlSource sqlSource =
        new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class);
    Map<String, Object> params = new HashMap<>();
    params.put("metalakeName", "test_metalake");
    params.put("groupExternalId", "group-ext-1");
    params.put("userExternalIds", Collections.emptyList());

    BoundSql boundSql = sqlSource.getBoundSql(params);
    String normalizedSql = boundSql.getSql().replaceAll("\\s+", " ").trim();

    Assertions.assertFalse(
        normalizedSql.matches(".*\\bIN\\s*\\(\\s*\\).*"),
        "Empty userExternalIds should not generate invalid SQL IN (...) with no values");
    Assertions.assertTrue(
        normalizedSql.isEmpty(), "Empty userExternalIds should not generate UPDATE SQL");
  }

  @Test
  void testSoftDeleteGroupUsersNonEmpty() {
    ScimUserGroupRelBaseSQLProvider provider = new ScimUserGroupRelBaseSQLProvider();
    String script =
        provider.softDeleteMembersByGroupAndUserExternalIds(
            "test_metalake", "group-ext-1", Arrays.asList("user-ext-1", "user-ext-2"));

    SqlSource sqlSource =
        new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class);
    Map<String, Object> params = new HashMap<>();
    params.put("metalakeName", "test_metalake");
    params.put("groupExternalId", "group-ext-1");
    params.put("userExternalIds", Arrays.asList("user-ext-1", "user-ext-2"));

    BoundSql boundSql = sqlSource.getBoundSql(params);
    String normalizedSql = boundSql.getSql().replaceAll("\\s+", " ").trim();

    Assertions.assertTrue(
        normalizedSql.matches(".*\\buser_id\\s+IN\\s*\\(.*\\).*"),
        "Non-empty userExternalIds should generate SQL with user_id IN (...) clause");
    Assertions.assertTrue(normalizedSql.contains("u.external_id IN"));
  }

  @Test
  void testSoftDeleteGroupUsersNull() {
    ScimUserGroupRelBaseSQLProvider provider = new ScimUserGroupRelBaseSQLProvider();
    String script =
        provider.softDeleteMembersByGroupAndUserExternalIds("test_metalake", "group-ext-1", null);

    SqlSource sqlSource =
        new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class);
    Map<String, Object> params = new HashMap<>();
    params.put("metalakeName", "test_metalake");
    params.put("groupExternalId", "group-ext-1");
    params.put("userExternalIds", null);

    BoundSql boundSql = sqlSource.getBoundSql(params);
    String normalizedSql = boundSql.getSql().replaceAll("\\s+", " ").trim();

    Assertions.assertFalse(
        normalizedSql.matches(".*\\bIN\\s*\\(\\s*\\).*"),
        "Null userExternalIds should not generate invalid SQL IN (...) with no values");
    Assertions.assertTrue(
        normalizedSql.isEmpty(), "Null userExternalIds should not generate UPDATE SQL");
  }

  @Test
  void testInsertByExtIdsEmpty() {
    ScimUserGroupRelBaseSQLProvider provider = new ScimUserGroupRelBaseSQLProvider();
    String script =
        provider.insertMemberships(
            "test_metalake", "group-ext-1", Collections.emptyList(), "{}", 1L, 0L);

    SqlSource sqlSource =
        new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class);
    Map<String, Object> params = new HashMap<>();
    params.put("metalakeName", "test_metalake");
    params.put("groupExternalId", "group-ext-1");
    params.put("userExternalIds", Collections.emptyList());
    params.put("auditInfo", "{}");
    params.put("currentVersion", 1L);
    params.put("lastVersion", 0L);

    BoundSql boundSql = sqlSource.getBoundSql(params);
    String normalizedSql = boundSql.getSql().replaceAll("\\s+", " ").trim();

    Assertions.assertTrue(
        normalizedSql.isEmpty(), "Empty userExternalIds should not generate INSERT SQL");
  }

  @Test
  void testInsertByExtIdsNonEmpty() {
    ScimUserGroupRelBaseSQLProvider provider = new ScimUserGroupRelBaseSQLProvider();
    String script =
        provider.insertMemberships(
            "test_metalake", "group-ext-1", Arrays.asList("ext-1", "ext-2"), "{}", 1L, 0L);

    SqlSource sqlSource =
        new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class);
    Map<String, Object> params = new HashMap<>();
    params.put("metalakeName", "test_metalake");
    params.put("groupExternalId", "group-ext-1");
    params.put("userExternalIds", Arrays.asList("ext-1", "ext-2"));
    params.put("auditInfo", "{}");
    params.put("currentVersion", 1L);
    params.put("lastVersion", 0L);

    BoundSql boundSql = sqlSource.getBoundSql(params);
    String normalizedSql = boundSql.getSql().replaceAll("\\s+", " ").trim();

    Assertions.assertTrue(normalizedSql.contains("u.external_id IN"));
    Assertions.assertTrue(normalizedSql.contains("SELECT"));
    Assertions.assertTrue(normalizedSql.contains("ON DUPLICATE KEY UPDATE"));
  }

  @Test
  void testPgInsertByExtIdsOnConflict() {
    String script =
        new ScimUserGroupRelPostgreSQLProvider()
            .insertMemberships("test_metalake", "group-ext-1", List.of("ext-1"), "{}", 1L, 0L);

    Assertions.assertTrue(
        script.contains("ON CONFLICT (metalake_id, user_id, group_id, deleted_at)"));
    Assertions.assertTrue(script.contains("DO UPDATE SET"));
    Assertions.assertTrue(script.contains("EXCLUDED.metalake_id"));
  }

  @Test
  void testSelectMembersJoinsGroupMeta() {
    String sql =
        new ScimUserGroupRelBaseSQLProvider()
            .selectMembersByGroupExternalId("test_metalake", "group-ext-1");

    Assertions.assertTrue(sql.contains("JOIN group_meta"));
    Assertions.assertTrue(sql.contains("JOIN metalake_meta"));
    Assertions.assertTrue(sql.contains("u.external_id as externalId"));
    Assertions.assertTrue(sql.contains("u.user_name as userName"));
    Assertions.assertTrue(sql.contains("g.external_id = #{groupExternalId}"));
    Assertions.assertTrue(sql.contains("mm.metalake_name = #{metalakeName}"));
  }

  @Test
  void testSelectGroupNamesJoinsGroupMeta() {
    String sql =
        new ScimUserGroupRelBaseSQLProvider().selectGroupNamesByUsername("alice", "test_metalake");

    Assertions.assertTrue(sql.contains("JOIN group_meta"));
    Assertions.assertTrue(sql.contains("JOIN metalake_meta"));
    Assertions.assertTrue(sql.contains("g.group_name"));
    Assertions.assertTrue(sql.contains("mm.metalake_name = #{metalakeName}"));
    Assertions.assertFalse(sql.contains("u.enabled"));
  }

  @Test
  void testSoftDeleteUnavailableNotExists() {
    String sql = new ScimUserGroupRelBaseSQLProvider().softDeleteMembersByUnavailableMetalake();

    Assertions.assertTrue(sql.contains("NOT EXISTS"));
    Assertions.assertTrue(sql.contains("metalake_meta"));
    Assertions.assertTrue(sql.contains("m.metalake_id = r.metalake_id"));
    Assertions.assertTrue(sql.contains("m.deleted_at = 0"));
    Assertions.assertTrue(sql.contains("r.deleted_at = 0"));
  }
}
