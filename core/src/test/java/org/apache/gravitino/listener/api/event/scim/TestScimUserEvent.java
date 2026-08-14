/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package org.apache.gravitino.listener.api.event.scim;

import java.util.Map;
import org.apache.gravitino.listener.api.event.EventSource;
import org.apache.gravitino.listener.api.event.OperationStatus;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Unit tests for SCIM User audit events (Get / List / Add). */
public class TestScimUserEvent {

  private static final String METALAKE = "ml1";
  private static final String INITIATOR = "entra-prod";
  private static final String USER_NAME = "alice";
  private static final String RESOURCE_ID = "42";
  private static final String EXTERNAL_ID = "ext-1";

  @Test
  public void testGetPreCustomInfo() {
    ScimGetUserPreEvent event = new ScimGetUserPreEvent(INITIATOR, METALAKE, RESOURCE_ID);

    Map<String, String> info = event.customInfo();
    assertScimSource(info);
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_USER, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertEquals(RESOURCE_ID, info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
  }

  @Test
  public void testGetSuccessCustomInfo() {
    ScimGetUserEvent event =
        new ScimGetUserEvent(INITIATOR, METALAKE, USER_NAME, RESOURCE_ID, EXTERNAL_ID);

    Map<String, String> info = event.customInfo();
    assertScimSource(info);
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_USER, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertEquals(RESOURCE_ID, info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertEquals(EXTERNAL_ID, info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
  }

  @Test
  public void testGetSuccessOmitsBlankIds() {
    ScimGetUserEvent event = new ScimGetUserEvent(INITIATOR, METALAKE, USER_NAME, "  ", null);

    Map<String, String> info = event.customInfo();
    assertScimSource(info);
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
  }

  @Test
  public void testGetFailureCustomInfo() {
    ScimGetUserFailureEvent event =
        new ScimGetUserFailureEvent(
            INITIATOR, METALAKE, new RuntimeException("missing"), RESOURCE_ID);

    Map<String, String> info = event.customInfo();
    assertScimSource(info);
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_USER, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertEquals(RESOURCE_ID, info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
    Assertions.assertEquals("missing", info.get(ScimAuditInfos.INFO_REASON));
  }

  @Test
  public void testListPreCustomInfo() {
    ScimListUsersPreEvent event = new ScimListUsersPreEvent(INITIATOR, METALAKE, 1, 10);

    Map<String, String> info = event.customInfo();
    assertScimSource(info);
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_USER, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
  }

  @Test
  public void testListSuccessCustomInfo() {
    ScimListUsersEvent event = new ScimListUsersEvent(INITIATOR, METALAKE, 1, 10, 2, 5L);

    Map<String, String> info = event.customInfo();
    assertScimSource(info);
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_USER, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
    Assertions.assertEquals("2", info.get(ScimAuditInfos.INFO_COUNT));
  }

  @Test
  public void testListFailureCustomInfo() {
    ScimListUsersFailureEvent event =
        new ScimListUsersFailureEvent(INITIATOR, METALAKE, new RuntimeException("boom"), 1, 10);

    Map<String, String> info = event.customInfo();
    assertScimSource(info);
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_USER, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
    Assertions.assertEquals("boom", info.get(ScimAuditInfos.INFO_REASON));
  }

  @Test
  public void testAddPreCustomInfo() {
    ScimAddUserPreEvent event =
        new ScimAddUserPreEvent(INITIATOR, METALAKE, USER_NAME, null, EXTERNAL_ID);

    Assertions.assertEquals(INITIATOR, event.user());
    Assertions.assertEquals(NameIdentifierUtil.ofUser(METALAKE, USER_NAME), event.identifier());
    Assertions.assertEquals(OperationType.ADD_USER, event.operationType());
    Assertions.assertEquals(OperationStatus.UNPROCESSED, event.operationStatus());
    Assertions.assertEquals(EventSource.GRAVITINO_SERVER, event.eventSource());
    Assertions.assertEquals(USER_NAME, event.userName());
    Assertions.assertNull(event.resourceId());
    Assertions.assertEquals(EXTERNAL_ID, event.externalId());

    Map<String, String> info = event.customInfo();
    assertScimSource(info);
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_USER, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertEquals(EXTERNAL_ID, info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
  }

  @Test
  public void testAddSuccessCustomInfo() {
    ScimAddUserEvent event =
        new ScimAddUserEvent(INITIATOR, METALAKE, USER_NAME, RESOURCE_ID, EXTERNAL_ID);

    Assertions.assertEquals(INITIATOR, event.user());
    Assertions.assertEquals(NameIdentifierUtil.ofUser(METALAKE, USER_NAME), event.identifier());
    Assertions.assertEquals(OperationType.ADD_USER, event.operationType());
    Assertions.assertEquals(OperationStatus.SUCCESS, event.operationStatus());
    Assertions.assertEquals(EventSource.GRAVITINO_SERVER, event.eventSource());
    Assertions.assertEquals(USER_NAME, event.userName());
    Assertions.assertEquals(RESOURCE_ID, event.resourceId());
    Assertions.assertEquals(EXTERNAL_ID, event.externalId());

    Map<String, String> info = event.customInfo();
    assertScimSource(info);
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_USER, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertEquals(RESOURCE_ID, info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertEquals(EXTERNAL_ID, info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
  }

  @Test
  public void testAddFailureCustomInfo() {
    Exception cause = new RuntimeException("duplicate user");
    ScimAddUserFailureEvent event =
        new ScimAddUserFailureEvent(INITIATOR, METALAKE, cause, USER_NAME, null, EXTERNAL_ID);

    Assertions.assertEquals(INITIATOR, event.user());
    Assertions.assertEquals(NameIdentifierUtil.ofUser(METALAKE, USER_NAME), event.identifier());
    Assertions.assertEquals(OperationType.ADD_USER, event.operationType());
    Assertions.assertEquals(OperationStatus.FAILURE, event.operationStatus());
    Assertions.assertEquals(EventSource.GRAVITINO_SERVER, event.eventSource());
    Assertions.assertEquals(USER_NAME, event.userName());
    Assertions.assertEquals(cause, event.exception());
    Assertions.assertNull(event.resourceId());
    Assertions.assertEquals(EXTERNAL_ID, event.externalId());

    Map<String, String> info = event.customInfo();
    assertScimSource(info);
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_USER, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertEquals(EXTERNAL_ID, info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
    Assertions.assertEquals("duplicate user", info.get(ScimAuditInfos.INFO_REASON));
  }

  @Test
  public void testAddOmitsBlankExternalId() {
    ScimAddUserEvent event =
        new ScimAddUserEvent(INITIATOR, METALAKE, USER_NAME, RESOURCE_ID, "  ");

    assertScimSource(event.customInfo());
    Assertions.assertNull(event.customInfo().get(ScimAuditInfos.INFO_EXTERNAL_ID));
  }

  private static void assertScimSource(Map<String, String> info) {
    Assertions.assertEquals(ScimAuditInfos.SOURCE_SCIM, info.get(ScimAuditInfos.INFO_SOURCE));
  }
}
