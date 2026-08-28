/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.authorization.utils;

import com.datastrato.gravitino.authorization.UserWithGroups;
import com.datastrato.gravitino.authorization.po.UserWithGroupsPO;
import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.json.JsonUtils;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.storage.relational.utils.POConverters;

/** JSON helpers for enterprise authorization read models. */
public final class DatastratoPOConverters {

  private DatastratoPOConverters() {}

  /**
   * Parses a JSON array column into distinct, sorted names.
   *
   * @param jsonArray serialized JSON array, or blank when empty
   * @return parsed names
   */
  public static List<String> parseNameArray(String jsonArray) {
    if (StringUtils.isBlank(jsonArray)) {
      return Collections.emptyList();
    }
    try {
      List<String> names = JsonUtils.anyFieldMapper().readValue(jsonArray, List.class);
      return names.stream()
          .filter(Objects::nonNull)
          .map(String::valueOf)
          .filter(StringUtils::isNotBlank)
          .distinct()
          .sorted()
          .collect(Collectors.toList());
    } catch (Exception e) {
      throw new RuntimeException("Failed to deserialize json object:", e);
    }
  }

  /**
   * Converts a {@link UserWithGroupsPO} row to {@link UserWithGroups}.
   *
   * @param userPO User row with aggregated group names.
   * @param namespace User namespace for the metalake.
   * @return User with group names.
   */
  public static UserWithGroups fromUserWithGroupsPO(UserWithGroupsPO userPO, Namespace namespace) {
    Preconditions.checkNotNull(userPO, "userPO cannot be null");
    Preconditions.checkNotNull(namespace, "namespace cannot be null");
    UserEntity user = POConverters.fromExtendedUserPO(userPO, namespace);
    return new UserWithGroups(user, parseNameArray(userPO.getGroupNames()), userPO.inBuiltInIdp());
  }
}
