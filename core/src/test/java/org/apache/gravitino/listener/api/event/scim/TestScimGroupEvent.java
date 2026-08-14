/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.gravitino.listener.api.event.scim;

import java.util.Map;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.listener.api.event.EventSource;
import org.apache.gravitino.listener.api.event.OperationStatus;
import org.apache.gravitino.listener.api.event.OperationType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Unit tests for SCIM Group audit events (Get / List). */
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
    Assertions.assertEquals(
        ScimAuditInfos.STATUS_UNPROCESSED, info.get(ScimAuditInfos.INFO_STATUS));
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
    Assertions.assertEquals(ScimAuditInfos.STATUS_SUCCESS, info.get(ScimAuditInfos.INFO_STATUS));
    Assertions.assertEquals(GROUP_NAME, event.groupName());
  }

  @Test
  public void testGetSuccessOmitsBlankIds() {
    ScimGetGroupEvent event = new ScimGetGroupEvent(INITIATOR, METALAKE, GROUP_NAME, "  ", null);

    Map<String, String> info = event.customInfo();
    assertScimSource(info);
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
    Assertions.assertEquals(ScimAuditInfos.STATUS_SUCCESS, info.get(ScimAuditInfos.INFO_STATUS));
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
    Assertions.assertEquals(ScimAuditInfos.STATUS_FAILURE, info.get(ScimAuditInfos.INFO_STATUS));
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
    Assertions.assertEquals(
        ScimAuditInfos.STATUS_UNPROCESSED, info.get(ScimAuditInfos.INFO_STATUS));
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
    Assertions.assertEquals(ScimAuditInfos.STATUS_SUCCESS, info.get(ScimAuditInfos.INFO_STATUS));
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
    Assertions.assertEquals(ScimAuditInfos.STATUS_FAILURE, info.get(ScimAuditInfos.INFO_STATUS));
  }

  private static void assertScimSource(Map<String, String> info) {
    Assertions.assertEquals(ScimAuditInfos.SOURCE_SCIM, info.get(ScimAuditInfos.INFO_SOURCE));
  }
}
