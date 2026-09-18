/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.storage.relational.service;

import static org.apache.gravitino.metrics.source.MetricsSource.GRAVITINO_RELATIONAL_STORE_METRIC_NAME;

import com.datastrato.gravitino.authorization.DirectoryGroup;
import com.datastrato.gravitino.authorization.mapper.DatastratoGroupMetaMapper;
import com.datastrato.gravitino.authorization.po.DirectoryGroupDetailPO;
import com.datastrato.gravitino.authorization.po.DirectoryGroupPO;
import com.datastrato.gravitino.authorization.po.IdpUserGroupRelInsertPO;
import com.datastrato.gravitino.authorization.po.IdpUserIdPO;
import com.datastrato.gravitino.authorization.utils.DatastratoPOConverters;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.exceptions.GroupAlreadyExistsException;
import org.apache.gravitino.exceptions.NoSuchGroupException;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.RandomIdGenerator;
import org.apache.gravitino.storage.relational.utils.SessionUtils;

/**
 * Enterprise service for directory / group identity-store reads that do not fit metalake-scoped
 * EntityStore relation APIs.
 */
public class DatastratoGroupMetaService {
  private static final DatastratoGroupMetaService INSTANCE = new DatastratoGroupMetaService();

  private DatastratoGroupMetaService() {}

  /**
   * Gets the singleton instance.
   *
   * @return The singleton instance.
   */
  public static DatastratoGroupMetaService getInstance() {
    return INSTANCE;
  }

  /**
   * Lists identity-store groups for Configure → Directory → Groups.
   *
   * <p>Local from {@code idp_group_meta}, Provisioned from {@code scim_group_meta}, and JIT from
   * {@code jit_group_meta}, without merging. The same group name may appear more than once when it
   * exists in multiple stores. Metalake-only names (in {@code group_meta} but none of those stores)
   * are included with {@code origin} null and {@code memberCount} 0.
   *
   * @return Directory groups ordered by group name, then origin.
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listDirectoryGroups")
  public List<DirectoryGroup> listDirectoryGroups() {
    return SessionUtils.getWithoutCommit(
        DatastratoGroupMetaMapper.class,
        mapper -> {
          List<DirectoryGroupPO> groupPOs = mapper.listDirectoryGroups();
          return groupPOs.stream()
              .map(DatastratoPOConverters::fromDirectoryGroupPO)
              .collect(Collectors.toList());
        });
  }

  /**
   * Loads one Directory Group for Configure → Directory → Group Overview.
   *
   * <p>When {@code origin} is {@link IdentitySource#LOCAL} or {@link IdentitySource#PROVISIONED},
   * loads from the matching identity store. When {@code origin} is {@code null}, loads a
   * metalake-only name from {@code group_meta} (neither IdP nor SCIM); {@code memberCount} is 0 and
   * {@code createTime} is the earliest {@code audit_info.createTime} across all metalake rows for
   * that group name.
   *
   * @param groupName Group name.
   * @param origin Local, Provisioned, or {@code null} for metalake-only.
   * @return Directory group detail row.
   * @throws NoSuchGroupException If no matching group exists for that origin (or metalake-only).
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "getDirectoryGroup")
  public DirectoryGroupDetailPO getDirectoryGroup(
      String groupName, @javax.annotation.Nullable IdentitySource origin) {
    Preconditions.checkArgument(StringUtils.isNotBlank(groupName), "groupName cannot be blank");
    if (origin == null) {
      return getMetalakeOnlyDirectoryGroup(groupName);
    }
    if (origin != IdentitySource.LOCAL
        && origin != IdentitySource.PROVISIONED
        && origin != IdentitySource.JIT) {
      throw new IllegalArgumentException(
          "Directory group detail origin must be Local, Provisioned, or JIT, got: "
              + origin.value());
    }

    DirectoryGroupDetailPO row =
        SessionUtils.getWithoutCommit(
            DatastratoGroupMetaMapper.class,
            mapper -> mapper.getDirectoryGroup(groupName, origin.toOriginCode()));
    if (row == null) {
      throw new NoSuchGroupException(
          "Directory group does not exist: %s (%s)", groupName, origin.value());
    }
    return row;
  }

  private DirectoryGroupDetailPO getMetalakeOnlyDirectoryGroup(String groupName) {
    List<String> auditInfos =
        SessionUtils.getWithoutCommit(
            DatastratoGroupMetaMapper.class,
            mapper -> mapper.listMetalakeOnlyDirectoryGroupAuditInfos(groupName));
    if (auditInfos == null || auditInfos.isEmpty()) {
      throw new NoSuchGroupException(
          "Directory group does not exist: %s (metalake-only)", groupName);
    }
    DirectoryGroupDetailPO row = new DirectoryGroupDetailPO();
    row.setGroupName(groupName);
    row.setOriginCode(null);
    row.setMemberCount(0);
    row.setAuditInfo(DatastratoPOConverters.auditInfoWithEarliestCreateTime(auditInfos));
    return row;
  }

  /**
   * Lists Directory Groups for Configure → Directory → User → Groups.
   *
   * <p>When {@code origin} is Local or Provisioned, lists identity-store groups. When {@code
   * origin} is {@code null}, requires a metalake-only Directory User and returns an empty list
   * (metalake-only users have no identity-store groups).
   *
   * @param userName Username.
   * @param origin Local, Provisioned, or {@code null} for metalake-only.
   * @return Groups with member counts, ordered by name.
   * @throws org.apache.gravitino.exceptions.NoSuchUserException If the Directory User is missing
   *     for that origin.
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listDirectoryGroupsForUser")
  public List<DirectoryGroup> listDirectoryGroupsForUser(
      String userName, @javax.annotation.Nullable IdentitySource origin) {
    Preconditions.checkArgument(StringUtils.isNotBlank(userName), "userName cannot be blank");
    if (origin == null) {
      DatastratoUserMetaService.getInstance().getDirectoryUser(userName, null);
      return Collections.emptyList();
    }
    if (origin != IdentitySource.LOCAL
        && origin != IdentitySource.PROVISIONED
        && origin != IdentitySource.JIT) {
      throw new IllegalArgumentException(
          "Directory user groups origin must be Local, Provisioned, or JIT, got: "
              + origin.value());
    }

    // Ensure the Directory User exists for this origin (404 when missing).
    DatastratoUserMetaService.getInstance().getDirectoryUser(userName, origin);

    return SessionUtils.getWithoutCommit(
        DatastratoGroupMetaMapper.class,
        mapper -> {
          List<DirectoryGroupPO> groupPOs =
              mapper.listDirectoryGroupsForUser(userName, origin.toOriginCode());
          if (groupPOs == null || groupPOs.isEmpty()) {
            return Collections.emptyList();
          }
          return groupPOs.stream()
              .map(DatastratoPOConverters::fromDirectoryGroupPO)
              .collect(Collectors.toList());
        });
  }

  /**
   * Creates a Local Directory Group in {@code idp_group_meta} and adds membership rows in {@code
   * idp_user_group_rel}.
   *
   * <p>Validates that every member exists in {@code idp_user_meta} and that the group name is not
   * already present in {@code idp_group_meta}, then inserts the group and relations in one
   * transaction. Does not create metalake {@code group_meta} rows.
   *
   * @param groupName Group name to create.
   * @param comment Optional comment; {@code null} is stored as empty.
   * @param members Built-in IdP usernames to add; {@code null} or empty means none.
   * @return The created Directory Group (Local origin, empty metalakes).
   * @throws NotFoundException If any member is missing from {@code idp_user_meta}.
   * @throws GroupAlreadyExistsException If the group name already exists in {@code idp_group_meta}.
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "addDirectoryGroup")
  public DirectoryGroup addDirectoryGroup(String groupName, String comment, List<String> members) {
    Preconditions.checkArgument(StringUtils.isNotBlank(groupName), "group name cannot be blank");
    String normalizedComment = comment == null ? "" : comment;

    LinkedHashSet<String> distinctMembers = new LinkedHashSet<>();
    if (members != null) {
      for (String member : members) {
        Preconditions.checkArgument(StringUtils.isNotBlank(member), "member cannot be blank");
        distinctMembers.add(member);
      }
    }
    List<String> memberNames = new ArrayList<>(distinctMembers);
    long groupId = RandomIdGenerator.INSTANCE.nextId();

    SessionUtils.doWithCommit(
        DatastratoGroupMetaMapper.class,
        mapper -> {
          if (!mapper.selectIdpGroupNamesByNames(List.of(groupName)).isEmpty()) {
            throw new GroupAlreadyExistsException("IdP group already exists: %s", groupName);
          }

          Map<String, Long> userIdsByName = new HashMap<>();
          if (!memberNames.isEmpty()) {
            List<IdpUserIdPO> foundUsers = mapper.selectIdpUserIdsByNames(memberNames);
            for (IdpUserIdPO row : foundUsers) {
              userIdsByName.put(row.getUserName(), row.getUserId());
            }
            List<String> missing =
                memberNames.stream()
                    .filter(name -> !userIdsByName.containsKey(name))
                    .collect(Collectors.toList());
            if (!missing.isEmpty()) {
              throw new NotFoundException("IdP user not found: %s", missing);
            }
          }

          String auditInfo = DatastratoPOConverters.newCreateAuditInfoJson();
          mapper.insertIdpGroup(groupId, groupName, normalizedComment, auditInfo);
          if (!memberNames.isEmpty()) {
            List<IdpUserGroupRelInsertPO> relations = new ArrayList<>(memberNames.size());
            for (String member : memberNames) {
              relations.add(
                  new IdpUserGroupRelInsertPO(
                      RandomIdGenerator.INSTANCE.nextId(),
                      userIdsByName.get(member),
                      groupId,
                      auditInfo));
            }
            mapper.batchInsertIdpUserGroupRels(relations);
          }
        });

    return new DirectoryGroup(groupName, memberNames.size(), IdentitySource.LOCAL, List.of());
  }
}
