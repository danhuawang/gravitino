/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.listener;

import com.datastrato.gravitino.scim.ScimUtils;
import com.datastrato.gravitino.scim.service.adapter.ScimPatchSupport;
import com.datastrato.gravitino.scim.service.web.ScimMetalakeContext;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.directory.scim.core.repository.InvalidRepositoryException;
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
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.api.event.scim.ScimAddUserEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAddUserFailureEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAddUserPreEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAlterUserEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAlterUserFailureEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAlterUserPreEvent;
import org.apache.gravitino.listener.api.event.scim.ScimGetUserEvent;
import org.apache.gravitino.listener.api.event.scim.ScimGetUserFailureEvent;
import org.apache.gravitino.listener.api.event.scim.ScimGetUserPreEvent;
import org.apache.gravitino.listener.api.event.scim.ScimListUsersEvent;
import org.apache.gravitino.listener.api.event.scim.ScimListUsersFailureEvent;
import org.apache.gravitino.listener.api.event.scim.ScimListUsersPreEvent;
import org.apache.gravitino.listener.api.event.scim.ScimRemoveUserEvent;
import org.apache.gravitino.listener.api.event.scim.ScimRemoveUserFailureEvent;
import org.apache.gravitino.listener.api.event.scim.ScimRemoveUserPreEvent;
import org.apache.gravitino.utils.PrincipalUtils;

/**
 * Decorator for the SCIM User {@link Repository} that dispatches SCIM User audit events.
 *
 * <p>This wraps the SCIMple repository API (create / update / patch / delete / get / find). The
 * underlying adapter uses the internal access-control dispatcher, so core user audit events are not
 * emitted; this decorator is the SCIM audit layer.
 */
public class ScimUserEventDispatcher implements Repository<ScimUser> {

  private final EventBus eventBus;
  private final Repository<ScimUser> repository;

  /**
   * Creates a SCIM User repository decorator that emits audit events.
   *
   * @param eventBus shared event bus
   * @param repository underlying SCIM User repository
   */
  public ScimUserEventDispatcher(EventBus eventBus, Repository<ScimUser> repository) {
    this.eventBus = eventBus;
    this.repository = repository;
  }

  @Override
  public Class<ScimUser> getResourceClass() {
    return repository.getResourceClass();
  }

  @Override
  public ScimUser create(ScimUser resource) throws ResourceException {
    String initiator = PrincipalUtils.getCurrentUserName();
    String metalake = ScimMetalakeContext.getMetalake();
    String userName = ScimUtils.blankToUnknown(resource == null ? null : resource.getUserName());
    String externalId = ScimUtils.blankToNull(resource == null ? null : resource.getExternalId());

    eventBus.dispatchEvent(
        new ScimAddUserPreEvent(initiator, metalake, userName, null, externalId));
    try {
      ScimUser created = repository.create(resource);
      eventBus.dispatchEvent(
          new ScimAddUserEvent(
              initiator,
              metalake,
              ScimUtils.blankToUnknown(created.getUserName()),
              created.getId(),
              ScimUtils.blankToNull(created.getExternalId())));
      return created;
    } catch (Exception e) {
      eventBus.dispatchEvent(
          new ScimAddUserFailureEvent(initiator, metalake, e, userName, null, externalId));
      throw e;
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
    String initiator = PrincipalUtils.getCurrentUserName();
    String metalake = ScimMetalakeContext.getMetalake();
    String userName = ScimUtils.blankToUnknown(resource == null ? null : resource.getUserName());
    String externalId = ScimUtils.blankToNull(resource == null ? null : resource.getExternalId());
    Map<String, String> changes = ImmutableMap.of("changes", "put");

    eventBus.dispatchEvent(
        new ScimAlterUserPreEvent(initiator, metalake, userName, id, externalId));
    try {
      ScimUser updated =
          repository.update(id, version, resource, includedAttributes, excludedAttributes);
      eventBus.dispatchEvent(
          new ScimAlterUserEvent(
              initiator,
              metalake,
              ScimUtils.blankToUnknown(updated.getUserName()),
              updated.getId(),
              ScimUtils.blankToNull(updated.getExternalId()),
              changes));
      return updated;
    } catch (Exception e) {
      eventBus.dispatchEvent(
          new ScimAlterUserFailureEvent(initiator, metalake, e, userName, id, externalId));
      throw e;
    }
  }

  @Override
  public ScimUser patch(
      String id,
      String version,
      List<PatchOperation> patchOperations,
      Set<AttributeReference> includedAttributes,
      Set<AttributeReference> excludedAttributes)
      throws ResourceException {
    String initiator = PrincipalUtils.getCurrentUserName();
    String metalake = ScimMetalakeContext.getMetalake();
    ScimUser existing = findUserById(id);
    String userName = ScimUtils.blankToUnknown(existing == null ? null : existing.getUserName());
    String externalId = ScimUtils.blankToNull(existing == null ? null : existing.getExternalId());
    Map<String, String> changes = summarizeUserPatch(patchOperations);

    eventBus.dispatchEvent(
        new ScimAlterUserPreEvent(initiator, metalake, userName, id, externalId));
    try {
      ScimUser patched =
          repository.patch(id, version, patchOperations, includedAttributes, excludedAttributes);
      eventBus.dispatchEvent(
          new ScimAlterUserEvent(
              initiator,
              metalake,
              ScimUtils.blankToUnknown(patched.getUserName()),
              patched.getId(),
              ScimUtils.blankToNull(patched.getExternalId()),
              changes));
      return patched;
    } catch (Exception e) {
      eventBus.dispatchEvent(
          new ScimAlterUserFailureEvent(initiator, metalake, e, userName, id, externalId));
      throw e;
    }
  }

  @Override
  public ScimUser get(String id) throws ResourceException {
    String initiator = PrincipalUtils.getCurrentUserName();
    String metalake = ScimMetalakeContext.getMetalake();

    eventBus.dispatchEvent(new ScimGetUserPreEvent(initiator, metalake, id));
    try {
      ScimUser user = repository.get(id);
      eventBus.dispatchEvent(
          new ScimGetUserEvent(
              initiator,
              metalake,
              ScimUtils.blankToUnknown(user.getUserName()),
              user.getId(),
              ScimUtils.blankToNull(user.getExternalId())));
      return user;
    } catch (Exception e) {
      eventBus.dispatchEvent(new ScimGetUserFailureEvent(initiator, metalake, e, id));
      throw e;
    }
  }

  @Override
  public FilterResponse<ScimUser> find(
      Filter filter, PageRequest pageRequest, SortRequest sortRequest) throws ResourceException {
    String initiator = PrincipalUtils.getCurrentUserName();
    String metalake = ScimMetalakeContext.getMetalake();
    int startIndex =
        pageRequest == null || pageRequest.getStartIndex() == null
            ? 0
            : pageRequest.getStartIndex();
    int count = pageRequest == null || pageRequest.getCount() == null ? 0 : pageRequest.getCount();

    eventBus.dispatchEvent(new ScimListUsersPreEvent(initiator, metalake, startIndex, count));
    try {
      FilterResponse<ScimUser> response = repository.find(filter, pageRequest, sortRequest);
      int pageSize = response.getResources() == null ? 0 : response.getResources().size();
      eventBus.dispatchEvent(
          new ScimListUsersEvent(
              initiator, metalake, startIndex, count, pageSize, response.getTotalResults()));
      return response;
    } catch (Exception e) {
      eventBus.dispatchEvent(
          new ScimListUsersFailureEvent(initiator, metalake, e, startIndex, count));
      throw e;
    }
  }

  @Override
  public void delete(String id) throws ResourceException {
    String initiator = PrincipalUtils.getCurrentUserName();
    String metalake = ScimMetalakeContext.getMetalake();
    ScimUser existing = findUserById(id);
    String userName = ScimUtils.blankToUnknown(existing == null ? null : existing.getUserName());
    String externalId = ScimUtils.blankToNull(existing == null ? null : existing.getExternalId());

    eventBus.dispatchEvent(
        new ScimRemoveUserPreEvent(initiator, metalake, userName, id, externalId));
    try {
      repository.delete(id);
      eventBus.dispatchEvent(
          new ScimRemoveUserEvent(initiator, metalake, userName, id, externalId));
    } catch (Exception e) {
      eventBus.dispatchEvent(
          new ScimRemoveUserFailureEvent(initiator, metalake, e, userName, id, externalId));
      throw e;
    }
  }

  @Override
  public List<Class<? extends ScimExtension>> getExtensionList() throws InvalidRepositoryException {
    return repository.getExtensionList();
  }

  /** Returns the user for audit context, or {@code null} if lookup fails. */
  private ScimUser findUserById(String id) {
    try {
      return repository.get(id);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static Map<String, String> summarizeUserPatch(List<PatchOperation> patchOperations) {
    if (patchOperations == null || patchOperations.isEmpty()) {
      return ImmutableMap.of("changes", "patch");
    }
    try {
      Optional<Boolean> active = ScimPatchSupport.parseUserActive(patchOperations);
      if (active.isPresent()) {
        return ImmutableMap.of("changes", "active=" + active.get());
      }
    } catch (ResourceException ignored) {
      // Non-active or unsupported patch body: fall through.
    }
    return ImmutableMap.of("changes", "patch");
  }
}
