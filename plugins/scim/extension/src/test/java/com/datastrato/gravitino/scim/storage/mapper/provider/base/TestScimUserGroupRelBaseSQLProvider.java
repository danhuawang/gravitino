/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider.base;

import com.datastrato.gravitino.scim.storage.mapper.ScimGroupMetaMapper;
import com.datastrato.gravitino.scim.storage.mapper.ScimUserGroupRelMapper;
import com.datastrato.gravitino.scim.storage.mapper.ScimUserMetaMapper;
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

  private static final long GROUP_ID = 200L;

  @Test
  void testSoftDeleteGroupUsersEmpty() {
    ScimUserGroupRelBaseSQLProvider provider = new ScimUserGroupRelBaseSQLProvider();
    String script = provider.softDeleteMembersByGroupAndUserIds(GROUP_ID, Collections.emptyList());

    SqlSource sqlSource =
        new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class);
    Map<String, Object> params = new HashMap<>();
    params.put("groupId", GROUP_ID);
    params.put("userIds", Collections.emptyList());

    BoundSql boundSql = sqlSource.getBoundSql(params);
    String normalizedSql = boundSql.getSql().replaceAll("\\s+", " ").trim();

    Assertions.assertFalse(
        normalizedSql.matches(".*\\bIN\\s*\\(\\s*\\).*"),
        "Empty userIds should not generate invalid SQL IN (...) with no values");
    Assertions.assertTrue(normalizedSql.isEmpty(), "Empty userIds should not generate UPDATE SQL");
  }

  @Test
  void testSoftDeleteGroupUsersNonEmpty() {
    ScimUserGroupRelBaseSQLProvider provider = new ScimUserGroupRelBaseSQLProvider();
    String script =
        provider.softDeleteMembersByGroupAndUserIds(GROUP_ID, Arrays.asList(100L, 101L));

    Assertions.assertTrue(script.contains("user_id IN"));
    Assertions.assertTrue(script.contains("group_id = #{groupId}"));
    Assertions.assertFalse(script.contains("external_id"));
  }

  @Test
  void testSoftDeleteGroupUsersNull() {
    ScimUserGroupRelBaseSQLProvider provider = new ScimUserGroupRelBaseSQLProvider();
    String script = provider.softDeleteMembersByGroupAndUserIds(GROUP_ID, null);

    SqlSource sqlSource =
        new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class);
    Map<String, Object> params = new HashMap<>();
    params.put("groupId", GROUP_ID);
    params.put("userIds", null);

    BoundSql boundSql = sqlSource.getBoundSql(params);
    String normalizedSql = boundSql.getSql().replaceAll("\\s+", " ").trim();

    Assertions.assertFalse(
        normalizedSql.matches(".*\\bIN\\s*\\(\\s*\\).*"),
        "Null userIds should not generate invalid SQL IN (...) with no values");
    Assertions.assertTrue(normalizedSql.isEmpty(), "Null userIds should not generate UPDATE SQL");
  }

  @Test
  void testInsertByUserIdsEmpty() {
    ScimUserGroupRelBaseSQLProvider provider = new ScimUserGroupRelBaseSQLProvider();
    String script = provider.insertMemberships(GROUP_ID, Collections.emptyList(), 1L, 0L);

    SqlSource sqlSource =
        new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class);
    Map<String, Object> params = new HashMap<>();
    params.put("groupId", GROUP_ID);
    params.put("userIds", Collections.emptyList());
    params.put("currentVersion", 1L);
    params.put("lastVersion", 0L);

    BoundSql boundSql = sqlSource.getBoundSql(params);
    String normalizedSql = boundSql.getSql().replaceAll("\\s+", " ").trim();

    Assertions.assertTrue(normalizedSql.isEmpty(), "Empty userIds should not generate INSERT SQL");
  }

  @Test
  void testInsertByUserIdsNonEmpty() {
    ScimUserGroupRelBaseSQLProvider provider = new ScimUserGroupRelBaseSQLProvider();
    String script = provider.insertMemberships(GROUP_ID, Arrays.asList(100L, 101L), 1L, 0L);

    Assertions.assertTrue(script.contains("u.user_id IN"));
    Assertions.assertTrue(script.contains("#{groupId}"));
    Assertions.assertTrue(script.contains("SELECT"));
    Assertions.assertTrue(script.contains("ON DUPLICATE KEY UPDATE"));
    Assertions.assertFalse(script.contains("external_id"));
  }

  @Test
  void testPgInsertByUserIdsOnConflict() {
    String script =
        new ScimUserGroupRelPostgreSQLProvider().insertMemberships(GROUP_ID, List.of(100L), 1L, 0L);

    Assertions.assertTrue(script.contains("ON CONFLICT (user_id, group_id, deleted_at)"));
    Assertions.assertTrue(script.contains("DO UPDATE SET"));
    Assertions.assertTrue(script.contains("EXCLUDED.user_id"));
    Assertions.assertFalse(script.contains("external_id"));
  }

  @Test
  void testSelectMembersJoinsUserMeta() {
    String sql = new ScimUserGroupRelBaseSQLProvider().selectMembersByGroupId(GROUP_ID);

    Assertions.assertTrue(sql.contains(ScimUserMetaMapper.TABLE_NAME));
    Assertions.assertTrue(sql.contains(ScimUserGroupRelMapper.SCIM_USER_GROUP_REL_TABLE_NAME));
    Assertions.assertTrue(sql.contains("r.group_id as groupId"));
    Assertions.assertTrue(sql.contains("u.user_id as userId"));
    Assertions.assertTrue(sql.contains("u.user_name as userName"));
    Assertions.assertTrue(sql.contains("r.group_id = #{groupId}"));
    Assertions.assertFalse(sql.contains("metalake"));
  }

  @Test
  void testSelectMembersByGroupIds() {
    String script =
        new ScimUserGroupRelBaseSQLProvider().selectMembersByGroupIds(Arrays.asList(200L, 201L));

    Assertions.assertTrue(script.contains("r.group_id IN"));
    Assertions.assertTrue(script.contains("r.group_id as groupId"));
    Assertions.assertTrue(script.contains(ScimUserMetaMapper.TABLE_NAME));
  }

  @Test
  void testSelectMembersByGroupIdsEmpty() {
    ScimUserGroupRelBaseSQLProvider provider = new ScimUserGroupRelBaseSQLProvider();
    String script = provider.selectMembersByGroupIds(Collections.emptyList());

    SqlSource sqlSource =
        new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class);
    Map<String, Object> params = new HashMap<>();
    params.put("groupIds", Collections.emptyList());

    BoundSql boundSql = sqlSource.getBoundSql(params);
    String normalizedSql = boundSql.getSql().replaceAll("\\s+", " ").trim();
    Assertions.assertTrue(normalizedSql.isEmpty());
  }

  @Test
  void testSelectGroupNamesJoinsGroupMeta() {
    String sql = new ScimUserGroupRelBaseSQLProvider().selectGroupNamesByUsername("alice");

    Assertions.assertTrue(sql.contains(ScimGroupMetaMapper.TABLE_NAME));
    Assertions.assertTrue(sql.contains(ScimUserMetaMapper.TABLE_NAME));
    Assertions.assertTrue(sql.contains("g.group_name"));
    Assertions.assertTrue(sql.contains("u.enabled = 1"));
    Assertions.assertFalse(sql.contains("metalake"));
  }

  @Test
  void testSoftDeleteOrphanMemberships() {
    String sql = new ScimUserGroupRelBaseSQLProvider().softDeleteOrphanMemberships();

    Assertions.assertTrue(sql.contains("NOT EXISTS"));
    Assertions.assertTrue(sql.contains(ScimUserMetaMapper.TABLE_NAME));
    Assertions.assertTrue(sql.contains(ScimGroupMetaMapper.TABLE_NAME));
    Assertions.assertTrue(sql.contains("r.deleted_at = 0"));
  }

  @Test
  void testUpdateMemberUserIdJoinsUserMeta() {
    String sql =
        new ScimUserGroupRelBaseSQLProvider().updateMemberUserId(GROUP_ID, 100L, 101L, 1L, 0L);

    Assertions.assertTrue(sql.contains(ScimUserMetaMapper.TABLE_NAME));
    Assertions.assertTrue(sql.contains("u_new.user_id = #{newUserId}"));
    Assertions.assertTrue(sql.contains("r.user_id = #{oldUserId}"));
    Assertions.assertTrue(sql.contains("NOT EXISTS"));
  }

  @Test
  void testPgDeleteByLegacyTimelineUsesSubquery() {
    String sql = new ScimUserGroupRelPostgreSQLProvider().deleteByLegacyTimeline(Long.MAX_VALUE, 1);

    Assertions.assertTrue(sql.contains("WHERE id IN (SELECT id FROM"));
    Assertions.assertTrue(sql.contains(ScimUserGroupRelMapper.SCIM_USER_GROUP_REL_TABLE_NAME));
  }
}
