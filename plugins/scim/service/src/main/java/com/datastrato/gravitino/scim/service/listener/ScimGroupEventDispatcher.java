/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.listener;

import com.datastrato.gravitino.scim.ScimUtils;
import com.datastrato.gravitino.scim.service.web.ScimMetalakeContext;
import java.util.List;
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
import org.apache.directory.scim.spec.resources.ScimGroup;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.api.event.scim.ScimAddGroupEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAddGroupFailureEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAddGroupPreEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAlterGroupEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAlterGroupFailureEvent;
import org.apache.gravitino.listener.api.event.scim.ScimAlterGroupPreEvent;
import org.apache.gravitino.listener.api.event.scim.ScimGetGroupEvent;
import org.apache.gravitino.listener.api.event.scim.ScimGetGroupFailureEvent;
import org.apache.gravitino.listener.api.event.scim.ScimGetGroupPreEvent;
import org.apache.gravitino.listener.api.event.scim.ScimListGroupsEvent;
import org.apache.gravitino.listener.api.event.scim.ScimListGroupsFailureEvent;
import org.apache.gravitino.listener.api.event.scim.ScimListGroupsPreEvent;
import org.apache.gravitino.listener.api.event.scim.ScimRemoveGroupEvent;
import org.apache.gravitino.listener.api.event.scim.ScimRemoveGroupFailureEvent;
import org.apache.gravitino.listener.api.event.scim.ScimRemoveGroupPreEvent;
import org.apache.gravitino.utils.PrincipalUtils;

/**
 * Decorator for the SCIM Group {@link Repository} that dispatches SCIM Group audit events.
 *
 * <p>This wraps the SCIMple repository API (create / update / patch / delete / get / find). The
 * underlying adapter uses the internal access-control dispatcher, so core group audit events are
 * not emitted; this decorator is the SCIM audit layer.
 */
public class ScimGroupEventDispatcher implements Repository<ScimGroup> {

  private final EventBus eventBus;
  private final Repository<ScimGroup> repository;

  /**
   * Creates a SCIM Group repository decorator that emits audit events.
   *
   * @param eventBus shared event bus
   * @param repository underlying SCIM Group repository
   */
  public ScimGroupEventDispatcher(EventBus eventBus, Repository<ScimGroup> repository) {
    this.eventBus = eventBus;
    this.repository = repository;
  }

  @Override
  public Class<ScimGroup> getResourceClass() {
    return repository.getResourceClass();
  }

  @Override
  public ScimGroup create(ScimGroup resource) throws ResourceException {
    String initiator = PrincipalUtils.getCurrentUserName();
    String metalake = ScimMetalakeContext.getMetalake();
    String groupName =
        ScimUtils.blankToUnknown(resource == null ? null : resource.getDisplayName());
    String externalId = ScimUtils.blankToNull(resource == null ? null : resource.getExternalId());

    eventBus.dispatchEvent(
        new ScimAddGroupPreEvent(initiator, metalake, groupName, null, externalId));
    try {
      ScimGroup created = repository.create(resource);
      eventBus.dispatchEvent(
          new ScimAddGroupEvent(
              initiator,
              metalake,
              ScimUtils.blankToUnknown(created.getDisplayName()),
              created.getId(),
              ScimUtils.blankToNull(created.getExternalId())));
      return created;
    } catch (Exception e) {
      eventBus.dispatchEvent(
          new ScimAddGroupFailureEvent(initiator, metalake, e, groupName, null, externalId));
      throw e;
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
    String initiator = PrincipalUtils.getCurrentUserName();
    String metalake = ScimMetalakeContext.getMetalake();
    String groupName =
        ScimUtils.blankToUnknown(resource == null ? null : resource.getDisplayName());
    String externalId = ScimUtils.blankToNull(resource == null ? null : resource.getExternalId());

    eventBus.dispatchEvent(
        new ScimAlterGroupPreEvent(initiator, metalake, groupName, id, externalId));
    try {
      ScimGroup updated =
          repository.update(id, version, resource, includedAttributes, excludedAttributes);
      eventBus.dispatchEvent(
          new ScimAlterGroupEvent(
              initiator,
              metalake,
              ScimUtils.blankToUnknown(updated.getDisplayName()),
              updated.getId(),
              ScimUtils.blankToNull(updated.getExternalId())));
      return updated;
    } catch (Exception e) {
      eventBus.dispatchEvent(
          new ScimAlterGroupFailureEvent(initiator, metalake, e, groupName, id, externalId));
      throw e;
    }
  }

  @Override
  public ScimGroup patch(
      String id,
      String version,
      List<PatchOperation> patchOperations,
      Set<AttributeReference> includedAttributes,
      Set<AttributeReference> excludedAttributes)
      throws ResourceException {
    String initiator = PrincipalUtils.getCurrentUserName();
    String metalake = ScimMetalakeContext.getMetalake();
    String groupName = ScimUtils.blankToUnknown(null);

    eventBus.dispatchEvent(new ScimAlterGroupPreEvent(initiator, metalake, groupName, id, null));
    try {
      ScimGroup patched =
          repository.patch(id, version, patchOperations, includedAttributes, excludedAttributes);
      eventBus.dispatchEvent(
          new ScimAlterGroupEvent(
              initiator,
              metalake,
              ScimUtils.blankToUnknown(patched.getDisplayName()),
              patched.getId(),
              ScimUtils.blankToNull(patched.getExternalId())));
      return patched;
    } catch (Exception e) {
      eventBus.dispatchEvent(
          new ScimAlterGroupFailureEvent(initiator, metalake, e, groupName, id, null));
      throw e;
    }
  }

  @Override
  public ScimGroup get(String id) throws ResourceException {
    String initiator = PrincipalUtils.getCurrentUserName();
    String metalake = ScimMetalakeContext.getMetalake();

    eventBus.dispatchEvent(new ScimGetGroupPreEvent(initiator, metalake, id));
    try {
      ScimGroup group = repository.get(id);
      eventBus.dispatchEvent(
          new ScimGetGroupEvent(
              initiator,
              metalake,
              ScimUtils.blankToUnknown(group.getDisplayName()),
              group.getId(),
              ScimUtils.blankToNull(group.getExternalId())));
      return group;
    } catch (Exception e) {
      eventBus.dispatchEvent(new ScimGetGroupFailureEvent(initiator, metalake, e, id));
      throw e;
    }
  }

  @Override
  public FilterResponse<ScimGroup> find(
      Filter filter, PageRequest pageRequest, SortRequest sortRequest) throws ResourceException {
    String initiator = PrincipalUtils.getCurrentUserName();
    String metalake = ScimMetalakeContext.getMetalake();
    int startIndex =
        pageRequest == null || pageRequest.getStartIndex() == null
            ? 0
            : pageRequest.getStartIndex();
    int count = pageRequest == null || pageRequest.getCount() == null ? 0 : pageRequest.getCount();

    eventBus.dispatchEvent(new ScimListGroupsPreEvent(initiator, metalake, startIndex, count));
    try {
      FilterResponse<ScimGroup> response = repository.find(filter, pageRequest, sortRequest);
      int pageSize = response.getResources() == null ? 0 : response.getResources().size();
      eventBus.dispatchEvent(
          new ScimListGroupsEvent(
              initiator, metalake, startIndex, count, pageSize, response.getTotalResults()));
      return response;
    } catch (Exception e) {
      eventBus.dispatchEvent(
          new ScimListGroupsFailureEvent(initiator, metalake, e, startIndex, count));
      throw e;
    }
  }

  @Override
  public void delete(String id) throws ResourceException {
    String initiator = PrincipalUtils.getCurrentUserName();
    String metalake = ScimMetalakeContext.getMetalake();
    String groupName = ScimUtils.blankToUnknown(null);

    eventBus.dispatchEvent(new ScimRemoveGroupPreEvent(initiator, metalake, groupName, id, null));
    try {
      repository.delete(id);
      eventBus.dispatchEvent(new ScimRemoveGroupEvent(initiator, metalake, groupName, id, null));
    } catch (Exception e) {
      eventBus.dispatchEvent(
          new ScimRemoveGroupFailureEvent(initiator, metalake, e, groupName, id, null));
      throw e;
    }
  }

  @Override
  public List<Class<? extends ScimExtension>> getExtensionList() throws InvalidRepositoryException {
    return repository.getExtensionList();
  }
}
