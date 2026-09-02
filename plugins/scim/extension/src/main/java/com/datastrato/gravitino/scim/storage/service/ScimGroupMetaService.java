/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.service;

import static org.apache.gravitino.metrics.source.MetricsSource.GRAVITINO_RELATIONAL_STORE_METRIC_NAME;

import com.datastrato.gravitino.scim.model.ScimGroupMeta;
import com.datastrato.gravitino.scim.storage.mapper.ScimGroupMetaMapper;
import com.datastrato.gravitino.scim.storage.po.ScimGroupMetaPO;
import com.datastrato.gravitino.scim.storage.relational.utils.ScimExceptionUtils;
import com.datastrato.gravitino.scim.storage.relational.utils.ScimPOConverters;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.utils.SessionUtils;

/** Service class for SCIM group metadata database operations. */
public class ScimGroupMetaService {
  private static final ScimGroupMetaService INSTANCE = new ScimGroupMetaService();

  private ScimGroupMetaService() {}

  /** Returns the singleton SCIM group metadata service instance. */
  public static ScimGroupMetaService getInstance() {
    return INSTANCE;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "insertScimGroup")
  public void insertScimGroup(ScimGroupMetaPO groupMeta) throws IOException {
    try {
      SessionUtils.doWithCommit(ScimGroupMetaMapper.class, mapper -> mapper.insert(groupMeta));
    } catch (RuntimeException re) {
      ScimExceptionUtils.checkSQLException(re, "group", groupMeta.getGroupName());
      throw re;
    }
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getScimGroupByExternalId")
  public ScimGroupMeta getScimGroupByExternalId(String externalId) {
    ScimGroupMetaPO po =
        SessionUtils.getWithoutCommit(
            ScimGroupMetaMapper.class, mapper -> mapper.selectByExternalId(externalId));
    return po == null ? null : ScimPOConverters.fromGroupPO(po);
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getScimGroupByGroupName")
  public ScimGroupMeta getScimGroupByGroupName(String groupName) {
    ScimGroupMetaPO po =
        SessionUtils.getWithoutCommit(
            ScimGroupMetaMapper.class, mapper -> mapper.selectByGroupName(groupName));
    return po == null ? null : ScimPOConverters.fromGroupPO(po);
  }

  /**
   * Returns an active SCIM group by case-insensitive group name.
   *
   * @param groupName group name to match ignoring case
   * @return matching group, or {@code null} when absent
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getScimGroupByGroupNameIgnoreCase")
  public ScimGroupMeta getScimGroupByGroupNameIgnoreCase(String groupName) {
    ScimGroupMetaPO po =
        SessionUtils.getWithoutCommit(
            ScimGroupMetaMapper.class, mapper -> mapper.selectByGroupNameIgnoreCase(groupName));
    return po == null ? null : ScimPOConverters.fromGroupPO(po);
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getScimGroupByGroupId")
  public ScimGroupMeta getScimGroupByGroupId(long groupId) {
    ScimGroupMetaPO po =
        SessionUtils.getWithoutCommit(
            ScimGroupMetaMapper.class, mapper -> mapper.selectByGroupId(groupId));
    return po == null ? null : ScimPOConverters.fromGroupPO(po);
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "requireScimGroupByGroupId")
  public ScimGroupMeta requireScimGroupByGroupId(long groupId) {
    ScimGroupMeta group = getScimGroupByGroupId(groupId);
    if (group == null) {
      throw new NotFoundException("SCIM group not found: %s", groupId);
    }
    return group;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "requireScimGroupByExternalId")
  public ScimGroupMeta requireScimGroupByExternalId(String externalId) {
    ScimGroupMeta group = getScimGroupByExternalId(externalId);
    if (group == null) {
      throw new NotFoundException("SCIM group not found: %s", externalId);
    }
    return group;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listScimGroups")
  public List<ScimGroupMeta> listScimGroups(int offset, int limit) {
    List<ScimGroupMetaPO> rows =
        SessionUtils.getWithoutCommit(
            ScimGroupMetaMapper.class, mapper -> mapper.listGroups(offset, limit));
    if (rows == null) {
      return Collections.emptyList();
    }
    return rows.stream().map(ScimPOConverters::fromGroupPO).collect(Collectors.toList());
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "countScimGroups")
  public long countScimGroups() {
    Long count =
        SessionUtils.getWithoutCommit(ScimGroupMetaMapper.class, ScimGroupMetaMapper::countGroups);
    return count == null ? 0L : count;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "softDeleteScimGroup")
  public boolean softDeleteScimGroup(long groupId) {
    Integer deleted =
        SessionUtils.doWithCommitAndFetchResult(
            ScimGroupMetaMapper.class, mapper -> mapper.softDeleteByGroupId(groupId));
    return deleted != null && deleted > 0;
  }

  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "deleteScimGroupMetasByLegacyTimeline")
  public int deleteScimGroupMetasByLegacyTimeline(long legacyTimeline, int limit) {
    Integer deleted =
        SessionUtils.doWithCommitAndFetchResult(
            ScimGroupMetaMapper.class,
            mapper -> mapper.deleteByLegacyTimeline(legacyTimeline, limit));
    return deleted == null ? 0 : deleted;
  }
}
