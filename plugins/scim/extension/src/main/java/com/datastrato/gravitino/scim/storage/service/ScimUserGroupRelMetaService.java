/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.service;

import static org.apache.gravitino.metrics.source.MetricsSource.GRAVITINO_RELATIONAL_STORE_METRIC_NAME;

import com.datastrato.gravitino.scim.storage.mapper.ScimUserGroupRelMapper;
import com.datastrato.gravitino.scim.storage.po.ScimGroupMemberPO;
import com.datastrato.gravitino.scim.storage.relational.utils.ScimExceptionUtils;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.utils.SessionUtils;

/** Service class for SCIM user-group membership database operations. */
public class ScimUserGroupRelMetaService {
  private static final ScimUserGroupRelMetaService INSTANCE = new ScimUserGroupRelMetaService();

  private ScimUserGroupRelMetaService() {}

  /** Returns the singleton SCIM user-group membership meta service instance. */
  public static ScimUserGroupRelMetaService getInstance() {
    return INSTANCE;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listMembersByGroupId")
  public List<ScimGroupMemberPO> listMembersByGroupId(long groupId) {
    List<ScimGroupMemberPO> members =
        SessionUtils.getWithoutCommit(
            ScimUserGroupRelMapper.class, mapper -> mapper.selectMembersByGroupId(groupId));
    return members == null ? Collections.emptyList() : members;
  }

  /**
   * Lists active members for many groups in one query.
   *
   * @param groupIds group ids; {@code null} or empty yields an empty list
   * @return member rows including each membership group id
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listMembersByGroupIds")
  public List<ScimGroupMemberPO> listMembersByGroupIds(@Nullable List<Long> groupIds) {
    if (groupIds == null || groupIds.isEmpty()) {
      return Collections.emptyList();
    }
    List<ScimGroupMemberPO> members =
        SessionUtils.getWithoutCommit(
            ScimUserGroupRelMapper.class, mapper -> mapper.selectMembersByGroupIds(groupIds));
    return members == null ? Collections.emptyList() : members;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listGroupNamesByUsername")
  public List<String> listGroupNamesByUsername(String username) {
    return SessionUtils.getWithoutCommit(
        ScimUserGroupRelMapper.class, mapper -> mapper.selectGroupNamesByUsername(username));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "insertMemberships")
  public int insertMemberships(
      long groupId, List<Long> userIds, Long currentVersion, Long lastVersion) throws IOException {
    if (userIds == null || userIds.isEmpty()) {
      return 0;
    }
    try {
      Integer inserted =
          SessionUtils.doWithCommitAndFetchResult(
              ScimUserGroupRelMapper.class,
              mapper -> mapper.insertMemberships(groupId, userIds, currentVersion, lastVersion));
      return inserted == null ? 0 : inserted;
    } catch (RuntimeException re) {
      ScimExceptionUtils.checkSQLException(re, "membership", String.valueOf(groupId));
      throw re;
    }
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "softDeleteMembersByUserId")
  public void softDeleteMembersByUserId(long userId) {
    SessionUtils.doWithCommit(
        ScimUserGroupRelMapper.class, mapper -> mapper.softDeleteMembersByUserId(userId));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "softDeleteMembersByGroupAndUserIds")
  public void softDeleteMembersByGroupAndUserIds(long groupId, List<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return;
    }
    SessionUtils.doWithCommit(
        ScimUserGroupRelMapper.class,
        mapper -> mapper.softDeleteMembersByGroupAndUserIds(groupId, userIds));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "softDeleteMembersByGroupId")
  public void softDeleteMembersByGroupId(long groupId) {
    SessionUtils.doWithCommit(
        ScimUserGroupRelMapper.class, mapper -> mapper.softDeleteMembersByGroupId(groupId));
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "softDeleteOrphanMemberships")
  public int softDeleteOrphanMemberships() {
    Integer deleted =
        SessionUtils.doWithCommitAndFetchResult(
            ScimUserGroupRelMapper.class, ScimUserGroupRelMapper::softDeleteOrphanMemberships);
    return deleted == null ? 0 : deleted;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "replaceMembersByGroupId")
  public void replaceMembersByGroupId(
      long groupId, List<Long> userIds, Long currentVersion, Long lastVersion) throws IOException {
    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                ScimUserGroupRelMapper.class, mapper -> mapper.softDeleteMembersByGroupId(groupId)),
        () -> {
          if (userIds != null && !userIds.isEmpty()) {
            try {
              insertMemberships(groupId, userIds, currentVersion, lastVersion);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        });
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "updateMemberUserId")
  public boolean updateMemberUserId(
      long groupId, long oldUserId, long newUserId, Long currentVersion, Long lastVersion)
      throws IOException {
    try {
      Integer updated =
          SessionUtils.doWithCommitAndFetchResult(
              ScimUserGroupRelMapper.class,
              mapper ->
                  mapper.updateMemberUserId(
                      groupId, oldUserId, newUserId, currentVersion, lastVersion));
      return updated != null && updated > 0;
    } catch (RuntimeException re) {
      ScimExceptionUtils.checkSQLException(re, "membership", String.valueOf(groupId));
      throw re;
    }
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deleteScimUserGroupRelMetasByLegacyTimeline")
  public int deleteScimUserGroupRelMetasByLegacyTimeline(long legacyTimeline, int limit) {
    Integer deleted =
        SessionUtils.doWithCommitAndFetchResult(
            ScimUserGroupRelMapper.class,
            mapper -> mapper.deleteByLegacyTimeline(legacyTimeline, limit));
    return deleted == null ? 0 : deleted;
  }
}
