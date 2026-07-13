/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.converter;

import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.directory.scim.spec.resources.GroupMembership;
import org.apache.directory.scim.spec.resources.ScimGroup;
import org.apache.directory.scim.spec.resources.ScimUser;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.authorization.User;

/** Converts between Gravitino authorization entities and SCIMple resource models. */
public final class ScimResourceConverter {

  private static final String USER_SCHEMA = ScimUser.SCHEMA_URI;
  private static final String GROUP_SCHEMA = ScimGroup.SCHEMA_URI;

  private ScimResourceConverter() {}

  /**
   * Converts a Gravitino user to a SCIM user resource.
   *
   * @param user Gravitino user
   * @return SCIM user
   */
  public static ScimUser toScimUser(User user) {
    String externalId = user.externalId();
    ScimUser scimUser = new ScimUser();
    scimUser.setSchemas(ImmutableSet.of(USER_SCHEMA));
    scimUser.setId(externalId);
    scimUser.setExternalId(externalId);
    scimUser.setUserName(user.name());
    scimUser.setActive(user.enabled());
    return scimUser;
  }

  /**
   * Converts a Gravitino group and member external ids to a SCIM group resource.
   *
   * @param group Gravitino group
   * @param memberExternalIds member user external ids
   * @return SCIM group
   */
  public static ScimGroup toScimGroup(Group group, List<String> memberExternalIds) {
    String externalId = group.externalId();
    ScimGroup scimGroup = new ScimGroup();
    scimGroup.setSchemas(ImmutableSet.of(GROUP_SCHEMA));
    scimGroup.setId(externalId);
    scimGroup.setExternalId(externalId);
    scimGroup.setDisplayName(group.name());
    scimGroup.setMembers(
        memberExternalIds.stream()
            .map(ScimResourceConverter::toGroupMembership)
            .collect(Collectors.toList()));
    return scimGroup;
  }

  private static GroupMembership toGroupMembership(String externalId) {
    GroupMembership membership = new GroupMembership();
    membership.setValue(externalId);
    return membership;
  }
}
