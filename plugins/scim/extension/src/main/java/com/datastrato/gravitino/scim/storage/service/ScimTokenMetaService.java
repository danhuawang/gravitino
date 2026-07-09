/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.service;

import static org.apache.gravitino.metrics.source.MetricsSource.GRAVITINO_RELATIONAL_STORE_METRIC_NAME;

import com.datastrato.gravitino.scim.model.ScimToken;
import com.datastrato.gravitino.scim.storage.mapper.ScimTokenMetaMapper;
import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
import com.datastrato.gravitino.scim.storage.relational.utils.ScimExceptionUtils;
import com.datastrato.gravitino.scim.storage.relational.utils.ScimPOConverters;
import java.io.IOException;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.utils.SessionUtils;

/** Service class for SCIM token metadata database operations. */
public class ScimTokenMetaService {
  private static final ScimTokenMetaService INSTANCE = new ScimTokenMetaService();

  private ScimTokenMetaService() {}

  /** Returns the singleton SCIM token metadata service instance. */
  public static ScimTokenMetaService getInstance() {
    return INSTANCE;
  }

  /**
   * Loads an active token row by hash for storage-layer callers.
   *
   * @param tokenHash SHA-256 hex digest
   * @return token metadata or {@code null} when not found
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getScimTokenMetaByHash")
  public ScimTokenMetaPO getScimTokenMetaByHash(String tokenHash) {
    return SessionUtils.getWithoutCommit(
        ScimTokenMetaMapper.class, mapper -> mapper.selectByTokenHash(tokenHash));
  }

  /**
   * Loads an active token by hash.
   *
   * @param tokenHash SHA-256 hex digest
   * @return domain token or {@code null} when not found
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getScimTokenByHash")
  public ScimToken getScimTokenByHash(String tokenHash) {
    ScimTokenMetaPO tokenMeta = getScimTokenMetaByHash(tokenHash);
    return tokenMeta == null ? null : ScimPOConverters.fromPO(tokenMeta);
  }

  /**
   * Loads an active token row by metalake name and token name for storage-layer callers.
   *
   * @param metalakeName target metalake name
   * @param tokenName token name
   * @return active token metadata
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getScimTokenMetaByMetalakeAndName")
  public ScimTokenMetaPO getScimTokenMetaByMetalakeAndName(String metalakeName, String tokenName) {
    ScimTokenMetaPO tokenMeta =
        SessionUtils.getWithoutCommit(
            ScimTokenMetaMapper.class,
            mapper -> mapper.selectByMetalakeAndName(metalakeName, tokenName));
    if (tokenMeta == null) {
      throw new NotFoundException("SCIM token not found: %s", tokenName);
    }
    return tokenMeta;
  }

  /**
   * Loads an active token by metalake name and token name.
   *
   * @param metalakeName target metalake name
   * @param tokenName token name
   * @return active domain token
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getScimToken")
  public ScimToken getScimToken(String metalakeName, String tokenName) {
    return ScimPOConverters.fromPO(getScimTokenMetaByMetalakeAndName(metalakeName, tokenName));
  }

  /**
   * Inserts a new token metadata row.
   *
   * @param tokenMeta token metadata to insert
   * @throws IOException if persistence fails
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "insertScimToken")
  public void insertScimToken(ScimTokenMetaPO tokenMeta) throws IOException {
    try {
      SessionUtils.doWithCommit(ScimTokenMetaMapper.class, mapper -> mapper.insert(tokenMeta));
    } catch (RuntimeException re) {
      ScimExceptionUtils.checkSQLException(re, "token", tokenMeta.getTokenName());
      throw re;
    }
  }

  /**
   * Rotates token metadata using optimistic concurrency on the previous row state.
   *
   * @param newTokenMeta updated token metadata
   * @param oldTokenMeta previous token metadata
   * @return true when a row was updated
   * @throws IOException if persistence fails
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "updateScimTokenOnRotate")
  public boolean updateScimTokenOnRotate(ScimTokenMetaPO newTokenMeta, ScimTokenMetaPO oldTokenMeta)
      throws IOException {
    try {
      Integer updated =
          SessionUtils.doWithCommitAndFetchResult(
              ScimTokenMetaMapper.class,
              mapper -> mapper.updateTokenOnRotate(newTokenMeta, oldTokenMeta));
      return updated != null && updated > 0;
    } catch (RuntimeException re) {
      ScimExceptionUtils.checkSQLException(re, "token", newTokenMeta.getTokenName());
      throw re;
    }
  }

  /**
   * Soft-deletes the named token for the given metalake.
   *
   * @param metalakeName target metalake name
   * @param tokenName token name
   * @return true when an active row was soft-deleted
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "softDeleteScimToken")
  public boolean softDeleteScimToken(String metalakeName, String tokenName) {
    Integer deleted =
        SessionUtils.doWithCommitAndFetchResult(
            ScimTokenMetaMapper.class,
            mapper -> mapper.softDeleteByMetalakeAndName(metalakeName, tokenName));
    return deleted != null && deleted > 0;
  }

  /**
   * Soft-deletes expired token rows whose {@code expires_at} is in the past.
   *
   * @return number of rows soft-deleted
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "softDeleteExpiredScimTokens")
  public int softDeleteExpiredScimTokens() {
    Integer deleted =
        SessionUtils.doWithCommitAndFetchResult(
            ScimTokenMetaMapper.class, mapper -> mapper.softDeleteByExpiration());
    return deleted == null ? 0 : deleted;
  }

  /**
   * Physically deletes soft-deleted token rows older than the legacy timeline.
   *
   * @param legacyTimeline delete rows with {@code deleted_at} before this timestamp
   * @param limit maximum rows to delete in one batch
   * @return number of rows physically deleted
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deleteScimTokenMetasByLegacyTimeline")
  public int deleteTokenMetasByLegacyTimeline(long legacyTimeline, int limit) {
    Integer deleted =
        SessionUtils.doWithCommitAndFetchResult(
            ScimTokenMetaMapper.class,
            mapper -> mapper.deleteByLegacyTimeline(legacyTimeline, limit));
    return deleted == null ? 0 : deleted;
  }
}
