/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.storage.relational.service;

import static org.apache.gravitino.Configs.ENTITY_RELATIONAL_JDBC_BACKEND_MAX_CONNECTIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.authorization.DirectoryGroup;
import com.datastrato.gravitino.authorization.mapper.DatastratoGroupMetaMapper;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests Directory Groups listing from idp_group_meta and scim_group_meta. */
public class TestDatastratoGroupMetaServiceDirectoryGroups {

  private static final String JDBC_URL =
      "jdbc:h2:mem:directorygroupstest;DB_CLOSE_DELAY=-1;MODE=MySQL";
  private static final String DRIVER = "org.h2.Driver";

  @BeforeAll
  static void setUp() throws Exception {
    Config config = mock(Config.class);
    when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_URL)).thenReturn(JDBC_URL);
    when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_DRIVER)).thenReturn(DRIVER);
    when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_USER)).thenReturn("sa");
    when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_PASSWORD)).thenReturn("");
    when(config.get(ENTITY_RELATIONAL_JDBC_BACKEND_MAX_CONNECTIONS)).thenReturn(10);
    when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_WAIT_MILLISECONDS)).thenReturn(1000L);

    try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE metalake_meta ("
              + "metalake_id BIGINT NOT NULL PRIMARY KEY,"
              + "metalake_name VARCHAR(128) NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE group_meta ("
              + "group_id BIGINT NOT NULL PRIMARY KEY,"
              + "group_name VARCHAR(128) NOT NULL,"
              + "metalake_id BIGINT NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE idp_group_meta ("
              + "group_id BIGINT NOT NULL PRIMARY KEY,"
              + "group_name VARCHAR(128) NOT NULL,"
              + "group_comment VARCHAR(1024) NOT NULL DEFAULT '',"
              + "audit_info CLOB NOT NULL,"
              + "current_version INT NOT NULL DEFAULT 1,"
              + "last_version INT NOT NULL DEFAULT 1,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE idp_user_meta ("
              + "user_id BIGINT NOT NULL PRIMARY KEY,"
              + "user_name VARCHAR(128) NOT NULL,"
              + "password_hash VARCHAR(1024) NOT NULL,"
              + "enabled BOOLEAN NOT NULL DEFAULT TRUE,"
              + "audit_info CLOB NOT NULL,"
              + "current_version INT NOT NULL DEFAULT 1,"
              + "last_version INT NOT NULL DEFAULT 1,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE idp_user_group_rel ("
              + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
              + "user_id BIGINT NOT NULL,"
              + "group_id BIGINT NOT NULL,"
              + "audit_info CLOB NOT NULL,"
              + "current_version INT NOT NULL DEFAULT 1,"
              + "last_version INT NOT NULL DEFAULT 1,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE scim_group_meta ("
              + "group_id BIGINT NOT NULL PRIMARY KEY,"
              + "group_name VARCHAR(128) NOT NULL,"
              + "audit_info CLOB NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE scim_user_group_rel ("
              + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
              + "user_id BIGINT NOT NULL,"
              + "group_id BIGINT NOT NULL,"
              + "audit_info CLOB NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE jit_user_meta ("
              + "user_id BIGINT NOT NULL PRIMARY KEY,"
              + "user_name VARCHAR(128) NOT NULL,"
              + "audit_info CLOB NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE jit_group_meta ("
              + "group_id BIGINT NOT NULL PRIMARY KEY,"
              + "group_name VARCHAR(128) NOT NULL,"
              + "audit_info CLOB NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE jit_user_group_rel ("
              + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
              + "user_id BIGINT NOT NULL,"
              + "group_id BIGINT NOT NULL,"
              + "audit_info CLOB NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
    }
    SqlSessionFactoryHelper.getInstance().init(config);
  }

  @AfterAll
  static void tearDown() {
    SqlSessionFactoryHelper.getInstance().close();
  }

  @BeforeEach
  void resetTables() throws Exception {
    try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
        Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM scim_user_group_rel");
      statement.execute("DELETE FROM jit_user_group_rel");
      statement.execute("DELETE FROM idp_user_group_rel");
      statement.execute("DELETE FROM scim_group_meta");
      statement.execute("DELETE FROM jit_user_meta");
      statement.execute("DELETE FROM jit_group_meta");
      statement.execute("DELETE FROM idp_group_meta");
      statement.execute("DELETE FROM idp_user_meta");
      statement.execute("DELETE FROM group_meta");
      statement.execute("DELETE FROM metalake_meta");

      statement.execute(
          "INSERT INTO metalake_meta (metalake_id, metalake_name, deleted_at)"
              + " VALUES (1, 'Acme', 0), (2, 'Contoso', 0), (3, 'Northwind', 0)");
      statement.execute(
          "INSERT INTO idp_user_meta (user_id, user_name, password_hash, enabled, audit_info,"
              + " deleted_at)"
              + " VALUES (100, 'sam.o', 'hash', TRUE, '{\"creator\":\"test\"}', 0),"
              + " (101, 'lee.p', 'hash', TRUE, '{\"creator\":\"test\"}', 0)");
      statement.execute(
          "INSERT INTO idp_group_meta (group_id, group_name, group_comment, audit_info,"
              + " deleted_at)"
              + " VALUES (200, 'governance', '', '{\"creator\":\"test\"}', 0),"
              + " (201, 'contractors', '', '{\"creator\":\"test\"}', 0)");
      statement.execute(
          "INSERT INTO idp_user_group_rel (user_id, group_id, audit_info, deleted_at)"
              + " VALUES (100, 200, '{\"creator\":\"test\"}', 0),"
              + " (101, 200, '{\"creator\":\"test\"}', 0)");
      statement.execute(
          "INSERT INTO scim_group_meta (group_id, group_name, audit_info, deleted_at)"
              + " VALUES (20, 'platform', '{\"creator\":\"test\"}', 0),"
              + " (21, 'governance', '{\"creator\":\"test\"}', 0)");
      statement.execute(
          "INSERT INTO scim_user_group_rel (user_id, group_id, audit_info, deleted_at)"
              + " VALUES (10, 20, '{\"creator\":\"test\"}', 0)");
      statement.execute(
          "INSERT INTO group_meta (group_id, group_name, metalake_id, deleted_at)"
              + " VALUES (1000, 'governance', 1, 0),"
              + " (1001, 'governance', 2, 0),"
              + " (1002, 'platform', 2, 0),"
              + " (1003, 'contractors', 1, 0),"
              + " (1004, 'analysts', 2, 0),"
              + " (1005, 'analysts', 3, 0)");
    }
  }

  /** Verifies Add-role group picker names are SCIM ∪ IdP ∪ JIT without duplicates. */
  @Test
  public void testListIdpGroupNamesUnionsIdentityStores() throws Exception {
    try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO jit_group_meta (group_id, group_name, audit_info, deleted_at)"
              + " VALUES (30, 'engineers', '{\"creator\":\"test\"}', 0),"
              + " (31, 'governance', '{\"creator\":\"test\"}', 0)");
    }

    List<String> names =
        SessionUtils.getWithoutCommit(
            DatastratoGroupMetaMapper.class, DatastratoGroupMetaMapper::listIdpGroupNames);

    assertEquals(List.of("contractors", "engineers", "governance", "platform"), names);
  }

  /** Verifies Local / Provisioned Directory Groups without cross-store merging. */
  @Test
  public void testListDirectoryGroups() {
    List<DirectoryGroup> groups = DatastratoGroupMetaService.getInstance().listDirectoryGroups();

    // contractors (Local), governance (Provisioned + Local), platform (Provisioned) — no merge.
    assertEquals(4, groups.size());

    DirectoryGroup contractors = groups.get(0);
    assertEquals("contractors", contractors.name());
    assertEquals(0, contractors.memberCount());
    assertEquals(IdentitySource.LOCAL, contractors.origin());
    assertEquals(List.of("Acme"), contractors.metalakes());

    // ORIGIN_CODE_PROVISIONED (0) sorts before LOCAL (1) for the same group name.
    DirectoryGroup governanceProvisioned = groups.get(1);
    assertEquals("governance", governanceProvisioned.name());
    assertEquals(0, governanceProvisioned.memberCount());
    assertEquals(IdentitySource.PROVISIONED, governanceProvisioned.origin());
    assertEquals(List.of("Acme", "Contoso"), governanceProvisioned.metalakes());

    DirectoryGroup governanceLocal = groups.get(2);
    assertEquals("governance", governanceLocal.name());
    assertEquals(2, governanceLocal.memberCount());
    assertEquals(IdentitySource.LOCAL, governanceLocal.origin());
    assertEquals(List.of("Acme", "Contoso"), governanceLocal.metalakes());

    DirectoryGroup platform = groups.get(3);
    assertEquals("platform", platform.name());
    assertEquals(1, platform.memberCount());
    assertEquals(IdentitySource.PROVISIONED, platform.origin());
    assertEquals(List.of("Contoso"), platform.metalakes());
  }

  @Test
  public void testAddDirectoryGroupWithMembers() throws Exception {
    DirectoryGroup created =
        DatastratoGroupMetaService.getInstance()
            .addDirectoryGroup("ops", "Operations", List.of("sam.o", "lee.p"));

    assertEquals("ops", created.name());
    assertEquals(2, created.memberCount());
    assertEquals(IdentitySource.LOCAL, created.origin());
    assertTrue(created.metalakes().isEmpty());

    try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
        Statement statement = connection.createStatement()) {
      try (var comment =
          statement.executeQuery(
              "SELECT group_comment, audit_info FROM idp_group_meta WHERE group_name = 'ops' AND"
                  + " deleted_at = 0")) {
        assertTrue(comment.next());
        assertEquals("Operations", comment.getString(1));
        String auditInfo = comment.getString(2);
        assertTrue(auditInfo.contains("createTime"));
        assertTrue(auditInfo.contains("creator"));
      }
      try (var members =
          statement.executeQuery(
              "SELECT u.user_name, r.audit_info FROM idp_user_group_rel r"
                  + " JOIN idp_user_meta u ON u.user_id = r.user_id"
                  + " JOIN idp_group_meta g ON g.group_id = r.group_id"
                  + " WHERE g.group_name = 'ops' AND r.deleted_at = 0"
                  + " ORDER BY u.user_name")) {
        assertTrue(members.next());
        assertEquals("lee.p", members.getString(1));
        assertTrue(members.getString(2).contains("createTime"));
        assertTrue(members.next());
        assertEquals("sam.o", members.getString(1));
        assertTrue(members.getString(2).contains("createTime"));
      }
    }
  }

  @Test
  public void testAddDirectoryGroupRejectsMissingMember() {
    assertThrows(
        org.apache.gravitino.exceptions.NotFoundException.class,
        () ->
            DatastratoGroupMetaService.getInstance()
                .addDirectoryGroup("ops", "", List.of("missing.user")));
  }

  @Test
  public void testAddDirectoryGroupRejectsDuplicate() {
    DatastratoGroupMetaService.getInstance().addDirectoryGroup("dup.group", null, List.of());
    assertThrows(
        org.apache.gravitino.exceptions.GroupAlreadyExistsException.class,
        () ->
            DatastratoGroupMetaService.getInstance()
                .addDirectoryGroup("dup.group", null, List.of()));
  }
}
