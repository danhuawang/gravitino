/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.service.adapter;

import com.datastrato.gravitino.scim.v2.ScimUserManager;
import com.datastrato.gravitino.scim.v2.ScimUtils;
import com.datastrato.gravitino.scim.v2.model.ScimUserMeta;
import com.datastrato.gravitino.scim.v2.service.ScimConfig;
import com.datastrato.gravitino.scim.v2.service.basic.mapper.ScimNameMappers;
import com.datastrato.gravitino.scim.v2.service.converter.ScimResourceConverter;
import com.datastrato.gravitino.scim.v2.service.filter.ScimUserFilter;
import com.datastrato.gravitino.scim.v2.service.model.ScimPagedResult;
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
import org.apache.directory.scim.spec.resources.ScimExtension;
import org.apache.directory.scim.spec.resources.ScimUser;
import org.apache.gravitino.Config;
import org.apache.gravitino.authorization.PagedResult;
import org.apache.gravitino.exceptions.AlreadyExistsException;
import org.apache.gravitino.exceptions.NotFoundException;

/** SCIMple repository adapter for User provisioning backed by {@code v2_scim_user_meta}. */
public class ScimUserRepositoryAdapter implements Repository<ScimUser> {

  private final ScimUserManager userManager;
  private final ScimConfig scimConfig;

  public ScimUserRepositoryAdapter(Config gravitinoConfig, ScimConfig scimConfig) {
    this(ScimUserManager.getInstance(), scimConfig);
  }

  ScimUserRepositoryAdapter(ScimUserManager userManager, ScimConfig scimConfig) {
    this.userManager = userManager;
    this.scimConfig = scimConfig;
  }

  @Override
  public Class<ScimUser> getResourceClass() {
    return ScimUser.class;
  }

  @Override
  public ScimUser create(ScimUser resource) throws ResourceException {
    String externalId = ScimUtils.blankToNull(resource.getExternalId());
    String userName = resolveUserName(resource.getUserName());
    try {
      if (findUserIgnoreCase(userName).isPresent()) {
        throw new ResourceException(409, "User already exists: userName=" + userName);
      }
      ScimUserMeta user = userManager.createUser(userName, externalId, resolveEnabled(resource));
      return ScimResourceConverter.toScimUser(user);
    } catch (ResourceException e) {
      throw e;
    } catch (AlreadyExistsException e) {
      throw new ResourceException(409, "User already exists: userName=" + userName, e);
    } catch (Exception e) {
      throw new ResourceException(500, "Failed to create user", e);
    }
  }

  @Override
  public ScimUser update(
      String id,
      String version,
      ScimUser resource,
      Set<AttributeReference> includedAttributes,
      Set<AttributeReference> excludedAttributes)
      throws ResourceException {
    ScimUserMeta user = requireUser(id);
    validateImmutableUserIdentity(user, id, resource);
    validateImmutableUserName(user, resource.getUserName());
    user = applyActiveIfPresent(user, resource.getActive());
    return ScimResourceConverter.toScimUser(user);
  }

  @Override
  public ScimUser patch(
      String id,
      String version,
      List<PatchOperation> patchOperations,
      Set<AttributeReference> includedAttributes,
      Set<AttributeReference> excludedAttributes)
      throws ResourceException {
    Optional<Boolean> active = ScimPatchSupport.parseUserActive(patchOperations);
    if (active.isEmpty()) {
      throw new ResourceException(400, "PATCH on Users supports active only");
    }
    try {
      ScimUserMeta user = userManager.updateEnabled(id, active.get());
      return ScimResourceConverter.toScimUser(user);
    } catch (NotFoundException e) {
      throw new ResourceException(404, "User not found: " + id);
    }
  }

  @Override
  public ScimUser get(String id) throws ResourceException {
    try {
      return ScimResourceConverter.toScimUser(userManager.getUserByExternalId(id));
    } catch (NotFoundException e) {
      throw new ResourceException(404, "User not found: " + id);
    }
  }

  @Override
  public FilterResponse<ScimUser> find(
      Filter filter, PageRequest pageRequest, SortRequest sortRequest) throws ResourceException {
    ScimUserFilter criteria = ScimUserFilter.convert(filter, scimConfig);
    ScimRepositoryPagination.PageBounds page =
        ScimRepositoryPagination.normalizePage(pageRequest.getStartIndex(), pageRequest.getCount());
    ScimPagedResult<ScimUserMeta> result = findUsers(criteria, page);
    List<ScimUser> resources =
        result.items().stream().map(ScimResourceConverter::toScimUser).collect(Collectors.toList());
    return new FilterResponse<>(
        resources,
        new PageRequest().setStartIndex(page.startIndex()).setCount(page.limit()),
        (int) result.totalCount());
  }

  @Override
  public void delete(String id) throws ResourceException {
    if (!userManager.deleteUser(id)) {
      throw new ResourceException(404, "User not found: " + id);
    }
  }

  @Override
  public List<Class<? extends ScimExtension>> getExtensionList() {
    return List.of();
  }

  private ScimPagedResult<ScimUserMeta> findUsers(
      ScimUserFilter criteria, ScimRepositoryPagination.PageBounds page) {
    if (!criteria.hasPredicates()) {
      PagedResult<ScimUserMeta> pageResult = userManager.listUsers(page.offset(), page.limit());
      return new ScimPagedResult<>(pageResult.totalCount(), pageResult.items());
    }
    return lookupUser(criteria)
        .filter(user -> matchesFilter(user, criteria))
        .map(user -> singleMatchResult(user, page))
        .orElseGet(() -> new ScimPagedResult<>(0, List.of()));
  }

  private static ScimPagedResult<ScimUserMeta> singleMatchResult(
      ScimUserMeta user, ScimRepositoryPagination.PageBounds page) {
    if (page.limit() == 0) {
      return new ScimPagedResult<>(1, List.of());
    }
    return new ScimPagedResult<>(1, List.of(user));
  }

  private Optional<ScimUserMeta> lookupUser(ScimUserFilter criteria) {
    try {
      if (criteria.id().isPresent() || criteria.externalId().isPresent()) {
        String externalId =
            criteria.externalId().isPresent() ? criteria.externalId().get() : criteria.id().get();
        return Optional.of(userManager.getUserByExternalId(externalId));
      }
      if (criteria.userName().isPresent()) {
        return findUserIgnoreCase(criteria.userName().get());
      }
    } catch (NotFoundException ignored) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private Optional<ScimUserMeta> findUserIgnoreCase(String userName) {
    for (ScimUserMeta user : userManager.listUsers(0, Integer.MAX_VALUE).items()) {
      if (userName.equalsIgnoreCase(user.getUserName())) {
        return Optional.of(user);
      }
    }
    return Optional.empty();
  }

  private static boolean matchesFilter(ScimUserMeta user, ScimUserFilter criteria) {
    if (criteria.id().isPresent() && !criteria.id().get().equals(user.getExternalId())) {
      return false;
    }
    if (criteria.userName().isPresent()
        && !criteria.userName().get().equalsIgnoreCase(user.getUserName())) {
      return false;
    }
    if (criteria.externalId().isPresent()
        && !Objects.equals(criteria.externalId().get(), user.getExternalId())) {
      return false;
    }
    return true;
  }

  private ScimUserMeta requireUser(String externalId) throws ResourceException {
    try {
      return userManager.getUserByExternalId(externalId);
    } catch (NotFoundException e) {
      throw new ResourceException(404, "User not found: " + externalId);
    }
  }

  private String resolveUserName(String rawUserName) throws ResourceException {
    if (StringUtils.isBlank(rawUserName)) {
      throw new ResourceException(400, "userName is required on User create");
    }
    return ScimNameMappers.mapUserName(scimConfig.userMapper(), rawUserName);
  }

  private static void validateImmutableUserIdentity(
      ScimUserMeta user, String pathId, ScimUser resource) throws ResourceException {
    if (StringUtils.isNotBlank(resource.getId()) && !pathId.equals(resource.getId())) {
      throw new ResourceException(400, "User id is immutable");
    }
    if (StringUtils.isNotBlank(resource.getExternalId())
        && !Objects.equals(resource.getExternalId(), user.getExternalId())) {
      throw new ResourceException(400, "User externalId is immutable");
    }
  }

  private void validateImmutableUserName(ScimUserMeta user, String rawUserName)
      throws ResourceException {
    if (StringUtils.isBlank(rawUserName)) {
      return;
    }
    String resolvedName = ScimNameMappers.mapUserName(scimConfig.userMapper(), rawUserName);
    if (!resolvedName.equalsIgnoreCase(user.getUserName())) {
      throw new ResourceException(400, "User userName is immutable");
    }
  }

  private ScimUserMeta applyActiveIfPresent(ScimUserMeta user, Boolean active)
      throws ResourceException {
    if (active == null || active == user.isEnabled()) {
      return user;
    }
    try {
      return userManager.updateEnabled(user.getExternalId(), active);
    } catch (NotFoundException e) {
      throw new ResourceException(404, "User not found: " + user.getExternalId());
    }
  }

  private static boolean resolveEnabled(ScimUser resource) {
    Boolean active = resource.getActive();
    return active == null || active;
  }
}
