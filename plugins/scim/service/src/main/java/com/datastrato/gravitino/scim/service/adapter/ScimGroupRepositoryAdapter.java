/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.service.adapter;

import com.datastrato.gravitino.scim.ScimGroupManager;
import com.datastrato.gravitino.scim.ScimUserGroupRelManager;
import com.datastrato.gravitino.scim.ScimUtils;
import com.datastrato.gravitino.scim.model.ScimGroupMeta;
import com.datastrato.gravitino.scim.service.ScimConfig;
import com.datastrato.gravitino.scim.service.basic.mapper.ScimNameMappers;
import com.datastrato.gravitino.scim.service.converter.ScimResourceConverter;
import com.datastrato.gravitino.scim.service.filter.ScimGroupFilter;
import com.datastrato.gravitino.scim.service.model.ScimPagedResult;
import com.datastrato.gravitino.scim.storage.po.ScimGroupMemberPO;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.apache.gravitino.authorization.PagedResult;
import org.apache.gravitino.exceptions.AlreadyExistsException;
import org.apache.gravitino.exceptions.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** SCIMple repository adapter for Group provisioning backed by {@code scim_group_meta}. */
public class ScimGroupRepositoryAdapter implements Repository<ScimGroup> {

  private static final Logger LOG = LoggerFactory.getLogger(ScimGroupRepositoryAdapter.class);

  private final ScimGroupManager groupManager;
  private final ScimUserGroupRelManager membershipManager;
  private final ScimConfig scimConfig;

  public ScimGroupRepositoryAdapter(Config gravitinoConfig, ScimConfig scimConfig) {
    this(ScimGroupManager.getInstance(), ScimUserGroupRelManager.getInstance(), scimConfig);
  }

  ScimGroupRepositoryAdapter(
      ScimGroupManager groupManager,
      ScimUserGroupRelManager membershipManager,
      ScimConfig scimConfig) {
    this.groupManager = groupManager;
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
      ScimGroupMeta group = groupManager.createGroup(groupName, externalId);
      List<GroupMembership> members = resource.getMembers();
      if (members != null && !members.isEmpty()) {
        replaceGroupMembers(group, members);
      }
      return toScimGroup(group);
    } catch (ResourceException e) {
      throw e;
    } catch (AlreadyExistsException e) {
      throw new ResourceException(409, "Group already exists: displayName=" + groupName, e);
    } catch (Exception e) {
      throw new ResourceException(500, "Failed to create group", e);
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
    ScimGroupMeta group = requireGroup(id);
    validateImmutableGroupIdentity(group, id, resource);
    validateImmutableDisplayName(group, resource.getDisplayName());
    replaceGroupMembers(group, resource.getMembers());
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
    ScimGroupMeta group = requireGroup(id);
    for (PatchOperation operation : patchOperations) {
      for (ScimPatchSupport.GroupPatchOperation parsed :
          ScimPatchSupport.parseGroupPatches(operation)) {
        switch (parsed.kind()) {
          case MEMBERS:
            applyMembersPatch(
                group,
                operation.getOperation(),
                parsed.members(),
                parsed.membersFromPathFilter(),
                parsed.replacementMembers());
            break;
          case EXTERNAL_ID:
            throw new ResourceException(400, "Group PATCH supports members only");
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
      return toScimGroup(groupManager.getGroup(parseResourceId(id)));
    } catch (NotFoundException e) {
      throw new ResourceException(404, "Group not found: " + id);
    } catch (IllegalArgumentException e) {
      throw new ResourceException(404, "Group not found: " + id);
    }
  }

  @Override
  public FilterResponse<ScimGroup> find(
      Filter filter, PageRequest pageRequest, SortRequest sortRequest) throws ResourceException {
    ScimGroupFilter criteria = ScimGroupFilter.convert(filter, scimConfig);
    ScimRepositoryPagination.PageBounds page =
        ScimRepositoryPagination.normalizePage(pageRequest.getStartIndex(), pageRequest.getCount());
    ScimPagedResult<ScimGroupMeta> result = findGroups(criteria, page);
    Map<Long, List<String>> memberIdsByGroup =
        listMemberUserIdsByGroups(
            result.items().stream().map(ScimGroupMeta::getGroupId).collect(Collectors.toList()));
    List<ScimGroup> resources =
        result.items().stream()
            .map(
                group ->
                    ScimResourceConverter.toScimGroup(
                        group, memberIdsByGroup.getOrDefault(group.getGroupId(), List.of())))
            .collect(Collectors.toList());
    return new FilterResponse<>(
        resources,
        new PageRequest().setStartIndex(page.startIndex()).setCount(page.limit()),
        (int) result.totalCount());
  }

  @Override
  public void delete(String id) throws ResourceException {
    try {
      if (!groupManager.deleteGroup(parseResourceId(id))) {
        throw new ResourceException(404, "Group not found: " + id);
      }
    } catch (IllegalArgumentException e) {
      throw new ResourceException(404, "Group not found: " + id);
    }
  }

  @Override
  public List<Class<? extends ScimExtension>> getExtensionList() {
    return List.of();
  }

  private void applyMembersPatch(
      ScimGroupMeta group,
      PatchOperation.Type type,
      List<GroupMembership> members,
      boolean fromPathFilter,
      List<GroupMembership> replacementMembers)
      throws ResourceException {
    switch (type) {
      case ADD:
        addGroupMembers(group, members);
        break;
      case REMOVE:
        removeGroupMembers(group, members);
        break;
      case REPLACE:
        if (fromPathFilter) {
          replaceFilteredMember(group, members, replacementMembers);
        } else {
          replaceGroupMembers(group, members);
        }
        break;
      default:
        throw new ResourceException(400, "Unsupported PATCH operation: " + type);
    }
  }

  private void replaceFilteredMember(
      ScimGroupMeta group,
      List<GroupMembership> matchedMembers,
      List<GroupMembership> replacementMembers)
      throws ResourceException {
    List<Long> oldUserIds = extractMemberUserIds(matchedMembers);
    List<Long> newUserIds = extractMemberUserIds(replacementMembers);
    if (oldUserIds.size() != 1 || newUserIds.size() != 1) {
      throw new ResourceException(400, "members[value eq ...] REPLACE requires exactly one member");
    }
    long oldUserId = oldUserIds.get(0);
    long newUserId = newUserIds.get(0);
    if (oldUserId == newUserId) {
      return;
    }
    try {
      if (!membershipManager.replaceMemberUserInGroup(group.getGroupId(), oldUserId, newUserId)) {
        throw new ResourceException(404, "Unable to replace group member");
      }
    } catch (ResourceException e) {
      throw e;
    } catch (Exception e) {
      throw new ResourceException(500, "Failed to replace group member", e);
    }
  }

  private void applyDisplayNamePatch(
      ScimGroupMeta group, PatchOperation.Type type, String displayName) throws ResourceException {
    if (type != PatchOperation.Type.REPLACE && type != PatchOperation.Type.ADD) {
      throw new ResourceException(400, "Group displayName PATCH supports add/replace only");
    }
    if (StringUtils.isBlank(displayName)) {
      throw new ResourceException(400, "displayName PATCH value must be a non-blank string");
    }
    String resolvedName = ScimNameMappers.mapGroupName(scimConfig.groupMapper(), displayName);
    if (!resolvedName.equalsIgnoreCase(group.getGroupName())) {
      throw new ResourceException(400, "Group displayName is immutable");
    }
  }

  private void addGroupMembers(ScimGroupMeta group, List<GroupMembership> members)
      throws ResourceException {
    List<Long> userIds = extractMemberUserIds(members);
    if (userIds.isEmpty()) {
      return;
    }
    try {
      membershipManager.addUsersToGroup(group.getGroupId(), userIds);
    } catch (Exception e) {
      throw new ResourceException(500, "Failed to add users to group", e);
    }
  }

  private void removeGroupMembers(ScimGroupMeta group, List<GroupMembership> members) {
    List<Long> userIds = extractMemberUserIds(members);
    if (userIds.isEmpty()) {
      return;
    }
    membershipManager.removeUsersFromGroup(group.getGroupId(), userIds);
  }

  private void replaceGroupMembers(ScimGroupMeta group, List<GroupMembership> members)
      throws ResourceException {
    List<Long> userIds = extractMemberUserIds(members);
    try {
      membershipManager.replaceUsersInGroup(group.getGroupId(), userIds);
    } catch (Exception e) {
      throw new ResourceException(500, "Failed to replace users in group", e);
    }
  }

  private List<String> listMemberUserIds(long groupId) {
    return toSortedUserIds(membershipManager.listMembersForGroup(groupId));
  }

  private Map<Long, List<String>> listMemberUserIdsByGroups(List<Long> groupIds) {
    if (groupIds == null || groupIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, List<String>> memberIdsByGroup = new LinkedHashMap<>();
    for (Map.Entry<Long, List<ScimGroupMemberPO>> entry :
        membershipManager.listMembersForGroups(groupIds).entrySet()) {
      memberIdsByGroup.put(entry.getKey(), toSortedUserIds(entry.getValue()));
    }
    return memberIdsByGroup;
  }

  private List<String> toSortedUserIds(List<ScimGroupMemberPO> members) {
    List<String> memberIds = new ArrayList<>();
    for (ScimGroupMemberPO member : members) {
      memberIds.add(String.valueOf(member.getUserId()));
    }
    memberIds.sort(String::compareTo);
    return memberIds;
  }

  private ScimPagedResult<ScimGroupMeta> findGroups(
      ScimGroupFilter criteria, ScimRepositoryPagination.PageBounds page) {
    if (!criteria.hasPredicates()) {
      PagedResult<ScimGroupMeta> pageResult = groupManager.listGroups(page.offset(), page.limit());
      return new ScimPagedResult<>(pageResult.totalCount(), pageResult.items());
    }
    return lookupGroup(criteria)
        .filter(group -> matchesFilter(group, criteria))
        .map(group -> singleMatchResult(group, page))
        .orElseGet(() -> new ScimPagedResult<>(0, List.of()));
  }

  private static ScimPagedResult<ScimGroupMeta> singleMatchResult(
      ScimGroupMeta group, ScimRepositoryPagination.PageBounds page) {
    if (page.limit() == 0) {
      return new ScimPagedResult<>(1, List.of());
    }
    return new ScimPagedResult<>(1, List.of(group));
  }

  private Optional<ScimGroupMeta> lookupGroup(ScimGroupFilter criteria) {
    try {
      if (criteria.id().isPresent()) {
        return Optional.of(groupManager.getGroup(parseResourceId(criteria.id().get())));
      }
      if (criteria.externalId().isPresent()) {
        return Optional.of(groupManager.getGroupByExternalId(criteria.externalId().get()));
      }
      if (criteria.displayName().isPresent()) {
        return findGroupIgnoreCase(criteria.displayName().get());
      }
    } catch (NotFoundException | IllegalArgumentException ignored) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private Optional<ScimGroupMeta> findGroupIgnoreCase(String displayName) {
    return Optional.ofNullable(groupManager.findGroupByGroupNameIgnoreCase(displayName));
  }

  private static boolean matchesFilter(ScimGroupMeta group, ScimGroupFilter criteria) {
    if (criteria.id().isPresent()
        && !criteria.id().get().equals(ScimResourceConverter.toResourceId(group.getGroupId()))) {
      return false;
    }
    if (criteria.displayName().isPresent()
        && !criteria.displayName().get().equalsIgnoreCase(group.getGroupName())) {
      return false;
    }
    if (criteria.externalId().isPresent()
        && !Objects.equals(criteria.externalId().get(), group.getExternalId())) {
      return false;
    }
    return true;
  }

  private List<Long> extractMemberUserIds(List<GroupMembership> members) {
    if (members == null || members.isEmpty()) {
      return List.of();
    }
    List<Long> userIds = new ArrayList<>();
    for (GroupMembership membership : members) {
      String value = membership.getValue();
      if (StringUtils.isBlank(value)) {
        continue;
      }
      try {
        userIds.add(Long.parseLong(value));
      } catch (NumberFormatException e) {
        LOG.warn("Skipping invalid SCIM member value {}", value);
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

  private ScimGroupMeta requireGroup(String id) throws ResourceException {
    try {
      return groupManager.getGroup(parseResourceId(id));
    } catch (NotFoundException | IllegalArgumentException e) {
      throw new ResourceException(404, "Group not found: " + id);
    }
  }

  private static void validateImmutableGroupIdentity(
      ScimGroupMeta group, String pathId, ScimGroup resource) throws ResourceException {
    if (StringUtils.isNotBlank(resource.getId()) && !pathId.equals(resource.getId())) {
      throw new ResourceException(400, "Group id is immutable");
    }
    if (StringUtils.isNotBlank(resource.getExternalId())
        && !Objects.equals(resource.getExternalId(), group.getExternalId())) {
      throw new ResourceException(400, "Group externalId is immutable");
    }
  }

  private void validateImmutableDisplayName(ScimGroupMeta group, String rawDisplayName)
      throws ResourceException {
    if (StringUtils.isBlank(rawDisplayName)) {
      return;
    }
    String resolvedName = ScimNameMappers.mapGroupName(scimConfig.groupMapper(), rawDisplayName);
    if (!resolvedName.equalsIgnoreCase(group.getGroupName())) {
      throw new ResourceException(400, "Group displayName is immutable");
    }
  }

  private ScimGroup toScimGroup(ScimGroupMeta group) {
    return ScimResourceConverter.toScimGroup(group, listMemberUserIds(group.getGroupId()));
  }

  static long parseResourceId(String id) {
    if (StringUtils.isBlank(id)) {
      throw new IllegalArgumentException("Invalid SCIM group id: " + id);
    }
    try {
      return Long.parseLong(id);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid SCIM group id: " + id, e);
    }
  }
}
