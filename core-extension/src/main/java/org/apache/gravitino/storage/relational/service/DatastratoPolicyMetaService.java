/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.storage.relational.service;

import static org.apache.gravitino.metrics.source.MetricsSource.GRAVITINO_RELATIONAL_STORE_METRIC_NAME;

import com.datastrato.gravitino.policy.mapper.DatastratoPolicyMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.po.PolicyMetadataObjectRelPO;
import org.apache.gravitino.storage.relational.utils.ExceptionUtils;
import org.apache.gravitino.storage.relational.utils.SessionUtils;

/** Enterprise service for policy metadata queries. */
public class DatastratoPolicyMetaService {
  private static final DatastratoPolicyMetaService INSTANCE = new DatastratoPolicyMetaService();

  private DatastratoPolicyMetaService() {}

  /**
   * Gets the singleton instance.
   *
   * @return The singleton instance.
   */
  public static DatastratoPolicyMetaService getInstance() {
    return INSTANCE;
  }

  /**
   * Lists associated metadata objects for the selected policies in a metalake.
   *
   * @param metalakeName The metalake name.
   * @param policyIds The policy IDs to query.
   * @return A map of policy ID to associated metadata objects.
   * @throws IOException If an error occurs while accessing metadata storage.
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listAssociatedMetadataObjectsForPolicies")
  public Map<Long, List<MetadataObject>> listAssociatedMetadataObjectsForPolicies(
      String metalakeName, List<Long> policyIds) throws IOException {
    if (policyIds == null || policyIds.isEmpty()) {
      return new HashMap<>();
    }

    try {
      List<PolicyMetadataObjectRelPO> relationPOs =
          SessionUtils.getWithoutCommit(
              DatastratoPolicyMapper.class,
              mapper -> mapper.listAssociatedMetadataObjectsForPolicies(metalakeName, policyIds));

      if (relationPOs == null || relationPOs.isEmpty()) {
        return new HashMap<>();
      }

      Map<Long, List<MetadataObject>> objectsByPolicyId = new HashMap<>();
      relationPOs.stream()
          .collect(
              Collectors.groupingBy(
                  relation -> MetadataObject.Type.valueOf(relation.getMetadataObjectType())))
          .forEach(
              (objectType, relations) -> {
                List<Long> objectIds =
                    relations.stream()
                        .map(PolicyMetadataObjectRelPO::getMetadataObjectId)
                        .distinct()
                        .collect(Collectors.toList());
                Map<Long, String> objectNames =
                    Optional.ofNullable(
                            MetadataObjectService.TYPE_TO_FULLNAME_FUNCTION_MAP.get(objectType))
                        .map(resolver -> resolver.apply(objectIds))
                        .orElseThrow(
                            () ->
                                new IllegalArgumentException(
                                    "Unsupported metadata object type: " + objectType));

                relations.forEach(
                    relation -> {
                      String fullName = objectNames.get(relation.getMetadataObjectId());
                      if (fullName != null) {
                        objectsByPolicyId
                            .computeIfAbsent(relation.getPolicyId(), ignored -> new ArrayList<>())
                            .add(MetadataObjects.parse(fullName, objectType));
                      }
                    });
              });
      return objectsByPolicyId;
    } catch (RuntimeException e) {
      ExceptionUtils.checkSQLException(e, Entity.EntityType.POLICY, metalakeName);
      throw e;
    }
  }
}
