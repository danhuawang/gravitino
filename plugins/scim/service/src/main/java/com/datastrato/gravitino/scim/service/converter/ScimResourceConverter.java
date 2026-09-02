/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.service.converter;

import com.datastrato.gravitino.scim.model.ScimGroupMeta;
import com.datastrato.gravitino.scim.model.ScimUserMeta;
import com.datastrato.gravitino.scim.service.web.ScimRequestContext;
import com.datastrato.gravitino.scim.service.web.ScimRequestPaths;
import com.google.common.collect.ImmutableSet;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.directory.scim.spec.resources.GroupMembership;
import org.apache.directory.scim.spec.resources.ScimGroup;
import org.apache.directory.scim.spec.resources.ScimResource;
import org.apache.directory.scim.spec.resources.ScimUser;
import org.apache.directory.scim.spec.schema.Meta;

/** Converts between SCIM metadata and SCIMple resource models. */
public final class ScimResourceConverter {

  private static final String USER_SCHEMA = ScimUser.SCHEMA_URI;
  private static final String GROUP_SCHEMA = ScimGroup.SCHEMA_URI;
  private static final String RESOURCE_USER = "User";
  private static final String RESOURCE_GROUP = "Group";

  private ScimResourceConverter() {}

  /**
   * Converts a SCIM user to a SCIM user resource.
   *
   * <p>SCIM {@code id} is Gravitino's {@code user_id}; {@code externalId} is optional client
   * correlation.
   */
  public static ScimUser toScimUser(ScimUserMeta user) {
    String resourceId = toResourceId(user.getUserId());
    ScimUser scimUser = new ScimUser();
    scimUser.setSchemas(ImmutableSet.of(USER_SCHEMA));
    scimUser.setId(resourceId);
    scimUser.setExternalId(user.getExternalId());
    scimUser.setUserName(user.getUserName());
    scimUser.setDisplayName(user.getUserName());
    scimUser.setActive(user.isEnabled());
    applyMeta(scimUser, RESOURCE_USER, "Users", resourceId);
    return scimUser;
  }

  /**
   * Converts a SCIM group and member user ids to a SCIM group resource.
   *
   * <p>SCIM {@code id} is Gravitino's {@code group_id}; member {@code value}s are SCIM user ids
   * ({@code user_id}).
   */
  public static ScimGroup toScimGroup(ScimGroupMeta group, List<String> memberUserIds) {
    String resourceId = toResourceId(group.getGroupId());
    ScimGroup scimGroup = new ScimGroup();
    scimGroup.setSchemas(ImmutableSet.of(GROUP_SCHEMA));
    scimGroup.setId(resourceId);
    scimGroup.setExternalId(group.getExternalId());
    scimGroup.setDisplayName(group.getGroupName());
    scimGroup.setMembers(
        memberUserIds.stream()
            .map(ScimResourceConverter::toGroupMembership)
            .collect(Collectors.toList()));
    applyMeta(scimGroup, RESOURCE_GROUP, "Groups", resourceId);
    return scimGroup;
  }

  /** Formats a Gravitino numeric id as the SCIM resource id string. */
  public static String toResourceId(long id) {
    return Long.toString(id);
  }

  private static GroupMembership toGroupMembership(String memberUserId) {
    GroupMembership membership = new GroupMembership();
    membership.setValue(memberUserId);
    return membership;
  }

  private static void applyMeta(
      ScimResource resource, String resourceType, String collection, String resourceId) {
    Meta meta = new Meta();
    meta.setResourceType(resourceType);
    meta.setCreated(LocalDateTime.now(ZoneOffset.UTC));
    meta.setLastModified(LocalDateTime.now(ZoneOffset.UTC));
    String resourcePath = ScimRequestPaths.SCIM_PREFIX + collection + "/" + resourceId;
    meta.setLocation(
        ScimRequestContext.currentRequestBaseUri()
            .map(base -> base + resourcePath)
            .orElse(resourcePath));
    resource.setMeta(meta);
  }
}
