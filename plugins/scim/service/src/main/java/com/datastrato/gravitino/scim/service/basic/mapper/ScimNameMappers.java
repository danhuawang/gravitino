/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.service.basic.mapper;

import java.util.List;
import org.apache.gravitino.UserGroup;
import org.apache.gravitino.auth.GroupMapper;
import org.apache.gravitino.auth.PrincipalMapper;

/** Applies SCIM user/group name mappers from configuration. */
public final class ScimNameMappers {

  private ScimNameMappers() {}

  /**
   * Maps a SCIM userName to a Gravitino user name.
   *
   * @param mapper configured user mapper
   * @param rawUserName SCIM userName
   * @return mapped Gravitino user name
   */
  public static String mapUserName(PrincipalMapper mapper, String rawUserName) {
    return mapper.map(rawUserName).getName();
  }

  /**
   * Maps a SCIM displayName to a Gravitino group name.
   *
   * @param mapper configured group mapper
   * @param rawDisplayName SCIM displayName
   * @return mapped Gravitino group name
   */
  public static String mapGroupName(GroupMapper mapper, String rawDisplayName) {
    List<UserGroup> mappedGroups = mapper.map(List.of(rawDisplayName));
    if (mappedGroups.isEmpty()) {
      return rawDisplayName;
    }
    return mappedGroups.get(0).getGroupName();
  }
}
