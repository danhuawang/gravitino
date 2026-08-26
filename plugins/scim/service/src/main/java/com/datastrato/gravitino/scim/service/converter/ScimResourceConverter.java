/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.service.converter;

import com.datastrato.gravitino.scim.ScimUtils;
import com.datastrato.gravitino.scim.service.web.ScimMetalakeContext;
import com.datastrato.gravitino.scim.service.web.ScimRequestPaths;
import com.google.common.collect.ImmutableSet;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.directory.scim.spec.resources.GroupMembership;
import org.apache.directory.scim.spec.resources.ScimGroup;
import org.apache.directory.scim.spec.resources.ScimResource;
import org.apache.directory.scim.spec.resources.ScimUser;
import org.apache.directory.scim.spec.schema.Meta;
import org.apache.gravitino.Audit;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.authorization.User;

/** Converts between Gravitino authorization entities and SCIMple resource models. */
public final class ScimResourceConverter {

  private static final String USER_SCHEMA = ScimUser.SCHEMA_URI;
  private static final String GROUP_SCHEMA = ScimGroup.SCHEMA_URI;
  private static final String RESOURCE_USER = "User";
  private static final String RESOURCE_GROUP = "Group";
  private static final String USERS_COLLECTION = "Users";
  private static final String GROUPS_COLLECTION = "Groups";

  private ScimResourceConverter() {}

  /**
   * Converts a Gravitino user to a SCIM user resource.
   *
   * <p>SCIM {@code id} is always the Gravitino-assigned user id. {@code externalId} is optional and
   * returned only when set on the Gravitino user. {@code meta} includes {@code resourceType},
   * timestamps from {@link User#auditInfo()}, and a metalake-scoped {@code location} when request
   * context is available (absolute when the request origin is set).
   *
   * @param user Gravitino user
   * @return SCIM user
   */
  public static ScimUser toScimUser(User user) {
    ScimUser scimUser = new ScimUser();
    scimUser.setSchemas(ImmutableSet.of(USER_SCHEMA));
    scimUser.setId(String.valueOf(user.id()));
    scimUser.setExternalId(ScimUtils.blankToNull(user.externalId()));
    scimUser.setUserName(user.name());
    // Keycloak SCIM client calls displayName.get() after create; keep it non-empty.
    scimUser.setDisplayName(user.name());
    scimUser.setActive(user.enabled());
    applyMeta(
        scimUser, RESOURCE_USER, USERS_COLLECTION, String.valueOf(user.id()), user.auditInfo());
    return scimUser;
  }

  /**
   * Converts a Gravitino group and member SCIM ids to a SCIM group resource.
   *
   * <p>SCIM {@code id} is always the Gravitino-assigned group id. {@code members[].value} are
   * Gravitino user ids encoded as strings. {@code externalId} is optional. {@code meta} includes
   * {@code resourceType}, timestamps from {@link Group#auditInfo()}, and a metalake-scoped {@code
   * location} when request context is available (absolute when the request origin is set).
   *
   * @param group Gravitino group
   * @param memberIds member user SCIM ids (Gravitino user ids as strings)
   * @return SCIM group
   */
  public static ScimGroup toScimGroup(Group group, List<String> memberIds) {
    ScimGroup scimGroup = new ScimGroup();
    scimGroup.setSchemas(ImmutableSet.of(GROUP_SCHEMA));
    scimGroup.setId(String.valueOf(group.id()));
    scimGroup.setExternalId(ScimUtils.blankToNull(group.externalId()));
    scimGroup.setDisplayName(group.name());
    scimGroup.setMembers(
        memberIds.stream()
            .map(ScimResourceConverter::toGroupMembership)
            .collect(Collectors.toList()));
    applyMeta(
        scimGroup,
        RESOURCE_GROUP,
        GROUPS_COLLECTION,
        String.valueOf(group.id()),
        group.auditInfo());
    return scimGroup;
  }

  private static GroupMembership toGroupMembership(String memberId) {
    GroupMembership membership = new GroupMembership();
    membership.setValue(memberId);
    return membership;
  }

  private static void applyMeta(
      ScimResource resource,
      String resourceType,
      String collection,
      String resourceId,
      Audit audit) {
    Meta meta = new Meta();
    meta.setResourceType(resourceType);
    if (audit != null) {
      Instant created = audit.createTime();
      Instant lastModified =
          audit.lastModifiedTime() != null ? audit.lastModifiedTime() : audit.createTime();
      if (created != null) {
        meta.setCreated(toUtcLocalDateTime(created));
      }
      if (lastModified != null) {
        meta.setLastModified(toUtcLocalDateTime(lastModified));
      }
    }
    ScimMetalakeContext.currentMetalake()
        .ifPresent(
            metalake -> {
              String resourcePath =
                  ScimRequestPaths.METALAKE_SCIM_PREFIX
                      + metalake
                      + "/"
                      + collection
                      + "/"
                      + resourceId;
              // Prefer absolute URI to match the HTTP Location header shape (RFC 7643
              // meta.location).
              meta.setLocation(
                  ScimMetalakeContext.currentRequestBaseUri()
                      .map(base -> base + resourcePath)
                      .orElse(resourcePath));
            });
    resource.setMeta(meta);
  }

  private static LocalDateTime toUtcLocalDateTime(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
