/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.adapter;

import com.datastrato.gravitino.scim.service.ScimConfig;
import com.datastrato.gravitino.scim.service.basic.mapper.ScimNameMappers;
import com.datastrato.gravitino.scim.service.converter.ScimResourceConverter;
import com.datastrato.gravitino.scim.service.filter.ScimUserFilter;
import com.datastrato.gravitino.scim.service.model.ScimPagedResult;
import com.datastrato.gravitino.scim.service.web.ScimMetalakeContext;
import com.google.common.base.Preconditions;
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
import org.apache.directory.scim.spec.resources.ScimExtension;
import org.apache.directory.scim.spec.resources.ScimUser;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.exceptions.NoSuchUserException;
import org.apache.gravitino.exceptions.UserAlreadyExistsException;

/** SCIMple repository adapter for User provisioning backed by Gravitino core APIs. */
public class ScimUserRepositoryAdapter implements Repository<ScimUser> {

  private final AccessControlDispatcher dispatcher;
  private final ScimConfig scimConfig;

  /**
   * Creates an adapter from server and SCIM configuration.
   *
   * @param gravitinoConfig server configuration
   * @param scimConfig SCIM mapper configuration
   */
  public ScimUserRepositoryAdapter(Config gravitinoConfig, ScimConfig scimConfig) {
    this(GravitinoEnv.getInstance().accessControlDispatcher(), scimConfig);
  }

  /**
   * Creates an adapter with explicit dispatcher dependency.
   *
   * @param dispatcher access control dispatcher
   * @param scimConfig SCIM mapper configuration
   */
  ScimUserRepositoryAdapter(AccessControlDispatcher dispatcher, ScimConfig scimConfig) {
    this.dispatcher = dispatcher;
    this.scimConfig = scimConfig;
  }

  @Override
  public Class<ScimUser> getResourceClass() {
    return ScimUser.class;
  }

  @Override
  public ScimUser create(ScimUser resource) throws ResourceException {
    String externalId = resource.getExternalId();
    if (StringUtils.isBlank(externalId)) {
      throw new ResourceException(400, "externalId is required on User create");
    }
    try {
      User user =
          createUser(
              ScimMetalakeContext.getMetalake(),
              externalId,
              resource.getUserName(),
              resolveEnabled(resource));
      return ScimResourceConverter.toScimUser(user);
    } catch (UserAlreadyExistsException e) {
      throw new ResourceException(409, "User already exists: " + resource.getUserName(), e);
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
    throw new ResourceException(405, "PUT is not supported for Users");
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
      User user = setUserActive(ScimMetalakeContext.getMetalake(), id, active.get());
      return ScimResourceConverter.toScimUser(user);
    } catch (NoSuchUserException e) {
      throw new ResourceException(404, "User not found: " + id);
    }
  }

  @Override
  public ScimUser get(String id) throws ResourceException {
    try {
      User user = getUserByExternalId(ScimMetalakeContext.getMetalake(), id);
      return ScimResourceConverter.toScimUser(user);
    } catch (NoSuchUserException e) {
      throw new ResourceException(404, "User not found: " + id);
    }
  }

  @Override
  public FilterResponse<ScimUser> find(
      Filter filter, PageRequest pageRequest, SortRequest sortRequest) throws ResourceException {
    ScimUserFilter criteria = ScimUserFilter.convert(filter, scimConfig);
    ScimRepositoryPagination.PageBounds page =
        ScimRepositoryPagination.normalizePage(pageRequest.getStartIndex(), pageRequest.getCount());
    ScimPagedResult<User> result = findUsers(ScimMetalakeContext.getMetalake(), criteria);
    List<ScimUser> resources =
        result.items().stream().map(ScimResourceConverter::toScimUser).collect(Collectors.toList());
    return new FilterResponse<>(
        resources,
        new PageRequest().setStartIndex(page.startIndex()).setCount(page.limit()),
        (int) result.totalCount());
  }

  @Override
  public void delete(String id) throws ResourceException {
    boolean deleted = deleteUserByExternalId(ScimMetalakeContext.getMetalake(), id);
    if (!deleted) {
      throw new ResourceException(404, "User not found: " + id);
    }
  }

  @Override
  public List<Class<? extends ScimExtension>> getExtensionList() {
    return List.of();
  }

  private User createUser(String metalake, String externalId, String rawUserName, boolean enabled) {
    validateExternalId(externalId);
    try {
      return dispatcher.getUserByExternalId(metalake, externalId);
    } catch (NoSuchUserException ignored) {
      // Not provisioned yet — create below.
    }
    String userName = resolveUserName(rawUserName, externalId);
    return dispatcher.addUser(metalake, userName, externalId, enabled);
  }

  private User getUserByExternalId(String metalake, String externalId) throws NoSuchUserException {
    validateExternalId(externalId);
    return dispatcher.getUserByExternalId(metalake, externalId);
  }

  private User setUserActive(String metalake, String externalId, boolean active)
      throws NoSuchUserException {
    validateExternalId(externalId);
    return active
        ? dispatcher.enableUser(metalake, externalId)
        : dispatcher.disableUser(metalake, externalId);
  }

  private boolean deleteUserByExternalId(String metalake, String externalId) {
    validateExternalId(externalId);
    return dispatcher.removeUserByExternalId(metalake, externalId);
  }

  /**
   * Resolves a filtered SCIM list query.
   *
   * <p>Gravitino core exposes point lookups only ({@code getUserByExternalId} / {@code getUser});
   * there is no paginated user-list API yet. Supported {@code eq} / {@code and} filters therefore
   * map to at most one user via a primary-key lookup plus optional cross-field validation.
   */
  private ScimPagedResult<User> findUsers(String metalake, ScimUserFilter criteria) {
    if (!criteria.hasPredicates()) {
      return new ScimPagedResult<>(0, List.of());
    }
    return lookupUser(metalake, criteria)
        .filter(user -> matchesFilter(user, criteria))
        .map(user -> new ScimPagedResult<>(1, List.of(user)))
        .orElseGet(() -> new ScimPagedResult<>(0, List.of()));
  }

  private Optional<User> lookupUser(String metalake, ScimUserFilter criteria) {
    try {
      if (criteria.externalId().isPresent()) {
        return Optional.of(getUserByExternalId(metalake, criteria.externalId().get()));
      }
      return Optional.of(dispatcher.getUser(metalake, criteria.userName().orElseThrow()));
    } catch (NoSuchUserException ignored) {
      return Optional.empty();
    }
  }

  private static boolean matchesFilter(User user, ScimUserFilter criteria) {
    if (criteria.userName().isPresent() && !criteria.userName().get().equals(user.name())) {
      return false;
    }
    if (criteria.externalId().isPresent()
        && user.externalId() != null
        && !criteria.externalId().get().equals(user.externalId())) {
      return false;
    }
    return true;
  }

  private String resolveUserName(String rawUserName, String externalId) {
    return StringUtils.isBlank(rawUserName)
        ? externalId
        : ScimNameMappers.mapUserName(scimConfig.userMapper(), rawUserName);
  }

  /**
   * Resolves Gravitino {@code enabled} from the SCIM User {@code active} attribute.
   *
   * @param resource SCIM user create payload
   * @return enabled flag for {@code addUser}
   */
  private static boolean resolveEnabled(ScimUser resource) {
    Boolean active = resource.getActive();
    return active == null || active;
  }

  private static void validateExternalId(String externalId) {
    Preconditions.checkArgument(!StringUtils.isBlank(externalId), "externalId is required");
  }
}
