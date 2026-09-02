/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.service;

import static org.apache.gravitino.metrics.source.MetricsSource.GRAVITINO_RELATIONAL_STORE_METRIC_NAME;

import com.datastrato.gravitino.scim.model.ScimUserMeta;
import com.datastrato.gravitino.scim.storage.mapper.ScimUserMetaMapper;
import com.datastrato.gravitino.scim.storage.po.ScimUserMetaPO;
import com.datastrato.gravitino.scim.storage.relational.utils.ScimExceptionUtils;
import com.datastrato.gravitino.scim.storage.relational.utils.ScimPOConverters;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.utils.SessionUtils;

/** Service class for SCIM user metadata database operations. */
public class ScimUserMetaService {
  private static final ScimUserMetaService INSTANCE = new ScimUserMetaService();

  private ScimUserMetaService() {}

  /** Returns the singleton SCIM user metadata service instance. */
  public static ScimUserMetaService getInstance() {
    return INSTANCE;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "insertScimUser")
  public void insertScimUser(ScimUserMetaPO userMeta) throws IOException {
    try {
      SessionUtils.doWithCommit(ScimUserMetaMapper.class, mapper -> mapper.insert(userMeta));
    } catch (RuntimeException re) {
      ScimExceptionUtils.checkSQLException(re, "user", userMeta.getUserName());
      throw re;
    }
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getScimUserByExternalId")
  public ScimUserMeta getScimUserByExternalId(String externalId) {
    ScimUserMetaPO po =
        SessionUtils.getWithoutCommit(
            ScimUserMetaMapper.class, mapper -> mapper.selectByExternalId(externalId));
    return po == null ? null : ScimPOConverters.fromUserPO(po);
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getScimUserByUserName")
  public ScimUserMeta getScimUserByUserName(String userName) {
    ScimUserMetaPO po =
        SessionUtils.getWithoutCommit(
            ScimUserMetaMapper.class, mapper -> mapper.selectByUserName(userName));
    return po == null ? null : ScimPOConverters.fromUserPO(po);
  }

  /**
   * Returns an active SCIM user by case-insensitive user name.
   *
   * @param userName user name to match ignoring case
   * @return matching user, or {@code null} when absent
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getScimUserByUserNameIgnoreCase")
  public ScimUserMeta getScimUserByUserNameIgnoreCase(String userName) {
    ScimUserMetaPO po =
        SessionUtils.getWithoutCommit(
            ScimUserMetaMapper.class, mapper -> mapper.selectByUserNameIgnoreCase(userName));
    return po == null ? null : ScimPOConverters.fromUserPO(po);
  }

  /**
   * Returns active SCIM users matching the given externalIds.
   *
   * @param externalIds SCIM externalIds; {@code null} or empty yields an empty list
   * @return matching users
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listScimUsersByExternalIds")
  public List<ScimUserMeta> listScimUsersByExternalIds(@Nullable List<String> externalIds) {
    if (externalIds == null || externalIds.isEmpty()) {
      return Collections.emptyList();
    }
    List<ScimUserMetaPO> rows =
        SessionUtils.getWithoutCommit(
            ScimUserMetaMapper.class, mapper -> mapper.selectByExternalIds(externalIds));
    if (rows == null) {
      return Collections.emptyList();
    }
    return rows.stream().map(ScimPOConverters::fromUserPO).collect(Collectors.toList());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getScimUserByUserId")
  public ScimUserMeta getScimUserByUserId(long userId) {
    ScimUserMetaPO po =
        SessionUtils.getWithoutCommit(
            ScimUserMetaMapper.class, mapper -> mapper.selectByUserId(userId));
    return po == null ? null : ScimPOConverters.fromUserPO(po);
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "requireScimUserByUserId")
  public ScimUserMeta requireScimUserByUserId(long userId) {
    ScimUserMeta user = getScimUserByUserId(userId);
    if (user == null) {
      throw new NotFoundException("SCIM user not found: %s", userId);
    }
    return user;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "requireScimUserByExternalId")
  public ScimUserMeta requireScimUserByExternalId(String externalId) {
    ScimUserMeta user = getScimUserByExternalId(externalId);
    if (user == null) {
      throw new NotFoundException("SCIM user not found: %s", externalId);
    }
    return user;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listScimUsers")
  public List<ScimUserMeta> listScimUsers(int offset, int limit) {
    List<ScimUserMetaPO> rows =
        SessionUtils.getWithoutCommit(
            ScimUserMetaMapper.class, mapper -> mapper.listUsers(offset, limit));
    if (rows == null) {
      return Collections.emptyList();
    }
    return rows.stream().map(ScimPOConverters::fromUserPO).collect(Collectors.toList());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "countScimUsers")
  public long countScimUsers() {
    Long count =
        SessionUtils.getWithoutCommit(ScimUserMetaMapper.class, ScimUserMetaMapper::countUsers);
    return count == null ? 0L : count;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "updateScimUserEnabled")
  public boolean updateScimUserEnabled(long userId, boolean enabled) {
    Integer updated =
        SessionUtils.doWithCommitAndFetchResult(
            ScimUserMetaMapper.class, mapper -> mapper.updateEnabledByUserId(userId, enabled));
    return updated != null && updated > 0;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "softDeleteScimUser")
  public boolean softDeleteScimUser(long userId) {
    Integer deleted =
        SessionUtils.doWithCommitAndFetchResult(
            ScimUserMetaMapper.class, mapper -> mapper.softDeleteByUserId(userId));
    return deleted != null && deleted > 0;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deleteScimUserMetasByLegacyTimeline")
  public int deleteScimUserMetasByLegacyTimeline(long legacyTimeline, int limit) {
    Integer deleted =
        SessionUtils.doWithCommitAndFetchResult(
            ScimUserMetaMapper.class,
            mapper -> mapper.deleteByLegacyTimeline(legacyTimeline, limit));
    return deleted == null ? 0 : deleted;
  }
}
