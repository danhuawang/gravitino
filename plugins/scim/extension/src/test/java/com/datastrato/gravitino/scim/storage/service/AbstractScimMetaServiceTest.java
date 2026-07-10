/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.service;

import com.datastrato.gravitino.scim.storage.mapper.AbstractScimMetaStorageTest;
import com.datastrato.gravitino.scim.storage.mapper.ScimTokenMetaMapper;
import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.gravitino.storage.relational.po.MetalakePO;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.ibatis.session.SqlSession;

/** Base class for SCIM token metadata service tests. */
public abstract class AbstractScimMetaServiceTest extends AbstractScimMetaStorageTest {
  protected static final String METALAKE_NAME = "test_metalake";
  protected static final long METALAKE_ID = 10L;
  protected static final long LEGACY_TIMELINE = Instant.now().toEpochMilli() + 1000;

  protected ScimTokenMetaMapper scimTokenMetaMapper;

  @Override
  protected void initializeMappers() {
    scimTokenMetaMapper = sharedSession.getMapper(ScimTokenMetaMapper.class);
  }

  /** Reopens the shared session after service-layer commits or direct SQL updates. */
  protected void refreshSession() {
    closeSession();
    sharedSession = SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
    initializeMappers();
  }

  /**
   * Runs a service call outside the shared session (services use their own sessions), then
   * refreshes mapper state for assertions.
   */
  protected void runServiceCall(ServiceCall serviceCall) throws IOException {
    closeSession();
    serviceCall.run();
    refreshSession();
  }

  @FunctionalInterface
  protected interface ServiceCall {
    void run() throws IOException;
  }

  protected void insertMetalake() {
    insertMetalake(METALAKE_ID, METALAKE_NAME);
  }

  protected void insertMetalake(long metalakeId, String metalakeName) {
    MetalakeMetaMapper metalakeMetaMapper = sharedSession.getMapper(MetalakeMetaMapper.class);
    metalakeMetaMapper.insertMetalakeMeta(
        MetalakePO.builder()
            .withMetalakeId(metalakeId)
            .withMetalakeName(metalakeName)
            .withAuditInfo("{}")
            .withSchemaVersion("1.0")
            .withCurrentVersion(1L)
            .withLastVersion(0L)
            .withDeletedAt(0L)
            .build());
  }

  protected void softDeleteMetalake(long metalakeId) {
    MetalakeMetaMapper metalakeMetaMapper = sharedSession.getMapper(MetalakeMetaMapper.class);
    metalakeMetaMapper.softDeleteMetalakeMetaByMetalakeId(metalakeId);
  }

  protected ScimTokenMetaPO createTokenMeta(
      long tokenId, long metalakeId, String tokenName, String tokenHash, long expiresAt) {
    return ScimTokenMetaPO.builder()
        .withTokenId(tokenId)
        .withMetalakeId(metalakeId)
        .withTokenName(tokenName)
        .withTokenHash(tokenHash)
        .withExpiresAt(expiresAt)
        .withAuditInfo("{}")
        .withDeletedAt(0L)
        .withUpdatedAt(0L)
        .build();
  }

  protected void insertToken(
      long tokenId, long metalakeId, String tokenName, String tokenHash, long expiresAt) {
    scimTokenMetaMapper.insert(
        createTokenMeta(tokenId, metalakeId, tokenName, tokenHash, expiresAt));
  }

  protected void softDeleteAllTokens() throws SQLException {
    executeUpdate("UPDATE scim_token_meta SET deleted_at = 1 WHERE deleted_at = 0");
  }

  protected int countTokens() {
    return countTableRows("scim_token_meta");
  }

  private void executeUpdate(String... sqlStatements) throws SQLException {
    try (SqlSession sqlSession =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = sqlSession.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql : sqlStatements) {
        statement.execute(sql);
      }
    }
  }

  private int countTableRows(String tableName) {
    try (SqlSession sqlSession =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = sqlSession.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + tableName)) {
      if (rs.next()) {
        return rs.getInt(1);
      }
      throw new IllegalStateException("Count query returned no rows for table: " + tableName);
    } catch (SQLException e) {
      throw new RuntimeException("SQL execution failed", e);
    }
  }
}
