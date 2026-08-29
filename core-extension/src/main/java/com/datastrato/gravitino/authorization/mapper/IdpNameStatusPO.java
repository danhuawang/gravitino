/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.authorization.mapper;

import com.datastrato.gravitino.authorization.IdpNameStatus;
import com.datastrato.gravitino.dto.authorization.ExtendedGroupDTO;
import com.datastrato.gravitino.dto.authorization.ExtendedUserDTO;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.exceptions.NoSuchGroupException;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.exceptions.NoSuchUserException;
import org.apache.gravitino.storage.relational.po.ExtendedGroupPO;
import org.apache.gravitino.storage.relational.po.ExtendedUserPO;
import org.apache.gravitino.storage.relational.utils.POConverters;

/**
 * MyBatis row types for built-in IdP JOIN queries.
 *
 * <p>The top-level type maps IdP picker rows ({@code name} + {@code status}). Nested types map
 * metalake user/group list rows with an extra {@code inBuiltInIdp} flag. All are read-only and are
 * not written to the database.
 */
public class IdpNameStatusPO {

  private String name;
  private Integer status;

  /**
   * @return The IdP user or group name, or {@code null} when the metalake exists but the IdP has no
   *     identities.
   */
  public String getName() {
    return name;
  }

  /**
   * @param name The IdP user or group name.
   */
  public void setName(String name) {
    this.name = name;
  }

  /**
   * @return {@code 1} if the name is already in the metalake, otherwise {@code 0}.
   */
  public Integer getStatus() {
    return status;
  }

  /**
   * @param status {@code 1} if already in the metalake, otherwise {@code 0}.
   */
  public void setStatus(Integer status) {
    this.status = status;
  }

  /**
   * @return {@code true} when a SQL {@code 0/1} flag column is set.
   */
  public static boolean isFlagTrue(Integer flag) {
    return flag != null && flag != 0;
  }

  /**
   * Converts picker JOIN rows to {@link IdpNameStatus} values.
   *
   * @param rows Picker JOIN rows.
   * @return Status values.
   */
  public static List<IdpNameStatus> toStatuses(List<IdpNameStatusPO> rows) {
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    List<IdpNameStatus> result = new ArrayList<>(rows.size());
    for (IdpNameStatusPO row : rows) {
      result.add(new IdpNameStatus(row.getName(), isFlagTrue(row.getStatus())));
    }
    return result;
  }

  /**
   * Converts a metalake user JOIN row to an extended DTO.
   *
   * @param row User JOIN row.
   * @param metalakeName The metalake name.
   * @return Extended user DTO.
   */
  public static ExtendedUserDTO toExtendedUser(UserWithOrigin row, String metalakeName) {
    Namespace namespace = AuthorizationUtils.ofUserNamespace(metalakeName);
    return ExtendedUserDTO.from(
        POConverters.fromExtendedUserPO(row, namespace), row.inBuiltInIdp());
  }

  /**
   * Converts metalake user JOIN rows to extended DTOs.
   *
   * @param metalakeName The metalake name.
   * @param rows User JOIN rows.
   * @return Extended user DTOs.
   * @throws NoSuchMetalakeException If {@code rows} is null or empty.
   */
  public static ExtendedUserDTO[] toExtendedUsersByMetalake(
      String metalakeName, List<UserWithOrigin> rows) {
    return mapExtendedUsers(
        metalakeName,
        rows,
        () -> new NoSuchMetalakeException("Metalake %s does not exist", metalakeName),
        UserWithOrigin::getUserId);
  }

  /**
   * Converts a metalake group JOIN row to an extended DTO.
   *
   * @param row Group JOIN row.
   * @param metalakeName The metalake name.
   * @return Extended group DTO.
   */
  public static ExtendedGroupDTO toExtendedGroup(GroupWithOrigin row, String metalakeName) {
    Namespace namespace = AuthorizationUtils.ofGroupNamespace(metalakeName);
    return ExtendedGroupDTO.from(
        POConverters.fromExtendedGroupPO(row, namespace), row.inBuiltInIdp(), row.userCount());
  }

  /**
   * Converts metalake group JOIN rows to extended DTOs.
   *
   * @param metalakeName The metalake name.
   * @param rows Group JOIN rows.
   * @return Extended group DTOs.
   * @throws NoSuchMetalakeException If {@code rows} is null or empty.
   */
  public static ExtendedGroupDTO[] toExtendedGroupsByMetalake(
      String metalakeName, List<GroupWithOrigin> rows) {
    return mapExtendedGroups(
        metalakeName,
        rows,
        () -> new NoSuchMetalakeException("Metalake %s does not exist", metalakeName),
        GroupWithOrigin::getGroupId);
  }

  /**
   * Converts membership JOIN rows to extended group DTOs for a metalake user.
   *
   * @param metalakeName The metalake name.
   * @param userName The username.
   * @param rows Membership JOIN rows.
   * @return Extended group DTOs.
   * @throws NoSuchUserException If the metalake user does not exist.
   */
  public static ExtendedGroupDTO[] toExtendedGroupsForMetalakeUser(
      String metalakeName, String userName, List<GroupWithOrigin> rows) {
    return mapExtendedGroups(
        metalakeName,
        rows,
        () ->
            new NoSuchUserException(
                "User %s does not exist in the metalake %s", userName, metalakeName),
        GroupWithOrigin::getGroupId);
  }

  /**
   * Converts membership JOIN rows to extended user DTOs for a metalake group.
   *
   * @param metalakeName The metalake name.
   * @param groupName The group name.
   * @param rows Membership JOIN rows.
   * @return Extended user DTOs.
   * @throws NoSuchGroupException If the metalake group does not exist.
   */
  public static ExtendedUserDTO[] toExtendedUsersForMetalakeGroup(
      String metalakeName, String groupName, List<UserWithOrigin> rows) {
    return mapExtendedUsers(
        metalakeName,
        rows,
        () ->
            new NoSuchGroupException(
                "Group %s does not exist in the metalake %s", groupName, metalakeName),
        UserWithOrigin::getUserId);
  }

  private static ExtendedUserDTO[] mapExtendedUsers(
      String metalakeName,
      List<UserWithOrigin> rows,
      Supplier<? extends RuntimeException> ifMissing,
      Function<UserWithOrigin, Long> idGetter) {
    if (rows == null || rows.isEmpty()) {
      throw ifMissing.get();
    }
    if (rows.size() == 1 && idGetter.apply(rows.get(0)) == null) {
      return new ExtendedUserDTO[0];
    }
    ExtendedUserDTO[] users = new ExtendedUserDTO[rows.size()];
    for (int i = 0; i < rows.size(); i++) {
      users[i] = toExtendedUser(rows.get(i), metalakeName);
    }
    return users;
  }

  private static ExtendedGroupDTO[] mapExtendedGroups(
      String metalakeName,
      List<GroupWithOrigin> rows,
      Supplier<? extends RuntimeException> ifMissing,
      Function<GroupWithOrigin, Long> idGetter) {
    if (rows == null || rows.isEmpty()) {
      throw ifMissing.get();
    }
    if (rows.size() == 1 && idGetter.apply(rows.get(0)) == null) {
      return new ExtendedGroupDTO[0];
    }
    ExtendedGroupDTO[] groups = new ExtendedGroupDTO[rows.size()];
    for (int i = 0; i < rows.size(); i++) {
      groups[i] = toExtendedGroup(rows.get(i), metalakeName);
    }
    return groups;
  }

  /** MyBatis row for metalake user enabled aggregate query. */
  public static class UserEnabledCountsRow {

    private Long total;
    private Long active;
    private Long suspended;

    public Long getTotal() {
      return total;
    }

    public void setTotal(Long total) {
      this.total = total;
    }

    public Long getActive() {
      return active;
    }

    public void setActive(Long active) {
      this.active = active;
    }

    public Long getSuspended() {
      return suspended;
    }

    public void setSuspended(Long suspended) {
      this.suspended = suspended;
    }
  }

  /** MyBatis row for metalake group membership aggregate query. */
  public static class GroupMembershipCountsRow {

    private Long total;
    private Long empty;

    public Long getTotal() {
      return total;
    }

    public void setTotal(Long total) {
      this.total = total;
    }

    public Long getEmpty() {
      return empty;
    }

    public void setEmpty(Long empty) {
      this.empty = empty;
    }
  }

  /** Metalake user row plus whether the name exists in {@code idp_user_meta}. */
  public static final class UserWithOrigin extends ExtendedUserPO {

    private Integer inBuiltInIdp;

    /**
     * @return {@code 1} when the name exists in {@code idp_user_meta}, otherwise {@code 0}.
     */
    public Integer getInBuiltInIdp() {
      return inBuiltInIdp;
    }

    /**
     * @param inBuiltInIdp {@code 1} when the name exists in {@code idp_user_meta}.
     */
    public void setInBuiltInIdp(Integer inBuiltInIdp) {
      this.inBuiltInIdp = inBuiltInIdp;
    }

    /**
     * @return {@code true} when the name exists in {@code idp_user_meta}.
     */
    public boolean inBuiltInIdp() {
      return isFlagTrue(inBuiltInIdp);
    }
  }

  /** Metalake group row plus whether the name exists in {@code idp_group_meta}. */
  public static final class GroupWithOrigin extends ExtendedGroupPO {

    private Integer inBuiltInIdp;
    private Integer userCount;

    /**
     * @return {@code 1} when the name exists in {@code idp_group_meta}, otherwise {@code 0}.
     */
    public Integer getInBuiltInIdp() {
      return inBuiltInIdp;
    }

    /**
     * @param inBuiltInIdp {@code 1} when the name exists in {@code idp_group_meta}.
     */
    public void setInBuiltInIdp(Integer inBuiltInIdp) {
      this.inBuiltInIdp = inBuiltInIdp;
    }

    /**
     * @return {@code true} when the name exists in {@code idp_group_meta}.
     */
    public boolean inBuiltInIdp() {
      return isFlagTrue(inBuiltInIdp);
    }

    /**
     * @return Number of metalake users in the group.
     */
    public Integer getUserCount() {
      return userCount;
    }

    /**
     * @param userCount Number of metalake users in the group.
     */
    public void setUserCount(Integer userCount) {
      this.userCount = userCount;
    }

    /**
     * @return Number of metalake users in the group, or {@code 0} when unset.
     */
    public int userCount() {
      return userCount == null ? 0 : userCount;
    }
  }
}
