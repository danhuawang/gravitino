/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.storage.relational.service;

import static org.apache.gravitino.Configs.ENTITY_RELATIONAL_JDBC_BACKEND_MAX_CONNECTIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.authorization.po.DirectoryGroupDetailPO;
import com.datastrato.gravitino.authorization.po.DirectoryUserDetailPO;
import com.datastrato.gravitino.authorization.utils.DatastratoPOConverters;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Executes Directory User/Group Overview SQL against H2, MySQL, and PostgreSQL.
 *
 * <p>MySQL/PostgreSQL cases are tagged {@code gravitino-docker-test}.
 */
public class TestDirectoryDetailMetaServiceBackends {

  private static final String H2_URL =
      "jdbc:h2:mem:directory_detail_backends;DB_CLOSE_DELAY=-1;MODE=MySQL";
  private static final Instant CREATE_TIME = Instant.parse("2025-09-14T00:00:00Z");
  private static final String AUDIT_JSON =
      "{\"creator\":\"admin\",\"createTime\":\"2025-09-14T00:00:00Z\"}";

  @Test
  void testH2Backend() throws Exception {
    verifyBackend(H2_URL, "sa", "", "org.h2.Driver", BackendKind.H2);
  }

  @Test
  @Tag("gravitino-docker-test")
  void testMySQLBackend() throws Exception {
    try (MySQLContainer<?> container =
        new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("gravitino")
            .withUsername("root")
            .withPassword("root")) {
      container.start();
      verifyBackend(
          container.getJdbcUrl(),
          container.getUsername(),
          container.getPassword(),
          container.getDriverClassName(),
          BackendKind.MYSQL);
    }
  }

  @Test
  @Tag("gravitino-docker-test")
  void testPostgreSQLBackend() throws Exception {
    try (PostgreSQLContainer<?> container =
        new PostgreSQLContainer<>("postgres:13")
            .withDatabaseName("gravitino")
            .withUsername("root")
            .withPassword("root")) {
      container.start();
      verifyBackend(
          container.getJdbcUrl(),
          container.getUsername(),
          container.getPassword(),
          container.getDriverClassName(),
          BackendKind.POSTGRESQL);
    }
  }

  private void verifyBackend(
      String url, String user, String password, String driver, BackendKind kind) throws Exception {
    SqlSessionFactoryHelper.getInstance().close();
    createSchema(url, user, password, kind);
    SqlSessionFactoryHelper.getInstance().init(config(url, user, password, driver));
    try {
      DirectoryUserDetailPO localUser =
          DatastratoUserMetaService.getInstance().getDirectoryUser("sam.o", IdentitySource.LOCAL);
      assertEquals("sam.o", localUser.getUserName());
      assertTrue(localUser.enabledOrDefault());
      assertEquals(IdentitySource.LOCAL, localUser.origin());
      assertNull(localUser.getExternalId());
      assertEquals(CREATE_TIME, DatastratoPOConverters.parseCreateTime(localUser.getAuditInfo()));

      DirectoryUserDetailPO provisionedUser =
          DatastratoUserMetaService.getInstance()
              .getDirectoryUser("dana.k", IdentitySource.PROVISIONED);
      assertEquals("dana.k", provisionedUser.getUserName());
      assertTrue(provisionedUser.enabledOrDefault());
      assertEquals(IdentitySource.PROVISIONED, provisionedUser.origin());
      assertEquals("ext-dana", provisionedUser.getExternalId());
      assertEquals(
          CREATE_TIME, DatastratoPOConverters.parseCreateTime(provisionedUser.getAuditInfo()));

      DirectoryGroupDetailPO localGroup =
          DatastratoGroupMetaService.getInstance()
              .getDirectoryGroup("governance", IdentitySource.LOCAL);
      assertEquals("governance", localGroup.getGroupName());
      assertEquals(1, localGroup.memberCountOrZero());
      assertEquals(IdentitySource.LOCAL, localGroup.origin());
      assertEquals(CREATE_TIME, DatastratoPOConverters.parseCreateTime(localGroup.getAuditInfo()));

      DirectoryGroupDetailPO provisionedGroup =
          DatastratoGroupMetaService.getInstance()
              .getDirectoryGroup("platform", IdentitySource.PROVISIONED);
      assertEquals("platform", provisionedGroup.getGroupName());
      assertEquals(1, provisionedGroup.memberCountOrZero());
      assertEquals(IdentitySource.PROVISIONED, provisionedGroup.origin());
      assertNotNull(provisionedGroup.getAuditInfo());
      assertEquals(
          CREATE_TIME, DatastratoPOConverters.parseCreateTime(provisionedGroup.getAuditInfo()));
    } finally {
      SqlSessionFactoryHelper.getInstance().close();
    }
  }

  private Config config(String url, String user, String password, String driver) {
    Config config = mock(Config.class);
    when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_URL)).thenReturn(url);
    when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_DRIVER)).thenReturn(driver);
    when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_USER)).thenReturn(user);
    when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_PASSWORD)).thenReturn(password);
    when(config.get(ENTITY_RELATIONAL_JDBC_BACKEND_MAX_CONNECTIONS)).thenReturn(10);
    when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_WAIT_MILLISECONDS)).thenReturn(1000L);
    return config;
  }

  private void createSchema(String url, String user, String password, BackendKind kind)
      throws Exception {
    try (Connection connection = DriverManager.getConnection(url, user, password);
        Statement statement = connection.createStatement()) {
      if (kind == BackendKind.POSTGRESQL) {
        statement.execute("DROP TABLE IF EXISTS scim_user_group_rel CASCADE");
        statement.execute("DROP TABLE IF EXISTS idp_user_group_rel CASCADE");
        statement.execute("DROP TABLE IF EXISTS scim_user_meta CASCADE");
        statement.execute("DROP TABLE IF EXISTS scim_group_meta CASCADE");
        statement.execute("DROP TABLE IF EXISTS idp_user_meta CASCADE");
        statement.execute("DROP TABLE IF EXISTS idp_group_meta CASCADE");
        statement.execute(
            "CREATE TABLE idp_user_meta ("
                + "user_id BIGINT NOT NULL PRIMARY KEY,"
                + "user_name VARCHAR(128) NOT NULL,"
                + "password_hash VARCHAR(1024) NOT NULL,"
                + "enabled BOOLEAN NOT NULL DEFAULT TRUE,"
                + "audit_info TEXT NOT NULL,"
                + "deleted_at BIGINT NOT NULL DEFAULT 0)");
        statement.execute(
            "CREATE TABLE idp_group_meta ("
                + "group_id BIGINT NOT NULL PRIMARY KEY,"
                + "group_name VARCHAR(128) NOT NULL,"
                + "audit_info TEXT NOT NULL,"
                + "deleted_at BIGINT NOT NULL DEFAULT 0)");
        statement.execute(
            "CREATE TABLE idp_user_group_rel ("
                + "id BIGSERIAL PRIMARY KEY,"
                + "user_id BIGINT NOT NULL,"
                + "group_id BIGINT NOT NULL,"
                + "deleted_at BIGINT NOT NULL DEFAULT 0)");
        statement.execute(
            "CREATE TABLE scim_user_meta ("
                + "user_id BIGINT NOT NULL PRIMARY KEY,"
                + "user_name VARCHAR(128) NOT NULL,"
                + "external_id VARCHAR(256),"
                + "enabled SMALLINT NOT NULL DEFAULT 1,"
                + "audit_info TEXT NOT NULL,"
                + "deleted_at BIGINT NOT NULL DEFAULT 0)");
        statement.execute(
            "CREATE TABLE scim_group_meta ("
                + "group_id BIGINT NOT NULL PRIMARY KEY,"
                + "group_name VARCHAR(128) NOT NULL,"
                + "audit_info TEXT NOT NULL,"
                + "deleted_at BIGINT NOT NULL DEFAULT 0)");
        statement.execute(
            "CREATE TABLE scim_user_group_rel ("
                + "id BIGSERIAL PRIMARY KEY,"
                + "user_id BIGINT NOT NULL,"
                + "group_id BIGINT NOT NULL,"
                + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      } else {
        // H2 (MySQL mode) and MySQL share TINYINT/CLOB-compatible DDL.
        statement.execute("DROP TABLE IF EXISTS scim_user_group_rel");
        statement.execute("DROP TABLE IF EXISTS idp_user_group_rel");
        statement.execute("DROP TABLE IF EXISTS scim_user_meta");
        statement.execute("DROP TABLE IF EXISTS scim_group_meta");
        statement.execute("DROP TABLE IF EXISTS idp_user_meta");
        statement.execute("DROP TABLE IF EXISTS idp_group_meta");
        String auditType = kind == BackendKind.MYSQL ? "MEDIUMTEXT" : "CLOB";
        statement.execute(
            "CREATE TABLE idp_user_meta ("
                + "user_id BIGINT NOT NULL PRIMARY KEY,"
                + "user_name VARCHAR(128) NOT NULL,"
                + "password_hash VARCHAR(1024) NOT NULL,"
                + "enabled TINYINT(1) NOT NULL DEFAULT 1,"
                + "audit_info "
                + auditType
                + " NOT NULL,"
                + "deleted_at BIGINT NOT NULL DEFAULT 0)");
        statement.execute(
            "CREATE TABLE idp_group_meta ("
                + "group_id BIGINT NOT NULL PRIMARY KEY,"
                + "group_name VARCHAR(128) NOT NULL,"
                + "audit_info "
                + auditType
                + " NOT NULL,"
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
                + "enabled TINYINT(1) NOT NULL DEFAULT 1,"
                + "audit_info "
                + auditType
                + " NOT NULL,"
                + "deleted_at BIGINT NOT NULL DEFAULT 0)");
        statement.execute(
            "CREATE TABLE scim_group_meta ("
                + "group_id BIGINT NOT NULL PRIMARY KEY,"
                + "group_name VARCHAR(128) NOT NULL,"
                + "audit_info "
                + auditType
                + " NOT NULL,"
                + "deleted_at BIGINT NOT NULL DEFAULT 0)");
        statement.execute(
            "CREATE TABLE scim_user_group_rel ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "user_id BIGINT NOT NULL,"
                + "group_id BIGINT NOT NULL,"
                + "deleted_at BIGINT NOT NULL DEFAULT 0)");
      }

      String idpEnabled = kind == BackendKind.POSTGRESQL ? "TRUE" : "1";
      statement.execute(
          "INSERT INTO idp_user_meta (user_id, user_name, password_hash, enabled, audit_info,"
              + " deleted_at) VALUES (100, 'sam.o', 'hash', "
              + idpEnabled
              + ", '"
              + AUDIT_JSON
              + "', 0)");
      statement.execute(
          "INSERT INTO idp_group_meta (group_id, group_name, audit_info, deleted_at)"
              + " VALUES (200, 'governance', '"
              + AUDIT_JSON
              + "', 0)");
      statement.execute(
          "INSERT INTO idp_user_group_rel (user_id, group_id, deleted_at) VALUES (100, 200, 0)");
      statement.execute(
          "INSERT INTO scim_user_meta (user_id, user_name, external_id, enabled, audit_info,"
              + " deleted_at) VALUES (10, 'dana.k', 'ext-dana', 1, '"
              + AUDIT_JSON
              + "', 0)");
      statement.execute(
          "INSERT INTO scim_group_meta (group_id, group_name, audit_info, deleted_at)"
              + " VALUES (20, 'platform', '"
              + AUDIT_JSON
              + "', 0)");
      statement.execute(
          "INSERT INTO scim_user_group_rel (user_id, group_id, deleted_at) VALUES (10, 20, 0)");
    }
  }

  private enum BackendKind {
    H2,
    MYSQL,
    POSTGRESQL
  }
}
