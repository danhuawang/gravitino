/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.listener;

import static org.apache.gravitino.utils.MetadataObjectUtil.toEntityType;

import com.datastrato.gravitino.search.service.SearchService;
import org.apache.gravitino.Entity;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.listener.api.event.policy.AlterPolicyEvent;
import org.apache.gravitino.listener.api.event.policy.AssociatePoliciesForMetadataObjectEvent;
import org.apache.gravitino.listener.api.event.policy.CreatePolicyEvent;
import org.apache.gravitino.listener.api.event.policy.DeletePolicyEvent;
import org.apache.gravitino.listener.api.event.policy.DisablePolicyEvent;
import org.apache.gravitino.listener.api.event.policy.EnablePolicyEvent;

/** Synchronizes policy definitions and policy associations after policy events. */
public class PolicyEventHandler implements EventHandler {
  private final SearchService searchService;

  /**
   * Creates a policy event handler.
   *
   * @param searchService the search service
   */
  public PolicyEventHandler(SearchService searchService) {
    this.searchService = searchService;
  }

  @Override
  public void handleEvent(Event event) {
    if (event instanceof AssociatePoliciesForMetadataObjectEvent) {
      AssociatePoliciesForMetadataObjectEvent policyEvent =
          (AssociatePoliciesForMetadataObjectEvent) event;
      searchService.synchronizeMetadata(
          policyEvent.identifier(), toEntityType(policyEvent.metadataObject().type()), true);
    } else if (event instanceof DeletePolicyEvent) {
      String metalake = event.identifier().namespace().level(0);
      searchService.removeMetadata(event.identifier(), Entity.EntityType.POLICY, false);
      searchService.resyncMetadataByPolicy(metalake, event.identifier().name());
    } else if (event instanceof CreatePolicyEvent
        || event instanceof EnablePolicyEvent
        || event instanceof DisablePolicyEvent) {
      searchService.synchronizeMetadata(event.identifier(), Entity.EntityType.POLICY, false);
    } else if (event instanceof AlterPolicyEvent) {
      String metalake = event.identifier().namespace().level(0);
      searchService.synchronizeMetadata(event.identifier(), Entity.EntityType.POLICY, false);
      searchService.synchronizeEntityDataByPolicy(metalake, event.identifier().name());
    }
  }
}
