/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.service;

import com.datastrato.gravitino.scim.ScimFieldLimits;
import com.datastrato.gravitino.scim.storage.mapper.JitIdentityMapper;
import com.datastrato.gravitino.scim.storage.po.JitGroupPO;
import com.datastrato.gravitino.scim.storage.po.JitUserGroupRelPO;
import com.datastrato.gravitino.scim.storage.po.JitUserPO;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.storage.relational.utils.POConverters;
import org.apache.gravitino.storage.relational.utils.SessionUtils;

/** Persists global JWT-discovered users, groups, and membership snapshots. */
public class JitIdentityMetaService {
  private static final JitIdentityMetaService INSTANCE = new JitIdentityMetaService();

  private JitIdentityMetaService() {}

  /** Returns the singleton JIT identity metadata service. */
  public static JitIdentityMetaService getInstance() {
    return INSTANCE;
  }

  /**
   * Reconciles one validated JWT identity with the global JIT tables.
   *
   * <p>SCIM has precedence. If the user is provisioned by SCIM, its JIT row and memberships are
   * retired. Groups already present in SCIM are excluded and any same-name JIT rows are retired.
   * For the remaining groups, the relation set is replaced with the latest JWT snapshot.
   *
   * @param userName mapped username
   * @param groupNames mapped JWT group names
   * @param observedAt validation time in epoch milliseconds
   * @param auditInfo serialized create audit information
   * @param idGenerator global id generator
   * @return group names that remain JIT-owned after SCIM reconciliation
   */
  public List<String> synchronize(
      String userName,
      List<String> groupNames,
      long observedAt,
      String auditInfo,
      IdGenerator idGenerator) {
    String trimmedUserName = StringUtils.trimToNull(userName);
    if (trimmedUserName == null) {
      throw new IllegalArgumentException("The userName of the JIT identity must not be blank");
    }
    if (!trimmedUserName.equals(userName)) {
      throw new IllegalArgumentException(
          "The userName of the JIT identity must not have leading or trailing whitespace");
    }
    ScimFieldLimits.checkJitName(userName, "userName");
    List<String> normalizedGroups = normalizeGroupNames(groupNames);
    normalizedGroups.forEach(groupName -> ScimFieldLimits.checkJitName(groupName, "groupName"));
    return SessionUtils.doWithCommitAndFetchResult(
        JitIdentityMapper.class,
        mapper ->
            synchronize(mapper, userName, normalizedGroups, observedAt, auditInfo, idGenerator));
  }

  /** Physically deletes soft-deleted JIT memberships older than the retention timeline. */
  public int deleteMembershipsByLegacyTimeline(long legacyTimeline, int limit) {
    Integer deleted =
        SessionUtils.doWithCommitAndFetchResult(
            JitIdentityMapper.class,
            mapper -> mapper.deleteMembershipsByLegacyTimeline(legacyTimeline, limit));
    return deleted == null ? 0 : deleted;
  }

  /** Physically deletes soft-deleted JIT users older than the retention timeline. */
  public int deleteUsersByLegacyTimeline(long legacyTimeline, int limit) {
    Integer deleted =
        SessionUtils.doWithCommitAndFetchResult(
            JitIdentityMapper.class,
            mapper -> mapper.deleteUsersByLegacyTimeline(legacyTimeline, limit));
    return deleted == null ? 0 : deleted;
  }

  /** Physically deletes soft-deleted JIT groups older than the retention timeline. */
  public int deleteGroupsByLegacyTimeline(long legacyTimeline, int limit) {
    Integer deleted =
        SessionUtils.doWithCommitAndFetchResult(
            JitIdentityMapper.class,
            mapper -> mapper.deleteGroupsByLegacyTimeline(legacyTimeline, limit));
    return deleted == null ? 0 : deleted;
  }

  private List<String> synchronize(
      JitIdentityMapper mapper,
      String userName,
      List<String> groupNames,
      long observedAt,
      String auditInfo,
      IdGenerator idGenerator) {
    if (mapper.selectScimUserIdByName(userName) != null) {
      mapper.softDeleteMembershipsByUserName(userName, observedAt);
      mapper.softDeleteUserByName(userName, observedAt);
      return List.of();
    }

    if (mapper.selectUserByName(userName) == null) {
      mapper.insertUser(
          JitUserPO.builder()
              .withUserId(idGenerator.nextId())
              .withUserName(userName)
              .withAuditInfo(auditInfo)
              .withCurrentVersion(POConverters.INIT_VERSION)
              .withLastVersion(POConverters.INIT_VERSION)
              .withDeletedAt(0L)
              .build());
    }
    JitUserPO user = mapper.selectUserByName(userName);

    Set<String> scimGroupNames =
        groupNames.isEmpty()
            ? Set.of()
            : mapper.selectScimGroupNamesByNames(groupNames).stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    List<String> jitGroupNames =
        groupNames.stream()
            .filter(name -> !scimGroupNames.contains(name.toLowerCase(Locale.ROOT)))
            .toList();

    if (!scimGroupNames.isEmpty()) {
      List<String> names = new ArrayList<>(scimGroupNames);
      mapper.softDeleteMembershipsByGroupNames(names, observedAt);
      mapper.softDeleteGroupsByNames(names, observedAt);
    }

    Map<String, JitGroupPO> existingGroups =
        jitGroupNames.isEmpty()
            ? Map.of()
            : mapper.selectGroupsByNames(jitGroupNames).stream()
                .collect(
                    Collectors.toMap(
                        group -> group.getGroupName().toLowerCase(Locale.ROOT),
                        Function.identity()));
    if (!jitGroupNames.isEmpty()) {
      List<JitGroupPO> newGroups =
          jitGroupNames.stream()
              .filter(name -> !existingGroups.containsKey(name.toLowerCase(Locale.ROOT)))
              .map(
                  name ->
                      JitGroupPO.builder()
                          .withGroupId(idGenerator.nextId())
                          .withGroupName(name)
                          .withAuditInfo(auditInfo)
                          .withCurrentVersion(POConverters.INIT_VERSION)
                          .withLastVersion(POConverters.INIT_VERSION)
                          .withDeletedAt(0L)
                          .build())
              .toList();
      if (!newGroups.isEmpty()) {
        mapper.insertGroups(newGroups);
      }
    }

    List<Long> desiredGroupIds =
        jitGroupNames.isEmpty()
            ? List.of()
            : mapper.selectGroupsByNames(jitGroupNames).stream()
                .map(JitGroupPO::getGroupId)
                .toList();
    mapper.softDeleteMissingMemberships(user.getUserId(), desiredGroupIds, observedAt);
    if (!desiredGroupIds.isEmpty()) {
      Set<Long> existingGroupIds = new HashSet<>(mapper.selectGroupIdsForUser(user.getUserId()));
      List<JitUserGroupRelPO> newRelations =
          desiredGroupIds.stream()
              .filter(groupId -> !existingGroupIds.contains(groupId))
              .map(
                  groupId ->
                      JitUserGroupRelPO.builder()
                          .withId(idGenerator.nextId())
                          .withUserId(user.getUserId())
                          .withGroupId(groupId)
                          .withAuditInfo(auditInfo)
                          .withCurrentVersion(POConverters.INIT_VERSION)
                          .withLastVersion(POConverters.INIT_VERSION)
                          .withDeletedAt(0L)
                          .build())
              .toList();
      if (!newRelations.isEmpty()) {
        mapper.insertMemberships(newRelations);
      }
    }
    return jitGroupNames;
  }

  private static List<String> normalizeGroupNames(List<String> groupNames) {
    if (groupNames == null || groupNames.isEmpty()) {
      return List.of();
    }
    Set<String> normalized = new LinkedHashSet<>();
    for (String groupName : groupNames) {
      String trimmed = StringUtils.trimToNull(groupName);
      if (trimmed != null) {
        normalized.add(trimmed);
      }
    }
    return List.copyOf(normalized);
  }
}
