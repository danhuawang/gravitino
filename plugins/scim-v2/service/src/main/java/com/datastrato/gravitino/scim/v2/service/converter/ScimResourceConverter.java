/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.service.converter;

import com.datastrato.gravitino.scim.v2.model.ScimGroupMeta;
import com.datastrato.gravitino.scim.v2.model.ScimUserMeta;
import com.datastrato.gravitino.scim.v2.service.web.ScimRequestContext;
import com.datastrato.gravitino.scim.v2.service.web.ScimRequestPaths;
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

/** Converts between SCIM v2 metadata and SCIMple resource models. */
public final class ScimResourceConverter {

  private static final String USER_SCHEMA = ScimUser.SCHEMA_URI;
  private static final String GROUP_SCHEMA = ScimGroup.SCHEMA_URI;
  private static final String RESOURCE_USER = "User";
  private static final String RESOURCE_GROUP = "Group";

  private ScimResourceConverter() {}

  /** Converts a SCIM v2 user to a SCIM user resource. */
  public static ScimUser toScimUser(ScimUserMeta user) {
    ScimUser scimUser = new ScimUser();
    scimUser.setSchemas(ImmutableSet.of(USER_SCHEMA));
    scimUser.setId(user.getExternalId());
    scimUser.setExternalId(user.getExternalId());
    scimUser.setUserName(user.getUserName());
    scimUser.setDisplayName(user.getUserName());
    scimUser.setActive(user.isEnabled());
    applyMeta(scimUser, RESOURCE_USER, "Users", user.getExternalId());
    return scimUser;
  }

  /** Converts a SCIM v2 group and member external ids to a SCIM group resource. */
  public static ScimGroup toScimGroup(ScimGroupMeta group, List<String> memberExternalIds) {
    ScimGroup scimGroup = new ScimGroup();
    scimGroup.setSchemas(ImmutableSet.of(GROUP_SCHEMA));
    scimGroup.setId(group.getExternalId());
    scimGroup.setExternalId(group.getExternalId());
    scimGroup.setDisplayName(group.getGroupName());
    scimGroup.setMembers(
        memberExternalIds.stream()
            .map(ScimResourceConverter::toGroupMembership)
            .collect(Collectors.toList()));
    applyMeta(scimGroup, RESOURCE_GROUP, "Groups", group.getExternalId());
    return scimGroup;
  }

  private static GroupMembership toGroupMembership(String memberExternalId) {
    GroupMembership membership = new GroupMembership();
    membership.setValue(memberExternalId);
    return membership;
  }

  private static void applyMeta(
      ScimResource resource, String resourceType, String collection, String resourceId) {
    Meta meta = new Meta();
    meta.setResourceType(resourceType);
    meta.setCreated(LocalDateTime.now(ZoneOffset.UTC));
    meta.setLastModified(LocalDateTime.now(ZoneOffset.UTC));
    String resourcePath = ScimRequestPaths.SCIM_V2_PREFIX + collection + "/" + resourceId;
    meta.setLocation(
        ScimRequestContext.currentRequestBaseUri()
            .map(base -> base + resourcePath)
            .orElse(resourcePath));
    resource.setMeta(meta);
  }
}
