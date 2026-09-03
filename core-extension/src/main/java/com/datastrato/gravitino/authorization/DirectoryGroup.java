/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization;

import com.datastrato.gravitino.dto.authorization.DirectoryGroupDTO;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

/**
 * Instance-scoped directory group for Configure → Directory → Groups.
 *
 * <p>Rows come from {@code idp_group_meta} ({@code Local}), {@code scim_group_meta} ({@code
 * Provisioned}), or metalake {@code group_meta} only ({@code JIT}).
 */
public final class DirectoryGroup implements DirectoryGroupDTO.DirectoryGroupView {

  private final String name;
  private final int memberCount;
  private final IdentitySource origin;
  private final List<String> metalakes;

  /**
   * Creates a directory group.
   *
   * @param name Group name.
   * @param memberCount Identity-store member count (0 for JIT).
   * @param origin Local, Provisioned, or JIT.
   * @param metalakes Metalake names where the group exists in {@code group_meta}.
   */
  public DirectoryGroup(
      String name, int memberCount, IdentitySource origin, List<String> metalakes) {
    Preconditions.checkArgument(StringUtils.isNotBlank(name), "name cannot be blank");
    Preconditions.checkArgument(memberCount >= 0, "memberCount cannot be negative");
    Preconditions.checkArgument(origin != null, "origin cannot be null");
    this.name = name;
    this.memberCount = memberCount;
    this.origin = origin;
    this.metalakes = metalakes == null ? Collections.emptyList() : List.copyOf(metalakes);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public int memberCount() {
    return memberCount;
  }

  @Override
  public IdentitySource origin() {
    return origin;
  }

  @Override
  public List<String> metalakes() {
    return metalakes;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DirectoryGroup)) {
      return false;
    }
    DirectoryGroup that = (DirectoryGroup) o;
    return memberCount == that.memberCount
        && Objects.equals(name, that.name)
        && origin == that.origin
        && Objects.equals(metalakes, that.metalakes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, memberCount, origin, metalakes);
  }
}
