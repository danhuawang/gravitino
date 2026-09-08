/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.authorization.RoleAssignment;
import com.datastrato.gravitino.dto.authorization.AssignedRoleDTO;
import com.datastrato.gravitino.dto.authorization.RoleAssignmentDetailsDTO;
import com.datastrato.gravitino.dto.authorization.RoleMembersDTO;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.RelationalEntity;
import org.apache.gravitino.SupportsRelationOperations;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.Role;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.dto.authorization.OwnerDTO;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** Converts role assignments into privilege-free REST response DTOs. */
final class RoleAssignmentDTOConverter {

  static RoleAssignmentDetailsDTO[] toDTOs(
      String metalake, RoleAssignment[] assignments, SupportsRelationOperations relationOperations)
      throws IOException {
    if (assignments.length == 0) {
      return new RoleAssignmentDetailsDTO[0];
    }

    List<String> roleNames =
        Arrays.stream(assignments)
            .map(assignment -> assignment.role().name())
            .distinct()
            .collect(Collectors.toList());
    List<NameIdentifier> roleIdentifiers = new ArrayList<>(roleNames.size());
    roleNames.stream()
        .map(role -> NameIdentifierUtil.ofRole(metalake, role))
        .forEach(roleIdentifiers::add);

    Map<String, Set<String>> usersByRole = initializeMembersByRole(roleNames);
    collectUsersByRole(
        usersByRole,
        relationOperations.batchListEntitiesByRelation(
            SupportsRelationOperations.Type.ROLE_USER_REL,
            roleIdentifiers,
            Entity.EntityType.ROLE));
    Map<String, Set<String>> groupsByRole = initializeMembersByRole(roleNames);
    collectGroupsByRole(
        groupsByRole,
        relationOperations.batchListEntitiesByRelation(
            SupportsRelationOperations.Type.ROLE_GROUP_REL,
            roleIdentifiers,
            Entity.EntityType.ROLE));
    Map<String, OwnerDTO> ownersByRole =
        collectOwnersByRole(
            relationOperations.batchListEntitiesByRelation(
                SupportsRelationOperations.Type.OWNER_REL,
                roleIdentifiers,
                Entity.EntityType.ROLE));

    return Arrays.stream(assignments)
        .map(assignment -> toDTO(assignment, usersByRole, groupsByRole, ownersByRole))
        .toArray(RoleAssignmentDetailsDTO[]::new);
  }

  private static Map<String, Set<String>> initializeMembersByRole(List<String> roleNames) {
    Map<String, Set<String>> membersByRole = new LinkedHashMap<>();
    roleNames.forEach(role -> membersByRole.put(role, new TreeSet<>()));
    return membersByRole;
  }

  private static void collectUsersByRole(
      Map<String, Set<String>> usersByRole, List<RelationalEntity<?>> relations) {
    relations.forEach(
        relation -> {
          Set<String> users = usersByRole.get(relation.source().name());
          if (users != null && relation.targetEntity() instanceof User) {
            users.add(((User) relation.targetEntity()).name());
          }
        });
  }

  private static void collectGroupsByRole(
      Map<String, Set<String>> groupsByRole, List<RelationalEntity<?>> relations) {
    relations.forEach(
        relation -> {
          Set<String> groups = groupsByRole.get(relation.source().name());
          if (groups != null && relation.targetEntity() instanceof Group) {
            groups.add(((Group) relation.targetEntity()).name());
          }
        });
  }

  private static Map<String, OwnerDTO> collectOwnersByRole(List<RelationalEntity<?>> relations) {
    Map<String, OwnerDTO> ownersByRole = new LinkedHashMap<>();
    relations.forEach(
        relation -> {
          OwnerDTO owner = toOwnerDTO(relation.targetEntity());
          if (owner != null) {
            ownersByRole.put(relation.source().name(), owner);
          }
        });
    return ownersByRole;
  }

  private static OwnerDTO toOwnerDTO(Entity ownerEntity) {
    if (ownerEntity instanceof User) {
      return OwnerDTO.builder()
          .withName(((User) ownerEntity).name())
          .withType(Owner.Type.USER)
          .build();
    }
    if (ownerEntity instanceof Group) {
      return OwnerDTO.builder()
          .withName(((Group) ownerEntity).name())
          .withType(Owner.Type.GROUP)
          .build();
    }
    return null;
  }

  private static RoleAssignmentDetailsDTO toDTO(
      RoleAssignment assignment,
      Map<String, Set<String>> usersByRole,
      Map<String, Set<String>> groupsByRole,
      Map<String, OwnerDTO> ownersByRole) {
    Role role = assignment.role();
    return new RoleAssignmentDetailsDTO(
        new AssignedRoleDTO(
            role.name(),
            role.properties() == null ? Collections.emptyMap() : role.properties(),
            DTOConverters.toDTO(role.auditInfo())),
        new RoleMembersDTO(
            usersByRole.get(role.name()).toArray(new String[0]),
            groupsByRole.get(role.name()).toArray(new String[0])),
        ownersByRole.get(role.name()),
        DTOConverters.toDTO(assignment.assignmentAudit()));
  }

  private RoleAssignmentDTOConverter() {}
}
