/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.service;

import static org.apache.gravitino.metrics.source.MetricsSource.GRAVITINO_RELATIONAL_STORE_METRIC_NAME;

import com.datastrato.gravitino.scim.storage.mapper.ScimUserGroupRelMapper;
import com.datastrato.gravitino.scim.storage.po.ScimGroupMemberPO;
import com.datastrato.gravitino.scim.storage.relational.utils.ScimExceptionUtils;
import java.io.IOException;
import java.util.List;
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

  /**
   * Lists active members for a group identified by Gravitino group id.
   *
   * @param metalakeName target metalake name
   * @param groupId Gravitino group id
   * @return active group members
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listMembersByGroupId")
  public List<ScimGroupMemberPO> listMembersByGroupId(String metalakeName, long groupId) {
    return SessionUtils.getWithoutCommit(
        ScimUserGroupRelMapper.class,
        mapper -> mapper.selectMembersByGroupId(metalakeName, groupId));
  }

  /**
   * Lists active group names for a user in the given metalake.
   *
   * @param username Gravitino username
   * @param metalakeName target metalake name
   * @return active group names
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listGroupNamesByUsername")
  public List<String> listGroupNamesByUsername(String username, String metalakeName) {
    return SessionUtils.getWithoutCommit(
        ScimUserGroupRelMapper.class,
        mapper -> mapper.selectGroupNamesByUsername(username, metalakeName));
  }

  /**
   * Inserts group memberships by resolving Gravitino ids from {@code group_meta} and {@code
   * user_meta}.
   *
   * @param metalakeName target metalake name
   * @param groupId Gravitino group id
   * @param userIds Gravitino user ids from PATCH {@code members[].value}
   * @param auditInfo serialized audit metadata
   * @param currentVersion current version
   * @param lastVersion last version
   * @return number of membership rows affected
   * @throws IOException if persistence fails
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "insertMemberships")
  public int insertMemberships(
      String metalakeName,
      long groupId,
      List<Long> userIds,
      String auditInfo,
      Long currentVersion,
      Long lastVersion)
      throws IOException {
    if (userIds == null || userIds.isEmpty()) {
      return 0;
    }
    try {
      Integer inserted =
          SessionUtils.doWithCommitAndFetchResult(
              ScimUserGroupRelMapper.class,
              mapper ->
                  mapper.insertMemberships(
                      metalakeName, groupId, userIds, auditInfo, currentVersion, lastVersion));
      return inserted == null ? 0 : inserted;
    } catch (RuntimeException re) {
      ScimExceptionUtils.checkSQLException(re, "membership", String.valueOf(groupId));
      throw re;
    }
  }

  /**
   * Soft-deletes all active memberships for a user identified by Gravitino user id.
   *
   * @param metalakeName target metalake name
   * @param userId Gravitino user id
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "softDeleteMembersByUserId")
  public void softDeleteMembersByUserId(String metalakeName, long userId) {
    SessionUtils.doWithCommit(
        ScimUserGroupRelMapper.class,
        mapper -> mapper.softDeleteMembersByUserId(metalakeName, userId));
  }

  /**
   * Soft-deletes group memberships for users identified by Gravitino user ids.
   *
   * @param metalakeName target metalake name
   * @param groupId Gravitino group id
   * @param userIds Gravitino user ids from PATCH {@code members[].value}
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "softDeleteMembersByGroupAndUserIds")
  public void softDeleteMembersByGroupAndUserIds(
      String metalakeName, long groupId, List<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return;
    }
    SessionUtils.doWithCommit(
        ScimUserGroupRelMapper.class,
        mapper -> mapper.softDeleteMembersByGroupAndUserIds(metalakeName, groupId, userIds));
  }

  /**
   * Soft-deletes all active memberships for a group identified by Gravitino group id.
   *
   * @param metalakeName target metalake name
   * @param groupId Gravitino group id
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "softDeleteMembersByGroupId")
  public void softDeleteMembersByGroupId(String metalakeName, long groupId) {
    SessionUtils.doWithCommit(
        ScimUserGroupRelMapper.class,
        mapper -> mapper.softDeleteMembersByGroupId(metalakeName, groupId));
  }

  /**
   * Soft-deletes active membership rows whose metalake is missing or already soft-deleted.
   *
   * @return number of rows soft-deleted
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "softDeleteMembersByUnavailableMetalake")
  public int softDeleteMembersByUnavailableMetalake() {
    Integer deleted =
        SessionUtils.doWithCommitAndFetchResult(
            ScimUserGroupRelMapper.class,
            mapper -> mapper.softDeleteMembersByUnavailableMetalake());
    return deleted == null ? 0 : deleted;
  }

  /**
   * Replaces all active memberships in a group with the provided member ids.
   *
   * @param metalakeName target metalake name
   * @param groupId Gravitino group id
   * @param userIds Gravitino user ids from PATCH Replace
   * @param auditInfo serialized audit metadata
   * @param currentVersion current version
   * @param lastVersion last version
   * @throws IOException if persistence fails
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "replaceMembersByGroupId")
  public void replaceMembersByGroupId(
      String metalakeName,
      long groupId,
      List<Long> userIds,
      String auditInfo,
      Long currentVersion,
      Long lastVersion)
      throws IOException {
    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                ScimUserGroupRelMapper.class,
                mapper -> mapper.softDeleteMembersByGroupId(metalakeName, groupId)),
        () -> {
          if (userIds != null && !userIds.isEmpty()) {
            try {
              insertMemberships(
                  metalakeName, groupId, userIds, auditInfo, currentVersion, lastVersion);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        });
  }

  /**
   * Physically deletes soft-deleted membership rows older than the legacy timeline.
   *
   * @param legacyTimeline delete rows with {@code deleted_at} before this timestamp
   * @param limit maximum rows to delete in one batch
   * @return number of rows physically deleted
   */
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
