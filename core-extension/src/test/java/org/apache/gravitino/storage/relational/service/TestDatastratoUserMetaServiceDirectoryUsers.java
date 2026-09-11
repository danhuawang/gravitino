/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.storage.relational.service;

import static org.apache.gravitino.Configs.ENTITY_RELATIONAL_JDBC_BACKEND_MAX_CONNECTIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.authorization.DirectoryUser;
import com.datastrato.gravitino.authorization.UserWithGroups;
import com.datastrato.gravitino.authorization.mapper.DatastratoUserMetaMapper;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
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

/** Tests Directory Users listing from idp_user_meta, scim_user_meta, and user_meta (JIT). */
public class TestDatastratoUserMetaServiceDirectoryUsers {

  private static final String JDBC_URL =
      "jdbc:h2:mem:directoryuserstest;DB_CLOSE_DELAY=-1;MODE=MySQL";
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
          "CREATE TABLE user_meta ("
              + "user_id BIGINT NOT NULL PRIMARY KEY,"
              + "user_name VARCHAR(128) NOT NULL,"
              + "metalake_id BIGINT NOT NULL,"
              + "external_id VARCHAR(256),"
              + "enabled BOOLEAN NOT NULL DEFAULT TRUE,"
              + "audit_info CLOB NOT NULL,"
              + "current_version BIGINT NOT NULL DEFAULT 1,"
              + "last_version BIGINT NOT NULL DEFAULT 1,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE group_meta ("
              + "group_id BIGINT NOT NULL PRIMARY KEY,"
              + "group_name VARCHAR(128) NOT NULL,"
              + "metalake_id BIGINT NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE role_meta ("
              + "role_id BIGINT NOT NULL PRIMARY KEY,"
              + "role_name VARCHAR(128) NOT NULL,"
              + "metalake_id BIGINT NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE user_role_rel ("
              + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
              + "user_id BIGINT NOT NULL,"
              + "role_id BIGINT NOT NULL,"
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
          "CREATE TABLE idp_group_meta ("
              + "group_id BIGINT NOT NULL PRIMARY KEY,"
              + "group_name VARCHAR(128) NOT NULL,"
              + "audit_info CLOB NOT NULL,"
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
          "CREATE TABLE scim_user_meta ("
              + "user_id BIGINT NOT NULL PRIMARY KEY,"
              + "user_name VARCHAR(128) NOT NULL,"
              + "external_id VARCHAR(256),"
              + "enabled BOOLEAN NOT NULL DEFAULT TRUE,"
              + "audit_info CLOB NOT NULL,"
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
      statement.execute("DELETE FROM scim_user_meta");
      statement.execute("DELETE FROM scim_group_meta");
      statement.execute("DELETE FROM jit_user_meta");
      statement.execute("DELETE FROM jit_group_meta");
      statement.execute("DELETE FROM idp_user_meta");
      statement.execute("DELETE FROM idp_group_meta");
      statement.execute("DELETE FROM user_meta");
      statement.execute("DELETE FROM metalake_meta");

      statement.execute(
          "INSERT INTO metalake_meta (metalake_id, metalake_name, deleted_at)"
              + " VALUES (1, 'Acme', 0), (2, 'Contoso', 0), (3, 'Northwind', 0)");
      statement.execute(
          "INSERT INTO idp_user_meta (user_id, user_name, password_hash, enabled, audit_info,"
              + " deleted_at)"
              + " VALUES (100, 'sam.o', 'hash', TRUE, '{\"creator\":\"test\"}', 0),"
              + " (101, 'lee.p', 'hash', FALSE, '{\"creator\":\"test\"}', 0)");
      statement.execute(
          "INSERT INTO idp_group_meta (group_id, group_name, audit_info, deleted_at)"
              + " VALUES (200, 'governance', '{\"creator\":\"test\"}', 0),"
              + " (201, 'ops', '{\"creator\":\"test\"}', 0)");
      statement.execute(
          "INSERT INTO idp_user_group_rel (user_id, group_id, audit_info, deleted_at)"
              + " VALUES (100, 200, '{\"creator\":\"test\"}', 0),"
              + " (100, 201, '{\"creator\":\"test\"}', 0)");
      statement.execute(
          "INSERT INTO scim_user_meta (user_id, user_name, external_id, enabled, audit_info,"
              + " deleted_at)"
              + " VALUES (10, 'dana.k', 'ext-dana', TRUE, '{\"creator\":\"test\"}', 0),"
              + " (11, 'sam.o', 'ext-sam', TRUE, '{\"creator\":\"test\"}', 0)");
      statement.execute(
          "INSERT INTO scim_group_meta (group_id, group_name, deleted_at)"
              + " VALUES (20, 'finance', 0)");
      statement.execute(
          "INSERT INTO scim_user_group_rel (user_id, group_id, audit_info, deleted_at)"
              + " VALUES (10, 20, '{\"creator\":\"test\"}', 0)");
      statement.execute(
          "INSERT INTO user_meta"
              + " (user_id, user_name, metalake_id, enabled, audit_info, deleted_at)"
              + " VALUES (1000, 'sam.o', 2, TRUE, '{}', 0),"
              + " (1001, 'dana.k', 1, TRUE, '{}', 0),"
              + " (1002, 'dana.k', 2, TRUE, '{}', 0),"
              + " (1003, 'lee.p', 1, FALSE, '{}', 0),"
              + " (1004, 'jordan.m', 2, TRUE, '{}', 0),"
              + " (1005, 'jordan.m', 3, FALSE, '{}', 0)");
    }
  }

  /** Verifies Add-role user picker names are SCIM ∪ IdP ∪ JIT without duplicates. */
  @Test
  public void testListIdpUserNamesUnionsIdentityStores() throws Exception {
    try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO jit_user_meta (user_id, user_name, audit_info, deleted_at)"
              + " VALUES (30, 'jwt.user', '{\"creator\":\"test\"}', 0),"
              + " (31, 'sam.o', '{\"creator\":\"test\"}', 0)");
    }

    List<String> names =
        SessionUtils.getWithoutCommit(
            DatastratoUserMetaMapper.class, DatastratoUserMetaMapper::listIdpUserNames);

    assertEquals(List.of("dana.k", "jwt.user", "lee.p", "sam.o"), names);
  }

  /** Verifies Local / Provisioned Directory Users (no metalake-only JIT). */
  @Test
  public void testListDirectoryUsers() {
    List<DirectoryUser> users = DatastratoUserMetaService.getInstance().listDirectoryUsers();

    assertEquals(3, users.size());

    DirectoryUser dana = users.get(0);
    assertEquals("dana.k", dana.name());
    assertTrue(dana.enabled());
    assertEquals(IdentitySource.PROVISIONED, dana.origin());
    assertEquals(List.of("finance"), dana.groups());
    assertEquals(List.of("Acme", "Contoso"), dana.metalakes());

    DirectoryUser lee = users.get(1);
    assertEquals("lee.p", lee.name());
    assertFalse(lee.enabled());
    assertEquals(IdentitySource.LOCAL, lee.origin());
    assertTrue(lee.groups().isEmpty());
    assertEquals(List.of("Acme"), lee.metalakes());

    DirectoryUser sam = users.get(2);
    assertEquals("sam.o", sam.name());
    assertTrue(sam.enabled());
    assertEquals(IdentitySource.LOCAL, sam.origin());
    assertEquals(List.of("governance", "ops"), sam.groups());
    assertEquals(List.of("Contoso"), sam.metalakes());
  }

  /** Verifies metalake Users list origin (Local/Provisioned; unknown is null). */
  @Test
  public void testListUsersWithGroupsOrigin() {
    List<UserWithGroups> contoso =
        DatastratoUserMetaService.getInstance().listUsersWithGroups("Contoso");
    assertEquals(3, contoso.size());

    UserWithGroups dana =
        contoso.stream().filter(u -> u.user().name().equals("dana.k")).findFirst().orElseThrow();
    assertEquals(IdentitySource.PROVISIONED, dana.origin());

    UserWithGroups jordan =
        contoso.stream().filter(u -> u.user().name().equals("jordan.m")).findFirst().orElseThrow();
    assertNull(jordan.origin());

    UserWithGroups sam =
        contoso.stream().filter(u -> u.user().name().equals("sam.o")).findFirst().orElseThrow();
    // Present in both scim_user_meta and idp_user_meta → metalake origin is SCIM/Provisioned.
    assertEquals(IdentitySource.PROVISIONED, sam.origin());

    List<UserWithGroups> acme = DatastratoUserMetaService.getInstance().listUsersWithGroups("Acme");
    UserWithGroups lee =
        acme.stream().filter(u -> u.user().name().equals("lee.p")).findFirst().orElseThrow();
    assertEquals(IdentitySource.LOCAL, lee.origin());
  }

  /** Verifies Local Directory Users can be batch-disabled via idp_user_meta. */
  @Test
  public void testBatchUpdateDirectoryUserEnabled() throws Exception {
    List<String> updated =
        DatastratoUserMetaService.getInstance()
            .batchUpdateDirectoryUserEnabled(
                List.of("sam.o", "lee.p"),
                List.of(IdentitySource.LOCAL, IdentitySource.LOCAL),
                false);

    assertEquals(List.of("sam.o", "lee.p"), updated);

    try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "SELECT user_name, enabled FROM idp_user_meta"
                    + " WHERE user_name IN ('sam.o', 'lee.p') ORDER BY user_name")) {
      assertTrue(resultSet.next());
      assertEquals("lee.p", resultSet.getString(1));
      assertFalse(resultSet.getBoolean(2));
      assertTrue(resultSet.next());
      assertEquals("sam.o", resultSet.getString(1));
      assertFalse(resultSet.getBoolean(2));
    }

    List<DirectoryUser> users = DatastratoUserMetaService.getInstance().listDirectoryUsers();
    assertFalse(users.get(1).enabled());
    assertFalse(users.get(2).enabled());
  }

  @Test
  public void testBatchUpdateDirectoryUserEnabledRejectsNonLocalOrigin() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DatastratoUserMetaService.getInstance()
                    .batchUpdateDirectoryUserEnabled(
                        List.of("dana.k"), List.of(IdentitySource.PROVISIONED), false));
    assertTrue(ex.getMessage().contains("only Local origin is supported"));
  }

  @Test
  public void testBatchUpdateDirectoryUserEnabledRejectsMissingIdpUser() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DatastratoUserMetaService.getInstance()
                    .batchUpdateDirectoryUserEnabled(
                        List.of("missing.user"), List.of(IdentitySource.LOCAL), true));
    assertTrue(ex.getMessage().contains("idp_user_meta"));
    assertTrue(ex.getMessage().contains("missing.user"));
  }

  @Test
  public void testAddDirectoryUserWithGroups() throws Exception {
    DirectoryUser created =
        DatastratoUserMetaService.getInstance()
            .addDirectoryUser("jordan.m", "ChangeMe-2026!", List.of("governance", "ops"));

    assertEquals("jordan.m", created.name());
    assertTrue(created.enabled());
    assertEquals(IdentitySource.LOCAL, created.origin());
    assertEquals(List.of("governance", "ops"), created.groups());
    assertTrue(created.metalakes().isEmpty());

    try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
        Statement statement = connection.createStatement()) {
      try (ResultSet user =
          statement.executeQuery(
              "SELECT enabled, password_hash, audit_info FROM idp_user_meta WHERE user_name ="
                  + " 'jordan.m'")) {
        assertTrue(user.next());
        assertTrue(user.getBoolean(1));
        assertTrue(user.getString(2).length() > 10);
        String auditInfo = user.getString(3);
        assertTrue(auditInfo.contains("createTime"));
        assertTrue(auditInfo.contains("creator"));
      }
      try (ResultSet groups =
          statement.executeQuery(
              "SELECT g.group_name, r.audit_info FROM idp_user_group_rel r"
                  + " JOIN idp_user_meta u ON u.user_id = r.user_id"
                  + " JOIN idp_group_meta g ON g.group_id = r.group_id"
                  + " WHERE u.user_name = 'jordan.m' AND r.deleted_at = 0"
                  + " ORDER BY g.group_name")) {
        assertTrue(groups.next());
        assertEquals("governance", groups.getString(1));
        assertTrue(groups.getString(2).contains("createTime"));
        assertTrue(groups.next());
        assertEquals("ops", groups.getString(1));
        assertTrue(groups.getString(2).contains("createTime"));
      }
    }
  }

  @Test
  public void testAddDirectoryUserRejectsMissingGroup() {
    assertThrows(
        org.apache.gravitino.exceptions.NotFoundException.class,
        () ->
            DatastratoUserMetaService.getInstance()
                .addDirectoryUser("new.user", "ChangeMe-2026!", List.of("missing-group")));
  }

  @Test
  public void testAddDirectoryUserRejectsDuplicate() {
    DatastratoUserMetaService.getInstance()
        .addDirectoryUser("dup.user", "ChangeMe-2026!", List.of());
    assertThrows(
        org.apache.gravitino.exceptions.UserAlreadyExistsException.class,
        () ->
            DatastratoUserMetaService.getInstance()
                .addDirectoryUser("dup.user", "ChangeMe-2026!", List.of()));
  }
}
