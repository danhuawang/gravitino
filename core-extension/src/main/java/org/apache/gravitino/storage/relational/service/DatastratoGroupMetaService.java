/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.storage.relational.service;

import static org.apache.gravitino.metrics.source.MetricsSource.GRAVITINO_RELATIONAL_STORE_METRIC_NAME;

import com.datastrato.gravitino.authorization.DirectoryGroup;
import com.datastrato.gravitino.authorization.mapper.DatastratoGroupMetaMapper;
import com.datastrato.gravitino.authorization.po.DirectoryGroupPO;
import com.datastrato.gravitino.authorization.utils.DatastratoPOConverters;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.utils.SessionUtils;

/**
 * Enterprise service for directory / group identity-store reads that do not fit metalake-scoped
 * EntityStore relation APIs.
 */
public class DatastratoGroupMetaService {
  private static final DatastratoGroupMetaService INSTANCE = new DatastratoGroupMetaService();

  private DatastratoGroupMetaService() {}

  /**
   * Gets the singleton instance.
   *
   * @return The singleton instance.
   */
  public static DatastratoGroupMetaService getInstance() {
    return INSTANCE;
  }

  /**
   * Lists identity-store groups for Configure → Directory → Groups.
   *
   * <p>Local groups come from {@code idp_group_meta}; Provisioned groups from {@code
   * scim_group_meta}; JIT groups from metalake {@code group_meta} only. When a group name exists in
   * an identity store and metalake tables, the identity-store row wins.
   *
   * @return Directory groups ordered by group name.
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listDirectoryGroups")
  public List<DirectoryGroup> listDirectoryGroups() {
    return SessionUtils.getWithoutCommit(
        DatastratoGroupMetaMapper.class,
        mapper -> {
          List<DirectoryGroupPO> groupPOs = mapper.listDirectoryGroups();
          return groupPOs.stream()
              .map(DatastratoPOConverters::fromDirectoryGroupPO)
              .collect(Collectors.toList());
        });
  }
}
