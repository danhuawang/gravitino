/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.converter;

import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
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
   * <p>SCIM {@code id} is always the Gravitino-assigned user id. {@code externalId} is optional and
   * returned only when set on the Gravitino user.
   *
   * @param user Gravitino user
   * @return SCIM user
   */
  public static ScimUser toScimUser(User user) {
    ScimUser scimUser = new ScimUser();
    scimUser.setSchemas(ImmutableSet.of(USER_SCHEMA));
    scimUser.setId(String.valueOf(user.id()));
    scimUser.setExternalId(blankToNull(user.externalId()));
    scimUser.setUserName(user.name());
    // Keycloak SCIM client calls displayName.get() after create; keep it non-empty.
    scimUser.setDisplayName(user.name());
    scimUser.setActive(user.enabled());
    return scimUser;
  }

  /**
   * Converts a Gravitino group and member SCIM ids to a SCIM group resource.
   *
   * <p>SCIM {@code id} is always the Gravitino-assigned group id. {@code members[].value} are
   * Gravitino user ids encoded as strings. {@code externalId} is optional.
   *
   * @param group Gravitino group
   * @param memberIds member user SCIM ids (Gravitino user ids as strings)
   * @return SCIM group
   */
  public static ScimGroup toScimGroup(Group group, List<String> memberIds) {
    ScimGroup scimGroup = new ScimGroup();
    scimGroup.setSchemas(ImmutableSet.of(GROUP_SCHEMA));
    scimGroup.setId(String.valueOf(group.id()));
    scimGroup.setExternalId(blankToNull(group.externalId()));
    scimGroup.setDisplayName(group.name());
    scimGroup.setMembers(
        memberIds.stream()
            .map(ScimResourceConverter::toGroupMembership)
            .collect(Collectors.toList()));
    return scimGroup;
  }

  private static GroupMembership toGroupMembership(String memberId) {
    GroupMembership membership = new GroupMembership();
    membership.setValue(memberId);
    return membership;
  }

  private static String blankToNull(String value) {
    return StringUtils.isBlank(value) ? null : value;
  }
}
