/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.adapter;

import com.datastrato.gravitino.scim.ScimUserGroupRelManager;
import com.datastrato.gravitino.scim.ScimUtils;
import com.datastrato.gravitino.scim.service.ScimConfig;
import com.datastrato.gravitino.scim.service.basic.mapper.ScimNameMappers;
import com.datastrato.gravitino.scim.service.converter.ScimResourceConverter;
import com.datastrato.gravitino.scim.service.filter.ScimGroupFilter;
import com.datastrato.gravitino.scim.service.model.ScimPagedResult;
import com.datastrato.gravitino.scim.service.web.ScimMetalakeContext;
import com.datastrato.gravitino.scim.storage.po.ScimGroupMemberPO;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.directory.scim.core.repository.Repository;
import org.apache.directory.scim.spec.exception.ResourceException;
import org.apache.directory.scim.spec.filter.Filter;
import org.apache.directory.scim.spec.filter.FilterResponse;
import org.apache.directory.scim.spec.filter.PageRequest;
import org.apache.directory.scim.spec.filter.SortRequest;
import org.apache.directory.scim.spec.filter.attribute.AttributeReference;
import org.apache.directory.scim.spec.patch.PatchOperation;
import org.apache.directory.scim.spec.resources.GroupMembership;
import org.apache.directory.scim.spec.resources.ScimExtension;
import org.apache.directory.scim.spec.resources.ScimGroup;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.authorization.GroupChange;
import org.apache.gravitino.authorization.PagedResult;
import org.apache.gravitino.exceptions.GroupAlreadyExistsException;
import org.apache.gravitino.exceptions.NoSuchGroupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** SCIMple repository adapter for Group provisioning backed by Gravitino core APIs. */
public class ScimGroupRepositoryAdapter implements Repository<ScimGroup> {

  private static final Logger LOG = LoggerFactory.getLogger(ScimGroupRepositoryAdapter.class);

  private final AccessControlDispatcher dispatcher;
  private final ScimUserGroupRelManager membershipManager;
  private final ScimConfig scimConfig;

  /**
   * Creates an adapter from server and SCIM configuration.
   *
   * @param gravitinoConfig server configuration
   * @param scimConfig SCIM mapper configuration
   */
  public ScimGroupRepositoryAdapter(Config gravitinoConfig, ScimConfig scimConfig) {
    this(
        GravitinoEnv.getInstance().accessControlDispatcher(),
        ScimUserGroupRelManager.getInstance(),
        scimConfig);
  }

  /**
   * Creates an adapter with explicit dispatcher and membership dependencies.
   *
   * @param dispatcher access control dispatcher
   * @param membershipManager user-group membership manager
   * @param scimConfig SCIM mapper configuration
   */
  ScimGroupRepositoryAdapter(
      AccessControlDispatcher dispatcher,
      ScimUserGroupRelManager membershipManager,
      ScimConfig scimConfig) {
    this.dispatcher = dispatcher;
    this.membershipManager = membershipManager;
    this.scimConfig = scimConfig;
  }

  @Override
  public Class<ScimGroup> getResourceClass() {
    return ScimGroup.class;
  }

  @Override
  public ScimGroup create(ScimGroup resource) throws ResourceException {
    String externalId = ScimUtils.blankToNull(resource.getExternalId());
    String groupName = resolveGroupName(resource.getDisplayName());
    try {
      String metalake = ScimMetalakeContext.getMetalake();
      // displayName is caseExact=false; treat case-only variants as the same unique name.
      if (findGroupIgnoreCase(metalake, groupName).isPresent()) {
        throw new ResourceException(
            409, "Group already exists: displayName=" + groupName + ", externalId=" + externalId);
      }
      Group group = dispatcher.addGroup(metalake, groupName, externalId);
      List<GroupMembership> members = resource.getMembers();
      if (members != null && !members.isEmpty()) {
        replaceGroupMembers(metalake, group.id(), members);
      }
      return toScimGroup(group);
    } catch (GroupAlreadyExistsException e) {
      throw new ResourceException(
          409, "Group already exists: displayName=" + groupName + ", externalId=" + externalId, e);
    }
  }

  @Override
  public ScimGroup update(
      String id,
      String version,
      ScimGroup resource,
      Set<AttributeReference> includedAttributes,
      Set<AttributeReference> excludedAttributes)
      throws ResourceException {
    String metalake = ScimMetalakeContext.getMetalake();
    long groupId = parseResourceId(id);
    Group group;
    try {
      group = dispatcher.getGroupById(metalake, groupId);
    } catch (NoSuchGroupException e) {
      throw new ResourceException(404, "Group not found: " + id);
    }

    validateImmutableGroupIdentity(group, id, resource);
    validateImmutableDisplayName(group, resource.getDisplayName());
    replaceGroupMembers(metalake, group.id(), resource.getMembers());
    return toScimGroup(group);
  }

  @Override
  public ScimGroup patch(
      String id,
      String version,
      List<PatchOperation> patchOperations,
      Set<AttributeReference> includedAttributes,
      Set<AttributeReference> excludedAttributes)
      throws ResourceException {
    Group group;
    try {
      group = dispatcher.getGroupById(ScimMetalakeContext.getMetalake(), parseResourceId(id));
    } catch (NoSuchGroupException e) {
      throw new ResourceException(404, "Group not found: " + id);
    }

    String metalake = ScimMetalakeContext.getMetalake();
    for (PatchOperation operation : patchOperations) {
      for (ScimPatchSupport.GroupPatchOperation parsed :
          ScimPatchSupport.parseGroupPatches(operation)) {
        switch (parsed.kind()) {
          case MEMBERS:
            applyMembersPatch(
                metalake,
                group.id(),
                operation.getOperation(),
                parsed.members(),
                parsed.membersFromPathFilter(),
                parsed.replacementMembers());
            break;
          case EXTERNAL_ID:
            group =
                applyExternalIdPatch(
                    metalake, group, operation.getOperation(), parsed.externalId());
            break;
          case DISPLAY_NAME:
            applyDisplayNamePatch(group, operation.getOperation(), parsed.displayName());
            break;
          default:
            throw new ResourceException(400, "Unsupported Group PATCH kind: " + parsed.kind());
        }
      }
    }
    return toScimGroup(group);
  }

  @Override
  public ScimGroup get(String id) throws ResourceException {
    try {
      Group group = dispatcher.getGroupById(ScimMetalakeContext.getMetalake(), parseResourceId(id));
      return toScimGroup(group);
    } catch (NoSuchGroupException e) {
      throw new ResourceException(404, "Group not found: " + id);
    }
  }

  @Override
  public FilterResponse<ScimGroup> find(
      Filter filter, PageRequest pageRequest, SortRequest sortRequest) throws ResourceException {
    ScimGroupFilter criteria = ScimGroupFilter.convert(filter, scimConfig);
    ScimRepositoryPagination.PageBounds page =
        ScimRepositoryPagination.normalizePage(pageRequest.getStartIndex(), pageRequest.getCount());
    ScimPagedResult<Group> result = findGroups(ScimMetalakeContext.getMetalake(), criteria, page);
    List<ScimGroup> resources =
        result.items().stream().map(this::toScimGroup).collect(Collectors.toList());
    return new FilterResponse<>(
        resources,
        new PageRequest().setStartIndex(page.startIndex()).setCount(page.limit()),
        (int) result.totalCount());
  }

  @Override
  public void delete(String id) throws ResourceException {
    boolean deleted =
        dispatcher.removeGroupById(ScimMetalakeContext.getMetalake(), parseResourceId(id));
    if (!deleted) {
      throw new ResourceException(404, "Group not found: " + id);
    }
  }

  @Override
  public List<Class<? extends ScimExtension>> getExtensionList() {
    return List.of();
  }

  private void applyMembersPatch(
      String metalake,
      long groupId,
      PatchOperation.Type type,
      List<GroupMembership> members,
      boolean fromPathFilter,
      List<GroupMembership> replacementMembers)
      throws ResourceException {
    switch (type) {
      case ADD:
        addGroupMembers(metalake, groupId, members);
        break;
      case REMOVE:
        removeGroupMembers(metalake, groupId, members);
        break;
      case REPLACE:
        if (fromPathFilter) {
          replaceFilteredMember(metalake, groupId, members, replacementMembers);
        } else {
          replaceGroupMembers(metalake, groupId, members);
        }
        break;
      default:
        throw new ResourceException(400, "Unsupported PATCH operation: " + type);
    }
  }

  private void replaceFilteredMember(
      String metalake,
      long groupId,
      List<GroupMembership> matchedMembers,
      List<GroupMembership> replacementMembers)
      throws ResourceException {
    List<Long> oldUserIds = extractMemberUserIds(matchedMembers);
    List<Long> newUserIds = extractMemberUserIds(replacementMembers);
    if (oldUserIds.size() != 1) {
      throw new ResourceException(
          400, "members[value eq ...] REPLACE requires exactly one matched member");
    }
    if (newUserIds.size() != 1) {
      throw new ResourceException(
          400, "members[value eq ...] REPLACE requires a single replacement member value");
    }
    long oldUserId = oldUserIds.get(0);
    long newUserId = newUserIds.get(0);
    if (oldUserId == newUserId) {
      return;
    }
    try {
      boolean updated =
          membershipManager.replaceMemberUserInGroup(metalake, groupId, oldUserId, newUserId);
      if (!updated) {
        throw new ResourceException(
            404,
            "Unable to replace group member "
                + oldUserId
                + " with "
                + newUserId
                + ": old member missing, replacement user missing, or replacement already in group");
      }
    } catch (IOException e) {
      throw new ResourceException(
          500, "Failed to replace member " + oldUserId + " in group " + groupId, e);
    }
  }

  private Group applyExternalIdPatch(
      String metalake, Group group, PatchOperation.Type type, String externalId)
      throws ResourceException {
    if (type != PatchOperation.Type.REPLACE && type != PatchOperation.Type.ADD) {
      throw new ResourceException(400, "Group externalId PATCH supports add/replace only");
    }
    String normalized = ScimUtils.blankToNull(externalId);
    if (Objects.equals(normalized, group.externalId())) {
      return group;
    }
    try {
      return dispatcher.alterGroupById(
          metalake, group.id(), GroupChange.updateExternalId(normalized));
    } catch (NoSuchGroupException e) {
      throw new ResourceException(404, "Group not found: " + group.id(), e);
    }
  }

  private void applyDisplayNamePatch(Group group, PatchOperation.Type type, String displayName)
      throws ResourceException {
    if (type != PatchOperation.Type.REPLACE && type != PatchOperation.Type.ADD) {
      throw new ResourceException(400, "Group displayName PATCH supports add/replace only");
    }
    if (StringUtils.isBlank(displayName)) {
      throw new ResourceException(400, "displayName PATCH value must be a non-blank string");
    }
    String resolvedName = ScimNameMappers.mapGroupName(scimConfig.groupMapper(), displayName);
    // displayName is caseExact=false; case-only differences are a no-op, not a rename.
    if (!resolvedName.equalsIgnoreCase(group.name())) {
      throw new ResourceException(400, "Group displayName is immutable");
    }
  }

  private void addGroupMembers(String metalake, long groupId, List<GroupMembership> members)
      throws ResourceException {
    List<Long> userIds = extractMemberUserIds(members);
    if (userIds.isEmpty()) {
      return;
    }
    try {
      membershipManager.addUsersToGroup(metalake, groupId, userIds);
    } catch (IOException e) {
      throw new ResourceException(500, "Failed to add users to group " + groupId, e);
    }
  }

  private void removeGroupMembers(String metalake, long groupId, List<GroupMembership> members) {
    List<Long> userIds = extractMemberUserIds(members);
    if (userIds.isEmpty()) {
      return;
    }
    membershipManager.removeUsersFromGroup(metalake, groupId, userIds);
  }

  private void replaceGroupMembers(String metalake, long groupId, List<GroupMembership> members)
      throws ResourceException {
    List<Long> userIds = extractMemberUserIds(members);
    try {
      membershipManager.replaceUsersInGroup(metalake, groupId, userIds);
    } catch (IOException e) {
      throw new ResourceException(500, "Failed to replace users in group " + groupId, e);
    }
  }

  private List<String> listMemberScimIds(String metalake, long groupId) {
    List<String> memberIds = new ArrayList<>();
    for (ScimGroupMemberPO member : membershipManager.listMembersForGroup(metalake, groupId)) {
      if (member.getUserId() == null) {
        LOG.warn("Skipping group member without userId in group {}", groupId);
        continue;
      }
      memberIds.add(String.valueOf(member.getUserId()));
    }
    memberIds.sort(String::compareTo);
    return memberIds;
  }

  /**
   * Resolves a SCIM Group list/filter query.
   *
   * <p>Unfiltered queries use JDBC-backed {@code listGroups(metalake, offset, limit)}. Supported
   * {@code eq} / {@code and} filters map to at most one group via a primary-key lookup plus
   * optional cross-field validation. {@code displayName} matching is case-insensitive per SCIM
   * string comparison for caseExact=false attributes.
   */
  private ScimPagedResult<Group> findGroups(
      String metalake, ScimGroupFilter criteria, ScimRepositoryPagination.PageBounds page) {
    if (!criteria.hasPredicates()) {
      PagedResult<Group> pageResult = dispatcher.listGroups(metalake, page.offset(), page.limit());
      return new ScimPagedResult<>(pageResult.totalCount(), pageResult.items());
    }
    return lookupGroup(metalake, criteria)
        .filter(group -> matchesFilter(group, criteria))
        .map(group -> singleMatchResult(group, page))
        .orElseGet(() -> new ScimPagedResult<>(0, List.of()));
  }

  /** One filter match, respecting {@code count=0} (empty page, totalResults still 1). */
  private static ScimPagedResult<Group> singleMatchResult(
      Group group, ScimRepositoryPagination.PageBounds page) {
    if (page.limit() == 0) {
      return new ScimPagedResult<>(1, List.of());
    }
    return new ScimPagedResult<>(1, List.of(group));
  }

  private Optional<Group> lookupGroup(String metalake, ScimGroupFilter criteria) {
    try {
      if (criteria.externalId().isPresent()) {
        return Optional.of(dispatcher.getGroupByExternalId(metalake, criteria.externalId().get()));
      }
      if (criteria.displayName().isPresent()) {
        String displayName = criteria.displayName().get();
        try {
          return Optional.of(dispatcher.getGroup(metalake, displayName));
        } catch (NoSuchGroupException ignored) {
          return findGroupIgnoreCase(metalake, displayName);
        }
      }
    } catch (NoSuchGroupException ignored) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private Optional<Group> findGroupIgnoreCase(String metalake, String displayName) {
    for (Group group : dispatcher.listGroups(metalake)) {
      if (displayName.equalsIgnoreCase(group.name())) {
        return Optional.of(group);
      }
    }
    return Optional.empty();
  }

  private static boolean matchesFilter(Group group, ScimGroupFilter criteria) {
    if (criteria.displayName().isPresent()
        && !criteria.displayName().get().equalsIgnoreCase(group.name())) {
      return false;
    }
    if (criteria.externalId().isPresent()
        && !Objects.equals(criteria.externalId().get(), group.externalId())) {
      return false;
    }
    return true;
  }

  private static List<Long> extractMemberUserIds(List<GroupMembership> members) {
    if (members == null || members.isEmpty()) {
      return List.of();
    }
    List<Long> userIds = new ArrayList<>();
    for (GroupMembership membership : members) {
      if (StringUtils.isBlank(membership.getValue())) {
        continue;
      }
      try {
        userIds.add(Long.parseLong(membership.getValue()));
      } catch (NumberFormatException e) {
        LOG.warn("Skipping invalid SCIM member value {}", membership.getValue());
      }
    }
    return userIds;
  }

  private String resolveGroupName(String rawDisplayName) throws ResourceException {
    if (StringUtils.isBlank(rawDisplayName)) {
      throw new ResourceException(400, "displayName is required on Group create");
    }
    return ScimNameMappers.mapGroupName(scimConfig.groupMapper(), rawDisplayName);
  }

  /**
   * Rejects PUT attempts to change group identity. Gravitino treats SCIM {@code id} as the
   * immutable Gravitino-assigned id.
   */
  private static void validateImmutableGroupIdentity(Group group, String pathId, ScimGroup resource)
      throws ResourceException {
    if (StringUtils.isNotBlank(resource.getId()) && !pathId.equals(resource.getId())) {
      throw new ResourceException(400, "Group id is immutable");
    }
    if (StringUtils.isNotBlank(resource.getExternalId())
        && !Objects.equals(resource.getExternalId(), group.externalId())) {
      throw new ResourceException(400, "Group externalId is immutable");
    }
  }

  /**
   * Rejects displayName renames. Gravitino group names cannot be changed after create.
   *
   * <p>Blank displayName is treated as unchanged so clients that only replace members still work.
   * Group {@code displayName} is SCIM {@code caseExact=false}; case-only differences are a no-op.
   */
  private void validateImmutableDisplayName(Group group, String rawDisplayName)
      throws ResourceException {
    if (StringUtils.isBlank(rawDisplayName)) {
      return;
    }
    String resolvedName = ScimNameMappers.mapGroupName(scimConfig.groupMapper(), rawDisplayName);
    if (!resolvedName.equalsIgnoreCase(group.name())) {
      throw new ResourceException(400, "Group displayName is immutable");
    }
  }

  private ScimGroup toScimGroup(Group group) {
    List<String> memberIds = listMemberScimIds(ScimMetalakeContext.getMetalake(), group.id());
    return ScimResourceConverter.toScimGroup(group, memberIds);
  }

  private static long parseResourceId(String id) throws ResourceException {
    try {
      return Long.parseLong(id);
    } catch (NumberFormatException e) {
      throw new ResourceException(404, "Group not found: " + id);
    }
  }
}
