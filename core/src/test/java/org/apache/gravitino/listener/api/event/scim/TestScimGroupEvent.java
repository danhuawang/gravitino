/*
 * Copyright 2026 Datastrato Inc.
 */

package org.apache.gravitino.listener.api.event.scim;

import java.util.Map;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.listener.api.event.EventSource;
import org.apache.gravitino.listener.api.event.OperationStatus;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Unit tests for SCIM Group audit events (Get / List / Add). */
public class TestScimGroupEvent {

  private static final String METALAKE = "ml1";
  private static final String INITIATOR = "entra-prod";
  private static final String GROUP_NAME = "engineering";
  private static final String RESOURCE_ID = "42";
  private static final String EXTERNAL_ID = "ext-g1";

  @Test
  public void testGetPreCustomInfo() {
    ScimGetGroupPreEvent event = new ScimGetGroupPreEvent(INITIATOR, METALAKE, RESOURCE_ID);

    Assertions.assertEquals(INITIATOR, event.user());
    Assertions.assertEquals(AuthorizationUtils.ofGroupId(METALAKE, 42L), event.identifier());
    Assertions.assertEquals(OperationType.GET_GROUP_BY_ID, event.operationType());
    Assertions.assertEquals(OperationStatus.UNPROCESSED, event.operationStatus());
    Assertions.assertEquals(EventSource.GRAVITINO_SERVER, event.eventSource());
    Assertions.assertEquals(RESOURCE_ID, event.resourceId());
    Assertions.assertNull(event.externalId());

    Map<String, String> info = event.customInfo();
    assertScimSource(info);
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_GROUP, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertEquals(RESOURCE_ID, info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
  }

  @Test
  public void testGetSuccessCustomInfo() {
    ScimGetGroupEvent event =
        new ScimGetGroupEvent(INITIATOR, METALAKE, GROUP_NAME, RESOURCE_ID, EXTERNAL_ID);

    Map<String, String> info = event.customInfo();
    assertScimSource(info);
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_GROUP, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertEquals(RESOURCE_ID, info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertEquals(EXTERNAL_ID, info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
    Assertions.assertEquals(GROUP_NAME, event.groupName());
  }

  @Test
  public void testGetSuccessOmitsBlankIds() {
    ScimGetGroupEvent event = new ScimGetGroupEvent(INITIATOR, METALAKE, GROUP_NAME, "  ", null);

    Map<String, String> info = event.customInfo();
    assertScimSource(info);
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
  }

  @Test
  public void testGetFailureCustomInfo() {
    ScimGetGroupFailureEvent event =
        new ScimGetGroupFailureEvent(
            INITIATOR, METALAKE, new RuntimeException("missing"), RESOURCE_ID);

    Map<String, String> info = event.customInfo();
    assertScimSource(info);
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_GROUP, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertEquals(RESOURCE_ID, info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
    Assertions.assertEquals("missing", info.get(ScimAuditInfos.INFO_REASON));
  }

  @Test
  public void testListPreCustomInfo() {
    ScimListGroupsPreEvent event = new ScimListGroupsPreEvent(INITIATOR, METALAKE, 1, 10);

    Map<String, String> info = event.customInfo();
    assertScimSource(info);
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_GROUP, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
  }

  @Test
  public void testListSuccessCustomInfo() {
    ScimListGroupsEvent event = new ScimListGroupsEvent(INITIATOR, METALAKE, 1, 10, 2, 5L);

    Map<String, String> info = event.customInfo();
    assertScimSource(info);
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_GROUP, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
    Assertions.assertEquals("2", info.get(ScimAuditInfos.INFO_COUNT));
  }

  @Test
  public void testListFailureCustomInfo() {
    ScimListGroupsFailureEvent event =
        new ScimListGroupsFailureEvent(INITIATOR, METALAKE, new RuntimeException("boom"), 1, 10);

    Map<String, String> info = event.customInfo();
    assertScimSource(info);
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_GROUP, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
    Assertions.assertEquals("boom", info.get(ScimAuditInfos.INFO_REASON));
  }

  @Test
  public void testAddPreCustomInfo() {
    ScimAddGroupPreEvent event =
        new ScimAddGroupPreEvent(INITIATOR, METALAKE, GROUP_NAME, null, EXTERNAL_ID);

    Assertions.assertEquals(INITIATOR, event.user());
    Assertions.assertEquals(NameIdentifierUtil.ofGroup(METALAKE, GROUP_NAME), event.identifier());
    Assertions.assertEquals(OperationType.ADD_GROUP, event.operationType());
    Assertions.assertEquals(OperationStatus.UNPROCESSED, event.operationStatus());
    Assertions.assertEquals(EventSource.GRAVITINO_SERVER, event.eventSource());
    Assertions.assertEquals(GROUP_NAME, event.groupName());
    Assertions.assertNull(event.resourceId());
    Assertions.assertEquals(EXTERNAL_ID, event.externalId());

    Map<String, String> info = event.customInfo();
    assertScimSource(info);
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_GROUP, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertEquals(EXTERNAL_ID, info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
  }

  @Test
  public void testAddSuccessCustomInfo() {
    ScimAddGroupEvent event =
        new ScimAddGroupEvent(INITIATOR, METALAKE, GROUP_NAME, RESOURCE_ID, EXTERNAL_ID);

    Assertions.assertEquals(INITIATOR, event.user());
    Assertions.assertEquals(NameIdentifierUtil.ofGroup(METALAKE, GROUP_NAME), event.identifier());
    Assertions.assertEquals(OperationType.ADD_GROUP, event.operationType());
    Assertions.assertEquals(OperationStatus.SUCCESS, event.operationStatus());
    Assertions.assertEquals(EventSource.GRAVITINO_SERVER, event.eventSource());
    Assertions.assertEquals(GROUP_NAME, event.groupName());
    Assertions.assertEquals(RESOURCE_ID, event.resourceId());
    Assertions.assertEquals(EXTERNAL_ID, event.externalId());

    Map<String, String> info = event.customInfo();
    assertScimSource(info);
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_GROUP, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertEquals(RESOURCE_ID, info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertEquals(EXTERNAL_ID, info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
  }

  @Test
  public void testAddFailureCustomInfo() {
    Exception cause = new RuntimeException("duplicate group");
    ScimAddGroupFailureEvent event =
        new ScimAddGroupFailureEvent(INITIATOR, METALAKE, cause, GROUP_NAME, null, EXTERNAL_ID);

    Assertions.assertEquals(INITIATOR, event.user());
    Assertions.assertEquals(NameIdentifierUtil.ofGroup(METALAKE, GROUP_NAME), event.identifier());
    Assertions.assertEquals(OperationType.ADD_GROUP, event.operationType());
    Assertions.assertEquals(OperationStatus.FAILURE, event.operationStatus());
    Assertions.assertEquals(EventSource.GRAVITINO_SERVER, event.eventSource());
    Assertions.assertEquals(GROUP_NAME, event.groupName());
    Assertions.assertEquals(cause, event.exception());
    Assertions.assertNull(event.resourceId());
    Assertions.assertEquals(EXTERNAL_ID, event.externalId());

    Map<String, String> info = event.customInfo();
    assertScimSource(info);
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_GROUP, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertEquals(EXTERNAL_ID, info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
    Assertions.assertEquals("duplicate group", info.get(ScimAuditInfos.INFO_REASON));
  }

  @Test
  public void testAddOmitsBlankExternalId() {
    ScimAddGroupEvent event =
        new ScimAddGroupEvent(INITIATOR, METALAKE, GROUP_NAME, RESOURCE_ID, "  ");

    assertScimSource(event.customInfo());
    Assertions.assertNull(event.customInfo().get(ScimAuditInfos.INFO_EXTERNAL_ID));
  }

  private static void assertScimSource(Map<String, String> info) {
    Assertions.assertEquals(ScimAuditInfos.SOURCE_SCIM, info.get(ScimAuditInfos.INFO_SOURCE));
  }
}
