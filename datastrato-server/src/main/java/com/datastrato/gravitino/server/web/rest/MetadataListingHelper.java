/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.dto.authorization.RoleAssignmentDetailsDTO;
import com.datastrato.gravitino.dto.authorization.RoleMembersDTO;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared helpers for listing metadata visible to the current user. */
final class MetadataListingHelper {

  private static final Logger LOG = LoggerFactory.getLogger(MetadataListingHelper.class);

  private MetadataListingHelper() {}

  static NameIdentifier[] listVisibleSchemaIdentifiers(
      SchemaDispatcher schemaDispatcher, Namespace namespace) {
    NameIdentifier[] schemaIdentifiers = schemaDispatcher.listSchemas(namespace);
    return filterByExpression(
        namespace.level(0),
        AuthorizationExpressionConstants.FILTER_SCHEMA_AUTHORIZATION_EXPRESSION,
        Entity.EntityType.SCHEMA,
        schemaIdentifiers,
        Function.identity());
  }

  static <E> E[] filterByExpression(
      String metalake,
      String expression,
      Entity.EntityType entityType,
      E[] entities,
      Function<E, NameIdentifier> toNameIdentifier) {
    try {
      return MetadataAuthzHelper.filterByExpression(
          metalake, expression, entityType, entities, toNameIdentifier);
    } catch (IllegalArgumentException e) {
      if (!isMetadataAuthorizationReady()) {
        LOG.warn(
            "Skip metadata authorization filtering for {} due to uninitialized GravitinoEnv.",
            entityType);
        return entities;
      }
      throw e;
    }
  }

  static MetadataObject[] filterMetadataObject(String metalake, MetadataObject[] metadataObjects) {
    try {
      return MetadataAuthzHelper.filterMetadataObject(metalake, metadataObjects);
    } catch (IllegalArgumentException e) {
      if (!isMetadataAuthorizationReady()) {
        LOG.warn(
            "Skip metadata authorization filtering for metadata objects due to uninitialized"
                + " GravitinoEnv.");
        return metadataObjects;
      }
      throw e;
    }
  }

  static RoleAssignmentDetailsDTO[] filterRoleAssignmentDetails(
      String metalake, RoleAssignmentDetailsDTO[] details) {
    Set<String> visibleUsers =
        filterPrincipalNames(
            metalake,
            details,
            AuthorizationExpressionConstants.LOAD_USER_AUTHORIZATION_EXPRESSION,
            Entity.EntityType.USER);
    Set<String> visibleGroups =
        filterPrincipalNames(
            metalake,
            details,
            AuthorizationExpressionConstants.LOAD_GROUP_AUTHORIZATION_EXPRESSION,
            Entity.EntityType.GROUP);

    return Arrays.stream(details)
        .map(detail -> filterRoleAssignmentDetail(detail, visibleUsers, visibleGroups))
        .toArray(RoleAssignmentDetailsDTO[]::new);
  }

  private static Set<String> filterPrincipalNames(
      String metalake,
      RoleAssignmentDetailsDTO[] details,
      String expression,
      Entity.EntityType entityType) {
    String[] candidates =
        Arrays.stream(details)
            .flatMap(
                detail ->
                    Arrays.stream(
                        entityType == Entity.EntityType.USER
                            ? detail.getMembers().getUsers()
                            : detail.getMembers().getGroups()))
            .distinct()
            .toArray(String[]::new);
    if (candidates.length == 0) {
      return Collections.emptySet();
    }

    return Arrays.stream(
            filterByExpression(
                metalake,
                expression,
                entityType,
                candidates,
                name ->
                    entityType == Entity.EntityType.USER
                        ? NameIdentifierUtil.ofUser(metalake, name)
                        : NameIdentifierUtil.ofGroup(metalake, name)))
        .collect(Collectors.toSet());
  }

  private static RoleAssignmentDetailsDTO filterRoleAssignmentDetail(
      RoleAssignmentDetailsDTO detail, Set<String> visibleUsers, Set<String> visibleGroups) {
    String[] users =
        Arrays.stream(detail.getMembers().getUsers())
            .filter(visibleUsers::contains)
            .toArray(String[]::new);
    String[] groups =
        Arrays.stream(detail.getMembers().getGroups())
            .filter(visibleGroups::contains)
            .toArray(String[]::new);
    return new RoleAssignmentDetailsDTO(
        detail.getRole(),
        new RoleMembersDTO(users, groups),
        detail.getOwner(),
        detail.getAssignment());
  }

  private static boolean isMetadataAuthorizationReady() {
    return GravitinoEnv.getInstance().config() != null
        && GravitinoEnv.getInstance().accessControlDispatcher() != null;
  }
}
