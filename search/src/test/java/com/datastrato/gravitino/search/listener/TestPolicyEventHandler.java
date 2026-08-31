/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.listener;

import static org.mockito.Mockito.verify;

import com.datastrato.gravitino.search.service.SearchService;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.event.policy.AlterPolicyEvent;
import org.apache.gravitino.listener.api.event.policy.AssociatePoliciesForMetadataObjectEvent;
import org.apache.gravitino.listener.api.event.policy.CreatePolicyEvent;
import org.apache.gravitino.listener.api.event.policy.DeletePolicyEvent;
import org.apache.gravitino.listener.api.event.policy.DisablePolicyEvent;
import org.apache.gravitino.listener.api.event.policy.EnablePolicyEvent;
import org.apache.gravitino.listener.api.info.PolicyInfo;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyChange;
import org.apache.gravitino.policy.PolicyContent;
import org.apache.gravitino.policy.PolicyContents;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestPolicyEventHandler {
  private SearchService searchService;
  private PolicyEventHandler handler;

  @BeforeEach
  void setUp() {
    searchService = Mockito.mock(SearchService.class);
    handler = new PolicyEventHandler(searchService);
  }

  @Test
  void testCreateEnableAndDisableSynchronizePolicy() {
    NameIdentifier identifier = NameIdentifierUtil.ofPolicy("metalake", "retention");
    CreatePolicyEvent create = new CreatePolicyEvent("user", identifier, policyInfo());

    handler.handleEvent(create);
    handler.handleEvent(new EnablePolicyEvent("user", identifier));
    // Disabling a policy changes the indexed document too, the handler treats it like enabling.
    handler.handleEvent(new DisablePolicyEvent("user", identifier));

    verify(searchService, Mockito.times(3))
        .synchronizeMetadata(identifier, Entity.EntityType.POLICY, false);
  }

  @Test
  void testAlterSynchronizesPolicyAndAssociatedEntities() {
    NameIdentifier identifier = NameIdentifierUtil.ofPolicy("metalake", "retention");
    AlterPolicyEvent event =
        new AlterPolicyEvent(
            "user",
            identifier,
            new PolicyChange[] {PolicyChange.updateComment("updated")},
            policyInfo());

    handler.handleEvent(event);

    verify(searchService).synchronizeMetadata(identifier, Entity.EntityType.POLICY, false);
    verify(searchService).synchronizeEntityDataByPolicy("metalake", "retention");
  }

  @Test
  void testAssociationSynchronizesMetadataObject() {
    MetadataObject table = MetadataObjects.parse("catalog.schema.table", MetadataObject.Type.TABLE);
    AssociatePoliciesForMetadataObjectEvent event =
        new AssociatePoliciesForMetadataObjectEvent(
            "user", "metalake", table, new String[] {"retention"}, new String[0]);

    handler.handleEvent(event);

    verify(searchService).synchronizeMetadata(event.identifier(), Entity.EntityType.TABLE, true);
  }

  @Test
  void testDeleteRemovesPolicyAndResynchronizesAssociatedEntities() {
    NameIdentifier identifier = NameIdentifierUtil.ofPolicy("metalake", "retention");
    DeletePolicyEvent event = new DeletePolicyEvent("user", identifier, true);

    handler.handleEvent(event);

    verify(searchService).removeMetadata(identifier, Entity.EntityType.POLICY, false);
    verify(searchService).resyncMetadataByPolicy("metalake", "retention");
  }

  private static PolicyInfo policyInfo() {
    PolicyContent content =
        PolicyContents.custom(
            ImmutableMap.of("retentionDays", 30),
            ImmutableSet.of(MetadataObject.Type.TABLE),
            ImmutableMap.of());
    return new PolicyInfo(
        "retention",
        Policy.BuiltInType.CUSTOM.policyType(),
        "retention policy",
        true,
        content,
        null);
  }
}
