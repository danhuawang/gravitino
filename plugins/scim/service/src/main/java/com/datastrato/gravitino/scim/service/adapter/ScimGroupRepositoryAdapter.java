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
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.exceptions.GroupAlreadyExistsException;
import org.apache.gravitino.exceptions.NoSuchGroupException;
import org.apache.gravitino.exceptions.NoSuchUserException;
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
    String externalId = resource.getExternalId();
    if (StringUtils.isBlank(externalId)) {
      throw new ResourceException(400, "externalId is required on Group create");
    }
    try {
      Group group =
          createGroup(
              ScimMetalakeContext.getMetalake(),
              externalId,
              resource.getDisplayName(),
              resource.getMembers());
      return toScimGroup(group);
    } catch (GroupAlreadyExistsException e) {
      throw new ResourceException(409, "Group already exists: " + resource.getDisplayName(), e);
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
    throw new ResourceException(405, "PUT is not supported for Groups");
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
      group = getGroupByExternalId(ScimMetalakeContext.getMetalake(), id);
    } catch (NoSuchGroupException e) {
      throw new ResourceException(404, "Group not found: " + id);
    }

    for (PatchOperation operation : patchOperations) {
      List<GroupMembership> members = ScimPatchSupport.parseGroupMembers(operation);
      switch (operation.getOperation()) {
        case ADD:
          addGroupMembers(ScimMetalakeContext.getMetalake(), group.externalId(), members);
          break;
        case REMOVE:
          removeGroupMembers(ScimMetalakeContext.getMetalake(), group.externalId(), members);
          break;
        case REPLACE:
          replaceGroupMembers(ScimMetalakeContext.getMetalake(), group.externalId(), members);
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
      Group group = getGroupByExternalId(ScimMetalakeContext.getMetalake(), id);
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
    boolean deleted = deleteGroupByExternalId(ScimMetalakeContext.getMetalake(), id);
    if (!deleted) {
      throw new ResourceException(404, "Group not found: " + id);
    }
  }

  @Override
  public List<Class<? extends ScimExtension>> getExtensionList() {
    return List.of();
  }

  private Group createGroup(
      String metalake, String externalId, String rawDisplayName, List<GroupMembership> members)
      throws ResourceException, GroupAlreadyExistsException {
    validateExternalId(externalId);
    try {
      return dispatcher.getGroupByExternalId(metalake, externalId);
    } catch (NoSuchGroupException ignored) {
      // Not provisioned yet — create below.
    }
    String groupName = resolveGroupName(rawDisplayName, externalId);
    Group group = dispatcher.addGroup(metalake, groupName, externalId);
    if (members != null && !members.isEmpty()) {
      replaceGroupMembers(metalake, group.externalId(), members);
    }
    return group;
  }

  private Group getGroupByExternalId(String metalake, String externalId)
      throws NoSuchGroupException {
    validateExternalId(externalId);
    return dispatcher.getGroupByExternalId(metalake, externalId);
  }

  private boolean deleteGroupByExternalId(String metalake, String externalId) {
    validateExternalId(externalId);
    return dispatcher.removeGroupByExternalId(metalake, externalId);
  }

  private void addGroupMembers(
      String metalake, String groupExternalId, List<GroupMembership> members)
      throws ResourceException {
    List<String> userExternalIds = extractMemberExternalIds(members);
    if (userExternalIds.isEmpty()) {
      return;
    }
    try {
      membershipManager.addUsersToGroup(metalake, groupExternalId, userExternalIds);
    } catch (IOException e) {
      throw new ResourceException(500, "Failed to add users to group " + groupExternalId, e);
    }
  }

  private void removeGroupMembers(
      String metalake, String groupExternalId, List<GroupMembership> members) {
    List<String> userExternalIds = extractMemberExternalIds(members);
    if (userExternalIds.isEmpty()) {
      return;
    }
    membershipManager.removeUsersFromGroup(metalake, groupExternalId, userExternalIds);
  }

  private void replaceGroupMembers(
      String metalake, String groupExternalId, List<GroupMembership> members)
      throws ResourceException {
    List<String> userExternalIds = extractMemberExternalIds(members);
    try {
      membershipManager.replaceUsersInGroup(metalake, groupExternalId, userExternalIds);
    } catch (IOException e) {
      throw new ResourceException(500, "Failed to replace users in group " + groupExternalId, e);
    }
  }

  private List<String> listMemberExternalIds(String metalake, String groupExternalId) {
    List<String> externalIds = new ArrayList<>();
    for (String username : membershipManager.listUsernamesForGroup(metalake, groupExternalId)) {
      try {
        User user = dispatcher.getUser(metalake, username);
        if (StringUtils.isNotBlank(user.externalId())) {
          externalIds.add(user.externalId());
        } else {
          LOG.warn(
              "Skipping group member {} without externalId in group {}", username, groupExternalId);
        }
      } catch (NoSuchUserException ignored) {
        LOG.warn(
            "Skipping missing group member {} while listing members for group {}",
            username,
            groupExternalId);
      }
    }
    externalIds.sort(String::compareTo);
    return externalIds;
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
        return Optional.of(getGroupByExternalId(metalake, criteria.externalId().get()));
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
        && group.externalId() != null
        && !criteria.externalId().get().equals(group.externalId())) {
      return false;
    }
    return true;
  }

  private static List<String> extractMemberExternalIds(List<GroupMembership> members) {
    if (members == null || members.isEmpty()) {
      return List.of();
    }
    return members.stream()
        .map(GroupMembership::getValue)
        .filter(StringUtils::isNotBlank)
        .collect(Collectors.toList());
  }

  private String resolveGroupName(String rawDisplayName, String externalId) {
    return StringUtils.isBlank(rawDisplayName)
        ? externalId
        : ScimNameMappers.mapGroupName(scimConfig.groupMapper(), rawDisplayName);
  }

  private ScimGroup toScimGroup(Group group) {
    List<String> memberExternalIds =
        listMemberExternalIds(ScimMetalakeContext.getMetalake(), group.externalId());
    return ScimResourceConverter.toScimGroup(group, memberExternalIds);
  }

  private static void validateExternalId(String externalId) {
    Preconditions.checkArgument(!StringUtils.isBlank(externalId), "externalId is required");
  }
}
