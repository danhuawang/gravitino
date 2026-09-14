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
import com.datastrato.gravitino.dto.authorization.AuthMeRoleDTO;
import com.datastrato.gravitino.dto.authorization.AuthMeSecurableObjectDTO;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.MetadataObject;
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
              + "metalake_comment VARCHAR(256),"
              + "properties CLOB,"
              + "audit_info CLOB,"
              + "schema_version VARCHAR(64),"
              + "current_version BIGINT NOT NULL DEFAULT 1,"
              + "last_version BIGINT NOT NULL DEFAULT 1,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE catalog_meta ("
              + "catalog_id BIGINT NOT NULL PRIMARY KEY,"
              + "catalog_name VARCHAR(128) NOT NULL,"
              + "metalake_id BIGINT NOT NULL,"
              + "type VARCHAR(64),"
              + "provider VARCHAR(64),"
              + "catalog_comment VARCHAR(256),"
              + "properties CLOB,"
              + "audit_info CLOB,"
              + "current_version BIGINT NOT NULL DEFAULT 1,"
              + "last_version BIGINT NOT NULL DEFAULT 1,"
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
              + "properties CLOB,"
              + "audit_info CLOB,"
              + "current_version BIGINT NOT NULL DEFAULT 1,"
              + "last_version BIGINT NOT NULL DEFAULT 1,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE user_role_rel ("
              + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
              + "user_id BIGINT NOT NULL,"
              + "role_id BIGINT NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE role_meta_securable_object ("
              + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
              + "role_id BIGINT NOT NULL,"
              + "metadata_object_id BIGINT NOT NULL,"
              + "type VARCHAR(64) NOT NULL,"
              + "privilege_names CLOB NOT NULL,"
              + "privilege_conditions CLOB NOT NULL,"
              + "current_version BIGINT NOT NULL DEFAULT 1,"
              + "last_version BIGINT NOT NULL DEFAULT 1,"
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
      statement.execute(
          "CREATE TABLE owner_meta ("
              + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
              + "metalake_id BIGINT NOT NULL,"
              + "owner_id BIGINT NOT NULL,"
              + "owner_type VARCHAR(64) NOT NULL,"
              + "metadata_object_id BIGINT NOT NULL,"
              + "metadata_object_type VARCHAR(64) NOT NULL,"
              + "audit_info CLOB,"
              + "current_version BIGINT NOT NULL DEFAULT 1,"
              + "last_version BIGINT NOT NULL DEFAULT 1,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0,"
              + "updated_at BIGINT NOT NULL DEFAULT 0,"
              + "updated_at_id BIGINT NOT NULL DEFAULT 0)");
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
      statement.execute("DELETE FROM owner_meta");
      statement.execute("DELETE FROM user_role_rel");
      statement.execute("DELETE FROM role_meta_securable_object");
      statement.execute("DELETE FROM role_meta");
      statement.execute("DELETE FROM scim_user_meta");
      statement.execute("DELETE FROM scim_group_meta");
      statement.execute("DELETE FROM jit_user_meta");
      statement.execute("DELETE FROM jit_group_meta");
      statement.execute("DELETE FROM idp_user_meta");
      statement.execute("DELETE FROM idp_group_meta");
      statement.execute("DELETE FROM user_meta");
      statement.execute("DELETE FROM catalog_meta");
      statement.execute("DELETE FROM metalake_meta");

      statement.execute(
          "INSERT INTO metalake_meta (metalake_id, metalake_name, deleted_at)"
              + " VALUES (1, 'Acme', 0), (2, 'Contoso', 0), (3, 'Northwind', 0)");
      statement.execute(
          "INSERT INTO catalog_meta (catalog_id, catalog_name, metalake_id, deleted_at)"
              + " VALUES (99, 'sales', 2, 0)");
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

  /** Verifies Local / Provisioned / JIT Directory Users without cross-store merging. */
  @Test
  public void testListDirectoryUsers() {
    List<DirectoryUser> users = DatastratoUserMetaService.getInstance().listDirectoryUsers();

    // dana.k (Provisioned), lee.p (Local), sam.o (Provisioned + Local) — no merge.
    assertEquals(4, users.size());

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

    // ORIGIN_CODE_PROVISIONED (0) sorts before LOCAL (1) for the same username.
    DirectoryUser samProvisioned = users.get(2);
    assertEquals("sam.o", samProvisioned.name());
    assertTrue(samProvisioned.enabled());
    assertEquals(IdentitySource.PROVISIONED, samProvisioned.origin());
    assertTrue(samProvisioned.groups().isEmpty());
    assertEquals(List.of("Contoso"), samProvisioned.metalakes());

    DirectoryUser samLocal = users.get(3);
    assertEquals("sam.o", samLocal.name());
    assertTrue(samLocal.enabled());
    assertEquals(IdentitySource.LOCAL, samLocal.origin());
    assertEquals(List.of("governance", "ops"), samLocal.groups());
    assertEquals(List.of("Contoso"), samLocal.metalakes());
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
    DirectoryUser lee =
        users.stream()
            .filter(u -> u.name().equals("lee.p") && u.origin() == IdentitySource.LOCAL)
            .findFirst()
            .orElseThrow();
    DirectoryUser samLocal =
        users.stream()
            .filter(u -> u.name().equals("sam.o") && u.origin() == IdentitySource.LOCAL)
            .findFirst()
            .orElseThrow();
    assertFalse(lee.enabled());
    assertFalse(samLocal.enabled());
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

  /** Verifies auth/me helpers return all origins and lightweight roles keyed by metalake. */
  @Test
  public void testListUserOriginsAndRolesByMetalake() throws Exception {
    try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO jit_user_meta (user_id, user_name, audit_info, deleted_at)"
              + " VALUES (30, 'sam.o', '{\"creator\":\"test\"}', 0)");
      statement.execute(
          "INSERT INTO role_meta (role_id, role_name, metalake_id, properties, audit_info,"
              + " deleted_at)"
              + " VALUES (500, 'admin', 2, '{}', '{\"creator\":\"test\"}', 0),"
              + " (501, 'viewer', 2, '{}', '{\"creator\":\"test\"}', 0),"
              + " (502, 'analyst', 1, '{}', '{\"creator\":\"test\"}', 0)");
      statement.execute(
          "INSERT INTO user_role_rel (user_id, role_id, deleted_at)"
              + " VALUES (1000, 500, 0), (1000, 501, 0)");
      statement.execute(
          "INSERT INTO role_meta_securable_object (role_id, metadata_object_id, type,"
              + " privilege_names, privilege_conditions, deleted_at)"
              + " VALUES (500, 2, 'METALAKE',"
              + " '[\"USE_CATALOG\",\"CREATE_TABLE\"]', '[\"ALLOW\",\"ALLOW\"]', 0),"
              + " (500, 99, 'CATALOG',"
              + " '[\"USE_CATALOG\",\"SELECT_TABLE\"]', '[\"ALLOW\",\"DENY\"]', 0),"
              + " (501, 2, 'METALAKE', '[\"USE_CATALOG\"]', '[\"ALLOW\"]', 0)");
    }

    List<IdentitySource> origins = DatastratoUserMetaService.getInstance().listUserOrigins("sam.o");
    assertEquals(
        List.of(IdentitySource.LOCAL, IdentitySource.PROVISIONED, IdentitySource.JIT), origins);

    Map<String, List<AuthMeRoleDTO>> roles =
        DatastratoRoleMetaService.getInstance().listUserRolesWithPrivileges("sam.o");
    assertEquals(1, roles.size());
    assertTrue(roles.containsKey("Contoso"));
    assertEquals(2, roles.get("Contoso").size());
    assertEquals("admin", roles.get("Contoso").get(0).getName());
    Map<MetadataObject.Type, AuthMeSecurableObjectDTO> adminObjects =
        roles.get("Contoso").get(0).getSecurableObjects().stream()
            .collect(Collectors.toMap(AuthMeSecurableObjectDTO::getType, Function.identity()));
    assertEquals(2, adminObjects.size());
    assertEquals("Contoso", adminObjects.get(MetadataObject.Type.METALAKE).getFullName());
    assertEquals(
        Set.of("USE_CATALOG", "CREATE_TABLE"),
        new HashSet<>(adminObjects.get(MetadataObject.Type.METALAKE).getPrivileges()));
    assertEquals("sales", adminObjects.get(MetadataObject.Type.CATALOG).getFullName());
    assertEquals(
        Set.of("USE_CATALOG"),
        new HashSet<>(adminObjects.get(MetadataObject.Type.CATALOG).getPrivileges()));
    assertEquals("viewer", roles.get("Contoso").get(1).getName());
    assertEquals(1, roles.get("Contoso").get(1).getSecurableObjects().size());
    assertEquals(
        Set.of("USE_CATALOG"),
        new HashSet<>(roles.get("Contoso").get(1).getSecurableObjects().get(0).getPrivileges()));

    List<IdentitySource> leeOrigins =
        DatastratoUserMetaService.getInstance().listUserOrigins("lee.p");
    assertEquals(List.of(IdentitySource.LOCAL), leeOrigins);

    Map<String, List<AuthMeRoleDTO>> leeRoles =
        DatastratoRoleMetaService.getInstance().listUserRolesWithPrivileges("lee.p");
    assertEquals(1, leeRoles.size());
    assertTrue(leeRoles.containsKey("Acme"));
    assertTrue(leeRoles.get("Acme").isEmpty());

    try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
        Statement statement = connection.createStatement()) {
      // sam.o's Contoso user_id is 1000; mark that user as Contoso metalake owner.
      statement.execute(
          "INSERT INTO owner_meta (metalake_id, owner_id, owner_type, metadata_object_id,"
              + " metadata_object_type, deleted_at)"
              + " VALUES (2, 1000, 'USER', 2, 'METALAKE', 0)");
    }
    assertEquals(
        List.of("Contoso"), DatastratoUserMetaService.getInstance().listOwnedMetalakes("sam.o"));
    assertTrue(DatastratoUserMetaService.getInstance().listOwnedMetalakes("lee.p").isEmpty());
  }
}
