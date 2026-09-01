/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.storage.service;

import static org.apache.gravitino.metrics.source.MetricsSource.GRAVITINO_RELATIONAL_STORE_METRIC_NAME;

import com.datastrato.gravitino.scim.v2.model.ScimToken;
import com.datastrato.gravitino.scim.v2.storage.mapper.ScimTokenMetaMapper;
import com.datastrato.gravitino.scim.v2.storage.po.ScimProvisioningStatsPO;
import com.datastrato.gravitino.scim.v2.storage.po.ScimTokenMetaPO;
import com.datastrato.gravitino.scim.v2.storage.relational.utils.ScimExceptionUtils;
import com.datastrato.gravitino.scim.v2.storage.relational.utils.ScimPOConverters;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.utils.SessionUtils;

/** Service class for SCIM v2 token metadata database operations. */
public class ScimTokenMetaService {
  private static final ScimTokenMetaService INSTANCE = new ScimTokenMetaService();

  private ScimTokenMetaService() {}

  /** Returns the singleton SCIM v2 token metadata service instance. */
  public static ScimTokenMetaService getInstance() {
    return INSTANCE;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getScimTokenMetaByHash")
  public ScimTokenMetaPO getScimTokenMetaByHash(String tokenHash) {
    return SessionUtils.getWithoutCommit(
        ScimTokenMetaMapper.class, mapper -> mapper.selectByTokenHash(tokenHash));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getScimTokenByHash")
  public ScimToken getScimTokenByHash(String tokenHash) {
    ScimTokenMetaPO tokenMeta = getScimTokenMetaByHash(tokenHash);
    return tokenMeta == null ? null : ScimPOConverters.fromPO(tokenMeta);
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getScimTokenMetaByName")
  public ScimTokenMetaPO getScimTokenMetaByName(String tokenName) {
    ScimTokenMetaPO tokenMeta =
        SessionUtils.getWithoutCommit(
            ScimTokenMetaMapper.class, mapper -> mapper.selectByName(tokenName));
    if (tokenMeta == null) {
      throw new NotFoundException("SCIM token not found: %s", tokenName);
    }
    return tokenMeta;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getScimToken")
  public ScimToken getScimToken(String tokenName) {
    return ScimPOConverters.fromPO(getScimTokenMetaByName(tokenName));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listScimProvisioningStats")
  public ScimProvisioningStatsPO listProvisioningStats() {
    return SessionUtils.getWithoutCommit(
        ScimTokenMetaMapper.class, ScimTokenMetaMapper::listProvisioningStats);
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listScimTokens")
  public List<ScimTokenMetaPO> listScimTokens() {
    List<ScimTokenMetaPO> tokens =
        SessionUtils.getWithoutCommit(ScimTokenMetaMapper.class, ScimTokenMetaMapper::listAll);
    return tokens == null ? Collections.emptyList() : tokens;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getMaxScimTokenLastUsedAt")
  public long getMaxScimTokenLastUsedAt() {
    Long lastUsedAt =
        SessionUtils.getWithoutCommit(
            ScimTokenMetaMapper.class, ScimTokenMetaMapper::selectMaxLastUsedAt);
    return lastUsedAt == null ? 0L : lastUsedAt;
  }

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

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "updateScimTokenLastUsedAt")
  public boolean updateScimTokenLastUsedAt(long tokenId) {
    Integer updated =
        SessionUtils.doWithCommitAndFetchResult(
            ScimTokenMetaMapper.class, mapper -> mapper.updateScimTokenLastUsedAt(tokenId));
    return updated != null && updated > 0;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "softDeleteScimToken")
  public boolean softDeleteScimToken(String tokenName) {
    Integer deleted =
        SessionUtils.doWithCommitAndFetchResult(
            ScimTokenMetaMapper.class, mapper -> mapper.softDeleteByName(tokenName));
    return deleted != null && deleted > 0;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "softDeleteExpiredScimTokens")
  public int softDeleteExpiredScimTokens() {
    Integer deleted =
        SessionUtils.doWithCommitAndFetchResult(
            ScimTokenMetaMapper.class, ScimTokenMetaMapper::softDeleteByExpiration);
    return deleted == null ? 0 : deleted;
  }

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
