/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization;

import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/** Group metadata returned by identity lookup before adding a group into a metalake. */
public class GroupLookupInfo {

  private final String groupName;
  private final String comment;
  private final List<String> members;

  /**
   * @param groupName The group name.
   * @param comment The group comment.
   * @param members Member usernames.
   */
  public GroupLookupInfo(
      String groupName, @Nullable String comment, @Nullable List<String> members) {
    this.groupName = Preconditions.checkNotNull(groupName, "groupName cannot be null");
    this.comment = comment == null ? "" : comment;
    this.members = members == null ? Collections.emptyList() : members;
  }

  /**
   * @return The group name.
   */
  public String groupName() {
    return groupName;
  }

  /**
   * @return The group comment.
   */
  public String comment() {
    return comment;
  }

  /**
   * @return Member usernames.
   */
  public List<String> members() {
    return members;
  }
}
