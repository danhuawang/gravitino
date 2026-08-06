/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.adapter;

import com.datastrato.gravitino.scim.ScimUserGroupRelManager;
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
    String externalId = normalizeExternalId(resource.getExternalId());
    String groupName = resolveGroupName(resource.getDisplayName());
    try {
      String metalake = ScimMetalakeContext.getMetalake();
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

    for (PatchOperation operation : patchOperations) {
      List<GroupMembership> members = ScimPatchSupport.parseGroupMembers(operation);
      switch (operation.getOperation()) {
        case ADD:
          addGroupMembers(ScimMetalakeContext.getMetalake(), group.id(), members);
          break;
        case REMOVE:
          removeGroupMembers(ScimMetalakeContext.getMetalake(), group.id(), members);
          break;
        case REPLACE:
          replaceGroupMembers(ScimMetalakeContext.getMetalake(), group.id(), members);
          break;
        default:
          throw new ResourceException(
              400, "Unsupported PATCH operation: " + operation.getOperation());
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
    ScimPagedResult<Group> result = findGroups(ScimMetalakeContext.getMetalake(), criteria);
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
   * Resolves a filtered SCIM list query.
   *
   * <p>Gravitino core exposes point lookups only ({@code getGroupByExternalId} / {@code getGroup});
   * there is no paginated group-list API yet. Supported {@code eq} / {@code and} filters therefore
   * map to at most one group via a primary-key lookup plus optional cross-field validation.
   */
  private ScimPagedResult<Group> findGroups(String metalake, ScimGroupFilter criteria) {
    if (!criteria.hasPredicates()) {
      return new ScimPagedResult<>(0, List.of());
    }
    return lookupGroup(metalake, criteria)
        .filter(group -> matchesFilter(group, criteria))
        .map(group -> new ScimPagedResult<>(1, List.of(group)))
        .orElseGet(() -> new ScimPagedResult<>(0, List.of()));
  }

  private Optional<Group> lookupGroup(String metalake, ScimGroupFilter criteria) {
    try {
      if (criteria.externalId().isPresent()) {
        return Optional.of(dispatcher.getGroupByExternalId(metalake, criteria.externalId().get()));
      }
      if (criteria.displayName().isPresent()) {
        return Optional.of(dispatcher.getGroup(metalake, criteria.displayName().get()));
      }
    } catch (NoSuchGroupException ignored) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private static boolean matchesFilter(Group group, ScimGroupFilter criteria) {
    if (criteria.displayName().isPresent() && !criteria.displayName().get().equals(group.name())) {
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
   */
  private void validateImmutableDisplayName(Group group, String rawDisplayName)
      throws ResourceException {
    if (StringUtils.isBlank(rawDisplayName)) {
      return;
    }
    String resolvedName = ScimNameMappers.mapGroupName(scimConfig.groupMapper(), rawDisplayName);
    if (!resolvedName.equals(group.name())) {
      throw new ResourceException(400, "Group displayName is immutable");
    }
  }

  private ScimGroup toScimGroup(Group group) {
    List<String> memberIds = listMemberScimIds(ScimMetalakeContext.getMetalake(), group.id());
    return ScimResourceConverter.toScimGroup(group, memberIds);
  }

  private static String normalizeExternalId(String externalId) {
    return StringUtils.isBlank(externalId) ? null : externalId;
  }

  private static long parseResourceId(String id) throws ResourceException {
    try {
      return Long.parseLong(id);
    } catch (NumberFormatException e) {
      throw new ResourceException(404, "Group not found: " + id);
    }
  }
}
