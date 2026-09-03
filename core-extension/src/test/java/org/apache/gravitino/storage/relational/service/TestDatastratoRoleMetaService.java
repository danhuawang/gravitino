/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.storage.relational.service;

import static org.apache.gravitino.Configs.ENTITY_RELATIONAL_JDBC_BACKEND_MAX_CONNECTIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.authorization.RoleAssignment;
import com.datastrato.gravitino.authorization.RoleGroupAssignment;
import com.datastrato.gravitino.authorization.RoleUserAssignment;
import com.datastrato.gravitino.authorization.po.RoleAssignmentPO;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.Audit;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.exceptions.NoSuchGroupException;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.exceptions.NoSuchRoleException;
import org.apache.gravitino.exceptions.NoSuchUserException;
import org.apache.gravitino.json.JsonUtils;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.GroupEntity;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.storage.relational.po.RolePO;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests principal role-assignment loading and enrichment. */
public class TestDatastratoRoleMetaService {

  private static final String JDBC_URL =
      "jdbc:h2:mem:roleassignmenttest;DB_CLOSE_DELAY=-1;MODE=MySQL";
  private static final String DRIVER = "org.h2.Driver";
  private static final Instant ASSIGNED_AT = Instant.parse("2026-08-27T01:02:03Z");

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
              + "audit_info CLOB NOT NULL,"
              + "current_version BIGINT NOT NULL,"
              + "last_version BIGINT NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE group_meta ("
              + "group_id BIGINT NOT NULL PRIMARY KEY,"
              + "group_name VARCHAR(128) NOT NULL,"
              + "metalake_id BIGINT NOT NULL,"
              + "audit_info CLOB NOT NULL,"
              + "current_version BIGINT NOT NULL,"
              + "last_version BIGINT NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE role_meta ("
              + "role_id BIGINT NOT NULL PRIMARY KEY,"
              + "role_name VARCHAR(128) NOT NULL,"
              + "metalake_id BIGINT NOT NULL,"
              + "properties CLOB,"
              + "audit_info CLOB NOT NULL,"
              + "current_version BIGINT NOT NULL,"
              + "last_version BIGINT NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE user_role_rel ("
              + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
              + "user_id BIGINT NOT NULL,"
              + "role_id BIGINT NOT NULL,"
              + "audit_info CLOB NOT NULL,"
              + "current_version BIGINT NOT NULL DEFAULT 1,"
              + "last_version BIGINT NOT NULL DEFAULT 1,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE group_role_rel ("
              + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
              + "group_id BIGINT NOT NULL,"
              + "role_id BIGINT NOT NULL,"
              + "audit_info CLOB NOT NULL,"
              + "current_version BIGINT NOT NULL DEFAULT 1,"
              + "last_version BIGINT NOT NULL DEFAULT 1,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE role_meta_securable_object ("
              + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
              + "role_id BIGINT NOT NULL,"
              + "metadata_object_id BIGINT NOT NULL,"
              + "type VARCHAR(128) NOT NULL,"
              + "privilege_names CLOB NOT NULL,"
              + "privilege_conditions CLOB NOT NULL,"
              + "current_version BIGINT NOT NULL,"
              + "last_version BIGINT NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE idp_user_meta ("
              + "user_id BIGINT NOT NULL PRIMARY KEY,"
              + "user_name VARCHAR(128) NOT NULL,"
              + "enabled BOOLEAN NOT NULL DEFAULT TRUE,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE idp_group_meta ("
              + "group_id BIGINT NOT NULL PRIMARY KEY,"
              + "group_name VARCHAR(128) NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE idp_user_group_rel ("
              + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
              + "user_id BIGINT NOT NULL,"
              + "group_id BIGINT NOT NULL,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE scim_user_meta ("
              + "user_id BIGINT NOT NULL PRIMARY KEY,"
              + "user_name VARCHAR(128) NOT NULL,"
              + "external_id VARCHAR(256),"
              + "enabled BOOLEAN NOT NULL DEFAULT TRUE,"
              + "current_version INT NOT NULL DEFAULT 1,"
              + "last_version INT NOT NULL DEFAULT 1,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE scim_group_meta ("
              + "group_id BIGINT NOT NULL PRIMARY KEY,"
              + "group_name VARCHAR(128) NOT NULL,"
              + "group_comment VARCHAR(1024) DEFAULT '',"
              + "external_id VARCHAR(256),"
              + "current_version INT NOT NULL DEFAULT 1,"
              + "last_version INT NOT NULL DEFAULT 1,"
              + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      statement.execute(
          "CREATE TABLE scim_user_group_rel ("
              + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
              + "user_id BIGINT NOT NULL,"
              + "group_id BIGINT NOT NULL,"
              + "current_version INT NOT NULL DEFAULT 1,"
              + "last_version INT NOT NULL DEFAULT 1,"
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
    String entityAudit = JsonUtils.anyFieldMapper().writeValueAsString(buildRoleAudit());
    try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
        Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM role_meta_securable_object");
      statement.execute("DELETE FROM user_role_rel");
      statement.execute("DELETE FROM group_role_rel");
      statement.execute("DELETE FROM idp_user_group_rel");
      statement.execute("DELETE FROM scim_user_group_rel");
      statement.execute("DELETE FROM scim_user_meta");
      statement.execute("DELETE FROM scim_group_meta");
      statement.execute("DELETE FROM idp_user_meta");
      statement.execute("DELETE FROM idp_group_meta");
      statement.execute("DELETE FROM role_meta");
      statement.execute("DELETE FROM user_meta");
      statement.execute("DELETE FROM group_meta");
      statement.execute("DELETE FROM metalake_meta");

      statement.execute(
          "INSERT INTO metalake_meta (metalake_id, metalake_name, deleted_at)"
              + " VALUES (1, 'metalake1', 0)");
      try (PreparedStatement userStatement =
              connection.prepareStatement(
                  "INSERT INTO user_meta"
                      + " (user_id, user_name, metalake_id, audit_info,"
                      + " current_version, last_version, deleted_at)"
                      + " VALUES (?, ?, 1, ?, 1, 1, 0)");
          PreparedStatement groupStatement =
              connection.prepareStatement(
                  "INSERT INTO group_meta"
                      + " (group_id, group_name, metalake_id, audit_info,"
                      + " current_version, last_version, deleted_at)"
                      + " VALUES (?, ?, 1, ?, 1, 1, 0)")) {
        userStatement.setLong(1, 10L);
        userStatement.setString(2, "user1");
        userStatement.setString(3, entityAudit);
        userStatement.addBatch();
        userStatement.setLong(1, 11L);
        userStatement.setString(2, "userNoRoles");
        userStatement.setString(3, entityAudit);
        userStatement.addBatch();
        userStatement.setLong(1, 12L);
        userStatement.setString(2, "userProvisioned");
        userStatement.setString(3, entityAudit);
        userStatement.addBatch();
        userStatement.executeBatch();

        groupStatement.setLong(1, 20L);
        groupStatement.setString(2, "group1");
        groupStatement.setString(3, entityAudit);
        groupStatement.addBatch();
        groupStatement.setLong(1, 21L);
        groupStatement.setString(2, "groupNoRoles");
        groupStatement.setString(3, entityAudit);
        groupStatement.addBatch();
        groupStatement.setLong(1, 22L);
        groupStatement.setString(2, "groupProvisioned");
        groupStatement.setString(3, entityAudit);
        groupStatement.addBatch();
        groupStatement.executeBatch();
      }
      statement.execute(
          "INSERT INTO idp_user_meta (user_id, user_name, enabled, deleted_at)"
              + " VALUES (1000, 'user1', TRUE, 0)");
      statement.execute(
          "INSERT INTO idp_group_meta (group_id, group_name, deleted_at)"
              + " VALUES (2000, 'group1', 0)");
      statement.execute(
          "INSERT INTO idp_user_group_rel (user_id, group_id, deleted_at)"
              + " VALUES (1000, 2000, 0)");
      statement.execute(
          "INSERT INTO scim_user_meta (user_id, user_name, external_id, enabled, deleted_at)"
              + " VALUES (12, 'userProvisioned', 'provisioned-user-id', FALSE, 0)");
      statement.execute(
          "INSERT INTO scim_group_meta (group_id, group_name, external_id, deleted_at)"
              + " VALUES (22, 'groupProvisioned', 'provisioned-group-id', 0)");
      statement.execute(
          "INSERT INTO scim_user_group_rel"
              + " (user_id, group_id, deleted_at)"
              + " VALUES (12, 22, 0)");
    }

    String roleAudit = JsonUtils.anyFieldMapper().writeValueAsString(buildRoleAudit());
    String assignmentAudit = JsonUtils.anyFieldMapper().writeValueAsString(buildAssignmentAudit());
    try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
        PreparedStatement roleStatement =
            connection.prepareStatement(
                "INSERT INTO role_meta"
                    + " (role_id, role_name, metalake_id, properties, audit_info,"
                    + " current_version, last_version, deleted_at)"
                    + " VALUES (?, ?, 1, '{}', ?, 1, 1, 0)");
        PreparedStatement userRelationStatement =
            connection.prepareStatement(
                "INSERT INTO user_role_rel (user_id, role_id, audit_info, deleted_at)"
                    + " VALUES (?, 100, ?, 0)");
        PreparedStatement groupRelationStatement =
            connection.prepareStatement(
                "INSERT INTO group_role_rel (group_id, role_id, audit_info, deleted_at)"
                    + " VALUES (?, 100, ?, 0)")) {
      roleStatement.setLong(1, 100L);
      roleStatement.setString(2, "role1");
      roleStatement.setString(3, roleAudit);
      roleStatement.executeUpdate();
      roleStatement.setLong(1, 101L);
      roleStatement.setString(2, "roleNoAssignments");
      roleStatement.setString(3, roleAudit);
      roleStatement.executeUpdate();
      userRelationStatement.setLong(1, 10L);
      userRelationStatement.setString(2, assignmentAudit);
      userRelationStatement.executeUpdate();
      userRelationStatement.setLong(1, 12L);
      userRelationStatement.setString(2, assignmentAudit);
      userRelationStatement.executeUpdate();
      groupRelationStatement.setLong(1, 20L);
      groupRelationStatement.setString(2, assignmentAudit);
      groupRelationStatement.executeUpdate();
      groupRelationStatement.setLong(1, 22L);
      groupRelationStatement.setString(2, assignmentAudit);
      groupRelationStatement.executeUpdate();
    }
  }

  /** Tests user assignments loaded by principal name in one query. */
  @Test
  public void testListUserRoleAssignments() {
    List<RoleAssignment> assignments =
        DatastratoRoleMetaService.getInstance().listUserRoleAssignments("metalake1", "user1");

    assertEquals(1, assignments.size());
    assertAssignment(assignments.get(0));
  }

  /** Tests group assignments loaded by principal name in one query. */
  @Test
  public void testListGroupRoleAssignments() {
    List<RoleAssignment> assignments =
        DatastratoRoleMetaService.getInstance().listGroupRoleAssignments("metalake1", "group1");

    assertEquals(1, assignments.size());
    assertAssignment(assignments.get(0));
  }

  /** Tests users listed by role include assignment audit and IdP-derived origin. */
  @Test
  public void testListUserAssignmentsByRole() {
    DatastratoRoleMetaService service = DatastratoRoleMetaService.getInstance();

    List<RoleUserAssignment> assignments = service.listUserAssignmentsByRole("metalake1", "role1");

    assertEquals(2, assignments.size());
    assertEquals("user1", assignments.get(0).user().name());
    assertTrue(assignments.get(0).inBuiltInIdp());
    assertAssignmentAudit(assignments.get(0).assignmentAudit());
    assertEquals("userProvisioned", assignments.get(1).user().name());
    assertFalse(assignments.get(1).inBuiltInIdp());
    assertAssignmentAudit(assignments.get(1).assignmentAudit());

    assertTrue(service.listUserAssignmentsByRole("metalake1", "roleNoAssignments").isEmpty());
    assertThrows(
        NoSuchRoleException.class,
        () -> service.listUserAssignmentsByRole("metalake1", "missingRole"));
    assertThrows(
        NoSuchMetalakeException.class,
        () -> service.listUserAssignmentsByRole("missingMetalake", "role1"));
  }

  /** Tests groups listed by role include assignment audit and source-aware user counts. */
  @Test
  public void testListGroupAssignmentsByRole() {
    DatastratoRoleMetaService service = DatastratoRoleMetaService.getInstance();

    List<RoleGroupAssignment> assignments =
        service.listGroupAssignmentsByRole("metalake1", "role1");

    assertEquals(2, assignments.size());
    assertEquals("group1", assignments.get(0).group().name());
    assertEquals(1, assignments.get(0).userCount());
    assertAssignmentAudit(assignments.get(0).assignmentAudit());
    assertEquals("groupProvisioned", assignments.get(1).group().name());
    assertEquals(1, assignments.get(1).userCount());
    assertAssignmentAudit(assignments.get(1).assignmentAudit());

    assertTrue(service.listGroupAssignmentsByRole("metalake1", "roleNoAssignments").isEmpty());
    assertThrows(
        NoSuchRoleException.class,
        () -> service.listGroupAssignmentsByRole("metalake1", "missingRole"));
    assertThrows(
        NoSuchMetalakeException.class,
        () -> service.listGroupAssignmentsByRole("missingMetalake", "role1"));
  }

  /**
   * Tests assigning every requested role to every requested principal atomically and idempotently.
   */
  @Test
  public void testBatchAssignRolesToPrincipals() throws Exception {
    AuditInfo assignmentAudit = buildAssignmentAudit();
    RoleEntity role1 =
        RoleEntity.builder()
            .withNamespace(AuthorizationUtils.ofRoleNamespace("metalake1"))
            .withId(100L)
            .withName("role1")
            .withProperties(Collections.emptyMap())
            .withSecurableObjects(Collections.emptyList())
            .withAuditInfo(buildRoleAudit())
            .build();
    RoleEntity role2 =
        RoleEntity.builder()
            .withNamespace(AuthorizationUtils.ofRoleNamespace("metalake1"))
            .withId(101L)
            .withName("roleNoAssignments")
            .withProperties(Collections.emptyMap())
            .withSecurableObjects(Collections.emptyList())
            .withAuditInfo(buildRoleAudit())
            .build();
    UserEntity user =
        UserEntity.builder()
            .withNamespace(AuthorizationUtils.ofUserNamespace("metalake1"))
            .withId(11L)
            .withName("userNoRoles")
            .withRoleNames(List.of("role1", "roleNoAssignments"))
            .withRoleIds(List.of(100L, 101L))
            .withAuditInfo(assignmentAudit)
            .build();
    GroupEntity group =
        GroupEntity.builder()
            .withNamespace(AuthorizationUtils.ofGroupNamespace("metalake1"))
            .withId(21L)
            .withName("groupNoRoles")
            .withRoleNames(List.of("role1", "roleNoAssignments"))
            .withRoleIds(List.of(100L, 101L))
            .withAuditInfo(assignmentAudit)
            .build();

    DatastratoRoleMetaService service = DatastratoRoleMetaService.getInstance();
    service.batchAssignRolesToPrincipals(List.of(role1, role2), List.of(user), List.of(group));
    service.batchAssignRolesToPrincipals(List.of(role1, role2), List.of(user), List.of(group));

    try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
        Statement statement = connection.createStatement()) {
      assertRelationCount(statement, "user_role_rel", "user_id", 11L, 2);
      assertRelationCount(statement, "group_role_rel", "group_id", 21L, 2);
    }
  }

  /** Tests missing metalake, missing principal, and unassigned principal semantics. */
  @Test
  public void testPrincipalExistenceSemantics() {
    DatastratoRoleMetaService service = DatastratoRoleMetaService.getInstance();

    assertTrue(service.listUserRoleAssignments("metalake1", "userNoRoles").isEmpty());
    assertTrue(service.listGroupRoleAssignments("metalake1", "groupNoRoles").isEmpty());
    assertThrows(
        NoSuchUserException.class,
        () -> service.listUserRoleAssignments("metalake1", "missingUser"));
    assertThrows(
        NoSuchGroupException.class,
        () -> service.listGroupRoleAssignments("metalake1", "missingGroup"));
    assertThrows(
        NoSuchMetalakeException.class,
        () -> service.listUserRoleAssignments("missingMetalake", "user1"));
  }

  /** Tests empty conversion, securable-object enrichment, and assignment-audit parsing. */
  @Test
  public void testToRoleAssignments() throws JsonProcessingException {
    assertTrue(
        DatastratoRoleMetaService.toRoleAssignments(
                "metalake1", Collections.emptyList(), Collections.emptyMap())
            .isEmpty());

    RoleAssignmentPO assignmentPO = mock(RoleAssignmentPO.class);
    when(assignmentPO.getRoleId()).thenReturn(100L);
    when(assignmentPO.toRolePO()).thenReturn(buildRolePO());
    when(assignmentPO.getAssignmentAuditInfo())
        .thenReturn(JsonUtils.anyFieldMapper().writeValueAsString(buildAssignmentAudit()));

    SecurableObject securableObject =
        SecurableObjects.ofCatalog("catalog1", List.of(Privileges.UseCatalog.allow()));
    List<RoleAssignment> assignments =
        DatastratoRoleMetaService.toRoleAssignments(
            "metalake1", List.of(assignmentPO), Map.of(100L, List.of(securableObject)));

    assertEquals(1, assignments.size());
    RoleAssignment assignment = assignments.get(0);
    assertEquals("role1", assignment.role().name());
    assertEquals(List.of(securableObject), assignment.role().securableObjects());
    assertEquals(ASSIGNED_AT, assignment.assignmentAudit().lastModifiedTime());
    assertEquals("admin", assignment.assignmentAudit().lastModifier());
  }

  private void assertRelationCount(
      Statement statement,
      String relationTable,
      String principalIdColumn,
      long principalId,
      int expectedCount)
      throws Exception {
    String sql =
        String.format(
            "SELECT COUNT(*) FROM %s WHERE %s = %d AND deleted_at = 0",
            relationTable, principalIdColumn, principalId);
    try (ResultSet resultSet = statement.executeQuery(sql)) {
      assertTrue(resultSet.next());
      assertEquals(expectedCount, resultSet.getInt(1));
    }
  }

  private void assertAssignment(RoleAssignment assignment) {
    assertEquals("role1", assignment.role().name());
    assertTrue(assignment.role().securableObjects().isEmpty());
    assertEquals(ASSIGNED_AT, assignment.assignmentAudit().lastModifiedTime());
    assertEquals("admin", assignment.assignmentAudit().lastModifier());
  }

  private void assertAssignmentAudit(Audit audit) {
    assertEquals(ASSIGNED_AT, audit.lastModifiedTime());
    assertEquals("admin", audit.lastModifier());
  }

  private RolePO buildRolePO() throws JsonProcessingException {
    return RolePO.builder()
        .withRoleId(100L)
        .withRoleName("role1")
        .withMetalakeId(1L)
        .withProperties("{}")
        .withAuditInfo(JsonUtils.anyFieldMapper().writeValueAsString(buildRoleAudit()))
        .withCurrentVersion(1L)
        .withLastVersion(1L)
        .withDeletedAt(0L)
        .build();
  }

  private static AuditInfo buildRoleAudit() {
    return AuditInfo.builder().withCreator("creator").withCreateTime(Instant.EPOCH).build();
  }

  private static AuditInfo buildAssignmentAudit() {
    return AuditInfo.builder()
        .withCreator("creator")
        .withCreateTime(Instant.EPOCH)
        .withLastModifier("admin")
        .withLastModifiedTime(ASSIGNED_AT)
        .build();
  }
}
