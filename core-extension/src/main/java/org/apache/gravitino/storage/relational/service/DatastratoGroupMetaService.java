/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.storage.relational.service;

import static org.apache.gravitino.metrics.source.MetricsSource.GRAVITINO_RELATIONAL_STORE_METRIC_NAME;

import com.datastrato.gravitino.authorization.DirectoryGroup;
import com.datastrato.gravitino.authorization.mapper.DatastratoGroupMetaMapper;
import com.datastrato.gravitino.authorization.po.DirectoryGroupPO;
import com.datastrato.gravitino.authorization.po.IdpUserGroupRelInsertPO;
import com.datastrato.gravitino.authorization.po.IdpUserIdPO;
import com.datastrato.gravitino.authorization.utils.DatastratoPOConverters;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.exceptions.GroupAlreadyExistsException;
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
   * <p>Local groups come from {@code idp_group_meta}; Provisioned groups from {@code
   * scim_group_meta}; JIT groups from metalake {@code group_meta} only. When a group name exists in
   * an identity store and metalake tables, the identity-store row wins.
   *
   * @return Directory groups ordered by group name.
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

          mapper.insertIdpGroup(groupId, groupName, normalizedComment);
          if (!memberNames.isEmpty()) {
            List<IdpUserGroupRelInsertPO> relations = new ArrayList<>(memberNames.size());
            for (String member : memberNames) {
              relations.add(
                  new IdpUserGroupRelInsertPO(
                      RandomIdGenerator.INSTANCE.nextId(), userIdsByName.get(member), groupId));
            }
            mapper.batchInsertIdpUserGroupRels(relations);
          }
        });

    return new DirectoryGroup(groupName, memberNames.size(), IdentitySource.LOCAL, List.of());
  }
}
