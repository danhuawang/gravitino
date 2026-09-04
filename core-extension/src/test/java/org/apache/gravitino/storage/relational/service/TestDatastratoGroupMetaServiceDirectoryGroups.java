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
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
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
              + "current_version INT NOT NULL DEFAULT 1,"
              + "last_version INT NOT NULL DEFAULT 1,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE idp_user_meta ("
              + "user_id BIGINT NOT NULL PRIMARY KEY,"
              + "user_name VARCHAR(128) NOT NULL,"
              + "password_hash VARCHAR(1024) NOT NULL,"
              + "enabled BOOLEAN NOT NULL DEFAULT TRUE,"
              + "current_version INT NOT NULL DEFAULT 1,"
              + "last_version INT NOT NULL DEFAULT 1,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE idp_user_group_rel ("
              + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
              + "user_id BIGINT NOT NULL,"
              + "group_id BIGINT NOT NULL,"
              + "current_version INT NOT NULL DEFAULT 1,"
              + "last_version INT NOT NULL DEFAULT 1,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE scim_group_meta ("
              + "group_id BIGINT NOT NULL PRIMARY KEY,"
              + "group_name VARCHAR(128) NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE scim_user_group_rel ("
              + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
              + "user_id BIGINT NOT NULL,"
              + "group_id BIGINT NOT NULL,"
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
      statement.execute("DELETE FROM idp_user_group_rel");
      statement.execute("DELETE FROM scim_group_meta");
      statement.execute("DELETE FROM idp_group_meta");
      statement.execute("DELETE FROM idp_user_meta");
      statement.execute("DELETE FROM group_meta");
      statement.execute("DELETE FROM metalake_meta");

      statement.execute(
          "INSERT INTO metalake_meta (metalake_id, metalake_name, deleted_at)"
              + " VALUES (1, 'Acme', 0), (2, 'Contoso', 0), (3, 'Northwind', 0)");
      statement.execute(
          "INSERT INTO idp_user_meta (user_id, user_name, password_hash, enabled, deleted_at)"
              + " VALUES (100, 'sam.o', 'hash', TRUE, 0), (101, 'lee.p', 'hash', TRUE, 0)");
      statement.execute(
          "INSERT INTO idp_group_meta (group_id, group_name, group_comment, deleted_at)"
              + " VALUES (200, 'governance', '', 0), (201, 'contractors', '', 0)");
      statement.execute(
          "INSERT INTO idp_user_group_rel (user_id, group_id, deleted_at)"
              + " VALUES (100, 200, 0), (101, 200, 0)");
      statement.execute(
          "INSERT INTO scim_group_meta (group_id, group_name, deleted_at)"
              + " VALUES (20, 'platform', 0), (21, 'governance', 0)");
      statement.execute(
          "INSERT INTO scim_user_group_rel (user_id, group_id, deleted_at) VALUES (10, 20, 0)");
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

  /** Verifies Local / Provisioned / JIT, members, metalakes, and IdP precedence over SCIM. */
  @Test
  public void testListDirectoryGroups() {
    List<DirectoryGroup> groups = DatastratoGroupMetaService.getInstance().listDirectoryGroups();

    assertEquals(4, groups.size());

    DirectoryGroup analysts = groups.get(0);
    assertEquals("analysts", analysts.name());
    assertEquals(0, analysts.memberCount());
    assertEquals(IdentitySource.JIT, analysts.origin());
    assertEquals(List.of("Contoso", "Northwind"), analysts.metalakes());

    DirectoryGroup contractors = groups.get(1);
    assertEquals("contractors", contractors.name());
    assertEquals(0, contractors.memberCount());
    assertEquals(IdentitySource.LOCAL, contractors.origin());
    assertEquals(List.of("Acme"), contractors.metalakes());

    DirectoryGroup governance = groups.get(2);
    assertEquals("governance", governance.name());
    assertEquals(2, governance.memberCount());
    assertEquals(IdentitySource.LOCAL, governance.origin());
    assertEquals(List.of("Acme", "Contoso"), governance.metalakes());

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
              "SELECT group_comment FROM idp_group_meta WHERE group_name = 'ops' AND deleted_at ="
                  + " 0")) {
        assertTrue(comment.next());
        assertEquals("Operations", comment.getString(1));
      }
      try (var members =
          statement.executeQuery(
              "SELECT u.user_name FROM idp_user_group_rel r"
                  + " JOIN idp_user_meta u ON u.user_id = r.user_id"
                  + " JOIN idp_group_meta g ON g.group_id = r.group_id"
                  + " WHERE g.group_name = 'ops' AND r.deleted_at = 0"
                  + " ORDER BY u.user_name")) {
        assertTrue(members.next());
        assertEquals("lee.p", members.getString(1));
        assertTrue(members.next());
        assertEquals("sam.o", members.getString(1));
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
        org.apache.gravitino.exceptions.AlreadyExistsException.class,
        () ->
            DatastratoGroupMetaService.getInstance()
                .addDirectoryGroup("dup.group", null, List.of()));
  }
}
