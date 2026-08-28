/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package org.apache.gravitino.storage.relational.service;

import static org.apache.gravitino.metrics.source.MetricsSource.GRAVITINO_RELATIONAL_STORE_METRIC_NAME;

import com.datastrato.gravitino.authorization.RoleAssignment;
import com.datastrato.gravitino.authorization.RoleGroupAssignment;
import com.datastrato.gravitino.authorization.RoleUserAssignment;
import com.datastrato.gravitino.authorization.mapper.DatastratoRoleAssignmentMapper;
import com.datastrato.gravitino.authorization.mapper.DatastratoSecurableObjectMapper;
import com.datastrato.gravitino.authorization.po.RoleAssignmentPO;
import com.datastrato.gravitino.authorization.po.RoleGroupAssignmentPO;
import com.datastrato.gravitino.authorization.po.RoleUserAssignmentPO;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.apache.gravitino.exceptions.NoSuchGroupException;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.exceptions.NoSuchRoleException;
import org.apache.gravitino.exceptions.NoSuchUserException;
import org.apache.gravitino.json.JsonUtils;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.GroupEntity;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.mapper.RoleMetaMapper;
import org.apache.gravitino.storage.relational.po.GroupRoleRelPO;
import org.apache.gravitino.storage.relational.po.RolePO;
import org.apache.gravitino.storage.relational.po.SecurableObjectPO;
import org.apache.gravitino.storage.relational.po.UserRoleRelPO;
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

  /**
   * Lists roles assigned to a user, including privileges and assignment audit information.
   *
   * @param metalake The metalake name.
   * @param user The user name.
   * @return The user role assignments.
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listUserRoleAssignments")
  public List<RoleAssignment> listUserRoleAssignments(String metalake, String user) {
    List<RoleAssignmentPO> assignments =
        SessionUtils.getWithoutCommit(
            DatastratoRoleAssignmentMapper.class,
            mapper -> mapper.listRoleAssignmentsByUser(metalake, user));
    return toRoleAssignmentsForPrincipal(metalake, user, true, assignments);
  }

  /**
   * Lists roles assigned to a group, including privileges and assignment audit information.
   *
   * @param metalake The metalake name.
   * @param group The group name.
   * @return The group role assignments.
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listGroupRoleAssignments")
  public List<RoleAssignment> listGroupRoleAssignments(String metalake, String group) {
    List<RoleAssignmentPO> assignments =
        SessionUtils.getWithoutCommit(
            DatastratoRoleAssignmentMapper.class,
            mapper -> mapper.listRoleAssignmentsByGroup(metalake, group));
    return toRoleAssignmentsForPrincipal(metalake, group, false, assignments);
  }

  /**
   * Assigns one role to multiple users and groups in one transaction.
   *
   * <p>Each principal type is written with one batch SQL statement. Existing active assignments are
   * left unchanged so their original assignment audit information is preserved.
   *
   * @param roleId The role id.
   * @param users The users to assign.
   * @param groups The groups to assign.
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "batchAssignRoleToPrincipals")
  public void batchAssignRoleToPrincipals(
      long roleId, List<UserEntity> users, List<GroupEntity> groups) {
    List<UserRoleRelPO> userAssignments =
        users.stream()
            .flatMap(
                user ->
                    POConverters.initializeUserRoleRelsPOWithVersion(
                        user, Collections.singletonList(roleId))
                        .stream())
            .collect(Collectors.toList());
    List<GroupRoleRelPO> groupAssignments =
        groups.stream()
            .flatMap(
                group ->
                    POConverters.initializeGroupRoleRelsPOWithVersion(
                        group, Collections.singletonList(roleId))
                        .stream())
            .collect(Collectors.toList());

    if (userAssignments.isEmpty() && groupAssignments.isEmpty()) {
      return;
    }

    SessionUtils.doMultipleWithCommit(
        () -> {
          if (!userAssignments.isEmpty()) {
            SessionUtils.doWithoutCommit(
                DatastratoRoleAssignmentMapper.class,
                mapper -> mapper.batchAssignRoleToUsers(userAssignments));
          }
        },
        () -> {
          if (!groupAssignments.isEmpty()) {
            SessionUtils.doWithoutCommit(
                DatastratoRoleAssignmentMapper.class,
                mapper -> mapper.batchAssignRoleToGroups(groupAssignments));
          }
        });
  }

  /**
   * Lists users assigned to a role, including assignment audit and identity origin.
   *
   * @param metalake The metalake name.
   * @param role The role name.
   * @return The role user assignments.
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listUserAssignmentsByRole")
  public List<RoleUserAssignment> listUserAssignmentsByRole(String metalake, String role) {
    List<RoleUserAssignmentPO> assignments =
        SessionUtils.getWithoutCommit(
            DatastratoRoleAssignmentMapper.class,
            mapper -> mapper.listUserAssignmentsByRole(metalake, role));
    return toRoleUserAssignments(metalake, role, assignments);
  }

  /**
   * Lists groups assigned to a role, including assignment audit and user count.
   *
   * @param metalake The metalake name.
   * @param role The role name.
   * @return The role group assignments.
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listGroupAssignmentsByRole")
  public List<RoleGroupAssignment> listGroupAssignmentsByRole(String metalake, String role) {
    List<RoleGroupAssignmentPO> assignments =
        SessionUtils.getWithoutCommit(
            DatastratoRoleAssignmentMapper.class,
            mapper -> mapper.listGroupAssignmentsByRole(metalake, role));
    return toRoleGroupAssignments(metalake, role, assignments);
  }

  static List<RoleUserAssignment> toRoleUserAssignments(
      String metalake, String role, List<RoleUserAssignmentPO> assignments) {
    validateRoleAssignmentRows(
        metalake,
        role,
        assignments,
        assignments.isEmpty() ? null : assignments.get(0).getRequestedMetalakeId(),
        assignments.isEmpty() ? null : assignments.get(0).getRoleId());
    return assignments.stream()
        .filter(assignment -> assignment.getUserId() != null)
        .map(
            assignment -> {
              UserEntity user =
                  POConverters.fromUserPO(
                      assignment.toUserPO(),
                      Collections.emptyList(),
                      AuthorizationUtils.ofUserNamespace(metalake));
              return new RoleUserAssignment(
                  user,
                  deserializeAuditInfo(assignment.getAssignmentAuditInfo()),
                  Boolean.TRUE.equals(assignment.getInBuiltInIdp()));
            })
        .collect(Collectors.toList());
  }

  static List<RoleGroupAssignment> toRoleGroupAssignments(
      String metalake, String role, List<RoleGroupAssignmentPO> assignments) {
    validateRoleAssignmentRows(
        metalake,
        role,
        assignments,
        assignments.isEmpty() ? null : assignments.get(0).getRequestedMetalakeId(),
        assignments.isEmpty() ? null : assignments.get(0).getRoleId());
    return assignments.stream()
        .filter(assignment -> assignment.getGroupId() != null)
        .map(
            assignment -> {
              GroupEntity group =
                  POConverters.fromGroupPO(
                      assignment.toGroupPO(),
                      Collections.emptyList(),
                      AuthorizationUtils.ofGroupNamespace(metalake));
              return new RoleGroupAssignment(
                  group,
                  deserializeAuditInfo(assignment.getAssignmentAuditInfo()),
                  assignment.getUserCount() == null ? 0 : assignment.getUserCount());
            })
        .collect(Collectors.toList());
  }

  static List<RoleAssignment> toRoleAssignments(
      String metalake, List<RoleAssignmentPO> assignments) {
    if (assignments.isEmpty()) {
      return Collections.emptyList();
    }

    List<Long> roleIds =
        assignments.stream().map(RoleAssignmentPO::getRoleId).collect(Collectors.toList());
    Map<Long, List<SecurableObject>> securableObjectsByRoleId =
        toSecurableObjectsByRoleId(listSecurableObjectsByRoleIds(roleIds));

    return toRoleAssignments(metalake, assignments, securableObjectsByRoleId);
  }

  static List<RoleAssignment> toRoleAssignments(
      String metalake,
      List<RoleAssignmentPO> assignments,
      Map<Long, List<SecurableObject>> securableObjectsByRoleId) {
    return assignments.stream()
        .map(
            assignment -> {
              RoleEntity role =
                  POConverters.fromRolePO(
                      assignment.toRolePO(),
                      securableObjectsByRoleId.getOrDefault(
                          assignment.getRoleId(), Collections.emptyList()),
                      AuthorizationUtils.ofRoleNamespace(metalake));
              return new RoleAssignment(
                  role, deserializeAuditInfo(assignment.getAssignmentAuditInfo()));
            })
        .collect(Collectors.toList());
  }

  private static List<RoleAssignment> toRoleAssignmentsForPrincipal(
      String metalake, String principal, boolean user, List<RoleAssignmentPO> assignments) {
    if (assignments.isEmpty() || assignments.get(0).getRequestedMetalakeId() == null) {
      throw new NoSuchMetalakeException("Metalake %s does not exist", metalake);
    }

    if (assignments.get(0).getPrincipalId() == null) {
      if (user) {
        throw new NoSuchUserException(
            "User %s does not exist in the metalake %s", principal, metalake);
      }
      throw new NoSuchGroupException(
          "Group %s does not exist in the metalake %s", principal, metalake);
    }

    List<RoleAssignmentPO> activeAssignments =
        assignments.stream()
            .filter(assignment -> assignment.getRoleId() != null)
            .collect(Collectors.toList());
    return toRoleAssignments(metalake, activeAssignments);
  }

  private static void validateRoleAssignmentRows(
      String metalake, String role, List<?> assignments, Long requestedMetalakeId, Long roleId) {
    if (assignments.isEmpty() || requestedMetalakeId == null) {
      throw new NoSuchMetalakeException("Metalake %s does not exist", metalake);
    }
    if (roleId == null) {
      throw new NoSuchRoleException("Role %s does not exist in the metalake %s", role, metalake);
    }
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

  private static AuditInfo deserializeAuditInfo(String auditInfo) {
    try {
      return JsonUtils.anyFieldMapper().readValue(auditInfo, AuditInfo.class);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to deserialize role assignment audit information", e);
    }
  }

  private static MetadataObject.Type getType(String type) {
    return MetadataObject.Type.valueOf(type);
  }
}
