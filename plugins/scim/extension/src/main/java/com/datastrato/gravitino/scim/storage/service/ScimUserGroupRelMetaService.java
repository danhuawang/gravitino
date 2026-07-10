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
   * Lists active members for a group identified by SCIM {@code externalId}.
   *
   * @param metalakeName target metalake name
   * @param groupExternalId SCIM group {@code externalId}
   * @return active group members
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listMembersByGroupExternalId")
  public List<ScimGroupMemberPO> listMembersByGroupExternalId(
      String metalakeName, String groupExternalId) {
    return SessionUtils.getWithoutCommit(
        ScimUserGroupRelMapper.class,
        mapper -> mapper.selectMembersByGroupExternalId(metalakeName, groupExternalId));
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
   * Inserts group memberships by resolving SCIM ids from {@code group_meta} and {@code user_meta}.
   *
   * @param metalakeName target metalake name
   * @param groupExternalId SCIM group {@code externalId}
   * @param userExternalIds SCIM member ids from PATCH {@code members[].value}
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
      String groupExternalId,
      List<String> userExternalIds,
      String auditInfo,
      Long currentVersion,
      Long lastVersion)
      throws IOException {
    if (userExternalIds == null || userExternalIds.isEmpty()) {
      return 0;
    }
    try {
      Integer inserted =
          SessionUtils.doWithCommitAndFetchResult(
              ScimUserGroupRelMapper.class,
              mapper ->
                  mapper.insertMemberships(
                      metalakeName,
                      groupExternalId,
                      userExternalIds,
                      auditInfo,
                      currentVersion,
                      lastVersion));
      return inserted == null ? 0 : inserted;
    } catch (RuntimeException re) {
      ScimExceptionUtils.checkSQLException(re, "membership", groupExternalId);
      throw re;
    }
  }

  /**
   * Soft-deletes all active memberships for a user identified by SCIM {@code externalId}.
   *
   * @param metalakeName target metalake name
   * @param userExternalId SCIM user {@code externalId}
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "softDeleteMembersByUserExternalId")
  public void softDeleteMembersByUserExternalId(String metalakeName, String userExternalId) {
    SessionUtils.doWithCommit(
        ScimUserGroupRelMapper.class,
        mapper -> mapper.softDeleteMembersByUserExternalId(metalakeName, userExternalId));
  }

  /**
   * Soft-deletes group memberships for users identified by SCIM {@code externalId}s.
   *
   * @param metalakeName target metalake name
   * @param groupExternalId SCIM group {@code externalId}
   * @param userExternalIds SCIM member ids from PATCH {@code members[].value}
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "softDeleteMembersByGroupAndUserExternalIds")
  public void softDeleteMembersByGroupAndUserExternalIds(
      String metalakeName, String groupExternalId, List<String> userExternalIds) {
    if (userExternalIds == null || userExternalIds.isEmpty()) {
      return;
    }
    SessionUtils.doWithCommit(
        ScimUserGroupRelMapper.class,
        mapper ->
            mapper.softDeleteMembersByGroupAndUserExternalIds(
                metalakeName, groupExternalId, userExternalIds));
  }

  /**
   * Soft-deletes all active memberships for a group identified by SCIM {@code externalId}.
   *
   * @param metalakeName target metalake name
   * @param groupExternalId SCIM group {@code externalId}
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "softDeleteMembersByGroupExternalId")
  public void softDeleteMembersByGroupExternalId(String metalakeName, String groupExternalId) {
    SessionUtils.doWithCommit(
        ScimUserGroupRelMapper.class,
        mapper -> mapper.softDeleteMembersByGroupExternalId(metalakeName, groupExternalId));
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
   * @param groupExternalId SCIM group {@code externalId}
   * @param userExternalIds SCIM member ids from PATCH Replace
   * @param auditInfo serialized audit metadata
   * @param currentVersion current version
   * @param lastVersion last version
   * @throws IOException if persistence fails
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "replaceMembersByGroupExternalId")
  public void replaceMembersByGroupExternalId(
      String metalakeName,
      String groupExternalId,
      List<String> userExternalIds,
      String auditInfo,
      Long currentVersion,
      Long lastVersion)
      throws IOException {
    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                ScimUserGroupRelMapper.class,
                mapper -> mapper.softDeleteMembersByGroupExternalId(metalakeName, groupExternalId)),
        () -> {
          if (userExternalIds != null && !userExternalIds.isEmpty()) {
            try {
              insertMemberships(
                  metalakeName,
                  groupExternalId,
                  userExternalIds,
                  auditInfo,
                  currentVersion,
                  lastVersion);
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
