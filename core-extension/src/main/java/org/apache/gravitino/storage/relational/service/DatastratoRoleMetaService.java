/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.storage.relational.service;

import static org.apache.gravitino.metrics.source.MetricsSource.GRAVITINO_RELATIONAL_STORE_METRIC_NAME;

import com.datastrato.gravitino.authorization.mapper.DatastratoSecurableObjectMapper;
import com.google.common.collect.Lists;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.mapper.RoleMetaMapper;
import org.apache.gravitino.storage.relational.po.RolePO;
import org.apache.gravitino.storage.relational.po.SecurableObjectPO;
import org.apache.gravitino.storage.relational.utils.POConverters;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Enterprise service for role metadata queries. */
public class DatastratoRoleMetaService {
  private static final Logger LOG = LoggerFactory.getLogger(DatastratoRoleMetaService.class);
  private static final DatastratoRoleMetaService INSTANCE = new DatastratoRoleMetaService();

  private DatastratoRoleMetaService() {}

  /**
   * Gets the singleton instance.
   *
   * @return The singleton instance.
   */
  public static DatastratoRoleMetaService getInstance() {
    return INSTANCE;
  }

  /**
   * Lists roles with their securable objects in a namespace.
   *
   * @param namespace The role namespace.
   * @return The roles with securable objects.
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listRolesWithSecurableObjectsByNamespace")
  public List<RoleEntity> listRolesWithSecurableObjectsByNamespace(Namespace namespace) {
    AuthorizationUtils.checkRoleNamespace(namespace);
    String metalakeName = namespace.level(0);

    List<RolePO> rolePOs = listRolePOsByMetalake(metalakeName);
    if (rolePOs.isEmpty()) {
      return Collections.emptyList();
    }

    List<Long> roleIds = rolePOs.stream().map(RolePO::getRoleId).collect(Collectors.toList());
    Map<Long, List<SecurableObject>> securableObjectsByRoleId =
        toSecurableObjectsByRoleId(listSecurableObjectsByRoleIds(roleIds));

    return rolePOs.stream()
        .map(
            po ->
                POConverters.fromRolePO(
                    po,
                    securableObjectsByRoleId.getOrDefault(po.getRoleId(), Collections.emptyList()),
                    AuthorizationUtils.ofRoleNamespace(metalakeName)))
        .collect(Collectors.toList());
  }

  private static List<RolePO> listRolePOsByMetalake(String metalakeName) {
    return SessionUtils.getWithoutCommit(
        RoleMetaMapper.class, mapper -> mapper.listRolePOsByMetalake(metalakeName));
  }

  private static List<SecurableObjectPO> listSecurableObjectsByRoleIds(List<Long> roleIds) {
    if (roleIds == null || roleIds.isEmpty()) {
      return Collections.emptyList();
    }

    return SessionUtils.getWithoutCommit(
        DatastratoSecurableObjectMapper.class,
        mapper -> mapper.listSecurableObjectsByRoleIds(roleIds));
  }

  private static Map<Long, List<SecurableObject>> toSecurableObjectsByRoleId(
      List<SecurableObjectPO> securableObjectPOs) {
    Map<Long, List<SecurableObject>> securableObjectsByRoleId = new HashMap<>();

    securableObjectPOs.stream()
        .collect(Collectors.groupingBy(SecurableObjectPO::getType))
        .forEach(
            (type, objects) -> {
              List<Long> objectIds =
                  objects.stream()
                      .map(SecurableObjectPO::getMetadataObjectId)
                      .collect(Collectors.toList());

              Map<Long, String> objectIdAndNameMap =
                  Optional.of(MetadataObject.Type.valueOf(type))
                      .map(MetadataObjectService.TYPE_TO_FULLNAME_FUNCTION_MAP::get)
                      .map(getter -> getter.apply(objectIds))
                      .orElseThrow(
                          () ->
                              new IllegalArgumentException(
                                  "Unsupported metadata object type: " + type));

              for (SecurableObjectPO securableObjectPO : objects) {
                String fullName = objectIdAndNameMap.get(securableObjectPO.getMetadataObjectId());
                if (fullName != null) {
                  securableObjectsByRoleId
                      .computeIfAbsent(
                          securableObjectPO.getRoleId(), ignored -> Lists.newArrayList())
                      .add(
                          POConverters.fromSecurableObjectPO(
                              fullName, securableObjectPO, getType(securableObjectPO.getType())));
                } else {
                  LOG.warn(
                      "The securable object {} {} may be deleted",
                      securableObjectPO.getMetadataObjectId(),
                      securableObjectPO.getType());
                }
              }
            });
    return securableObjectsByRoleId;
  }

  private static MetadataObject.Type getType(String type) {
    return MetadataObject.Type.valueOf(type);
  }
}
