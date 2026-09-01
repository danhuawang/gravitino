/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.service.listener;

import com.datastrato.gravitino.scim.v2.ScimUtils;
import com.datastrato.gravitino.scim.v2.service.adapter.ScimPatchSupport;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.directory.scim.core.repository.InvalidRepositoryException;
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
    String metalake = ScimUtils.INSTANCE_AUDIT_METALAKE;
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
    String metalake = ScimUtils.INSTANCE_AUDIT_METALAKE;
    String groupName =
        ScimUtils.blankToUnknown(resource == null ? null : resource.getDisplayName());
    String externalId = ScimUtils.blankToNull(resource == null ? null : resource.getExternalId());
    ScimGroup existing = findGroupById(id);
    Set<String> beforeMembers = memberIds(existing);

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
              ScimUtils.blankToNull(updated.getExternalId()),
              membershipExtras(beforeMembers, memberIds(updated), "put")));
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
    String metalake = ScimUtils.INSTANCE_AUDIT_METALAKE;
    ScimGroup existing = findGroupById(id);
    String groupName =
        ScimUtils.blankToUnknown(existing == null ? null : existing.getDisplayName());
    String externalId = ScimUtils.blankToNull(existing == null ? null : existing.getExternalId());
    Set<String> beforeMembers = memberIds(existing);
    String changeSummary = summarizeGroupPatch(patchOperations);

    eventBus.dispatchEvent(
        new ScimAlterGroupPreEvent(initiator, metalake, groupName, id, externalId));
    try {
      ScimGroup patched =
          repository.patch(id, version, patchOperations, includedAttributes, excludedAttributes);
      eventBus.dispatchEvent(
          new ScimAlterGroupEvent(
              initiator,
              metalake,
              ScimUtils.blankToUnknown(patched.getDisplayName()),
              patched.getId(),
              ScimUtils.blankToNull(patched.getExternalId()),
              membershipExtras(beforeMembers, memberIds(patched), changeSummary)));
      return patched;
    } catch (Exception e) {
      eventBus.dispatchEvent(
          new ScimAlterGroupFailureEvent(initiator, metalake, e, groupName, id, externalId));
      throw e;
    }
  }

  @Override
  public ScimGroup get(String id) throws ResourceException {
    String initiator = PrincipalUtils.getCurrentUserName();
    String metalake = ScimUtils.INSTANCE_AUDIT_METALAKE;

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
    String metalake = ScimUtils.INSTANCE_AUDIT_METALAKE;
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
    String metalake = ScimUtils.INSTANCE_AUDIT_METALAKE;
    ScimGroup existing = findGroupById(id);
    String groupName =
        ScimUtils.blankToUnknown(existing == null ? null : existing.getDisplayName());
    String externalId = ScimUtils.blankToNull(existing == null ? null : existing.getExternalId());

    eventBus.dispatchEvent(
        new ScimRemoveGroupPreEvent(initiator, metalake, groupName, id, externalId));
    try {
      repository.delete(id);
      eventBus.dispatchEvent(
          new ScimRemoveGroupEvent(initiator, metalake, groupName, id, externalId));
    } catch (Exception e) {
      eventBus.dispatchEvent(
          new ScimRemoveGroupFailureEvent(initiator, metalake, e, groupName, id, externalId));
      throw e;
    }
  }

  @Override
  public List<Class<? extends ScimExtension>> getExtensionList() throws InvalidRepositoryException {
    return repository.getExtensionList();
  }

  /** Returns the group for audit context, or {@code null} if lookup fails. */
  private ScimGroup findGroupById(String id) {
    try {
      return repository.get(id);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static Set<String> memberIds(ScimGroup group) {
    Set<String> ids = new LinkedHashSet<>();
    if (group == null || group.getMembers() == null) {
      return ids;
    }
    for (GroupMembership membership : group.getMembers()) {
      if (membership != null && StringUtils.isNotBlank(membership.getValue())) {
        ids.add(membership.getValue());
      }
    }
    return ids;
  }

  private static Map<String, String> membershipExtras(
      Set<String> before, Set<String> after, String changes) {
    Map<String, String> extras = new LinkedHashMap<>();
    if (StringUtils.isNotBlank(changes)) {
      extras.put("changes", changes);
    }
    Set<String> added = new LinkedHashSet<>(after);
    added.removeAll(before);
    Set<String> removed = new LinkedHashSet<>(before);
    removed.removeAll(after);
    if (!added.isEmpty()) {
      extras.put("membersAdded", String.join(",", added));
    }
    if (!removed.isEmpty()) {
      extras.put("membersRemoved", String.join(",", removed));
    }
    return extras.isEmpty() ? null : extras;
  }

  private static String summarizeGroupPatch(List<PatchOperation> patchOperations) {
    if (patchOperations == null || patchOperations.isEmpty()) {
      return "patch";
    }
    List<String> parts = new java.util.ArrayList<>();
    for (PatchOperation operation : patchOperations) {
      try {
        for (ScimPatchSupport.GroupPatchOperation parsed :
            ScimPatchSupport.parseGroupPatches(operation)) {
          switch (parsed.kind()) {
            case MEMBERS:
              parts.add("members");
              break;
            case EXTERNAL_ID:
              parts.add("externalId");
              break;
            case DISPLAY_NAME:
              parts.add("displayName");
              break;
            default:
              break;
          }
        }
      } catch (ResourceException ignored) {
        parts.add("patch");
      }
    }
    if (parts.isEmpty()) {
      return "patch";
    }
    return parts.stream().distinct().collect(Collectors.joining(";"));
  }
}
