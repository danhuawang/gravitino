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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Unit tests for SCIM User audit event customInfo (Get / List). */
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
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_USER, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertEquals(RESOURCE_ID, info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
    Assertions.assertEquals(
        ScimAuditInfos.STATUS_UNPROCESSED, info.get(ScimAuditInfos.INFO_STATUS));
  }

  @Test
  public void testGetSuccessCustomInfo() {
    ScimGetUserEvent event =
        new ScimGetUserEvent(INITIATOR, METALAKE, USER_NAME, RESOURCE_ID, EXTERNAL_ID);

    Map<String, String> info = event.customInfo();
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_USER, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertEquals(RESOURCE_ID, info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertEquals(EXTERNAL_ID, info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
    Assertions.assertEquals(ScimAuditInfos.STATUS_SUCCESS, info.get(ScimAuditInfos.INFO_STATUS));
  }

  @Test
  public void testGetSuccessOmitsBlankIds() {
    ScimGetUserEvent event = new ScimGetUserEvent(INITIATOR, METALAKE, USER_NAME, "  ", null);

    Map<String, String> info = event.customInfo();
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
    Assertions.assertEquals(ScimAuditInfos.STATUS_SUCCESS, info.get(ScimAuditInfos.INFO_STATUS));
  }

  @Test
  public void testGetFailureCustomInfo() {
    ScimGetUserFailureEvent event =
        new ScimGetUserFailureEvent(
            INITIATOR, METALAKE, new RuntimeException("missing"), RESOURCE_ID);

    Map<String, String> info = event.customInfo();
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_USER, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertEquals(RESOURCE_ID, info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
    Assertions.assertEquals(ScimAuditInfos.STATUS_FAILURE, info.get(ScimAuditInfos.INFO_STATUS));
  }

  @Test
  public void testListPreCustomInfo() {
    ScimListUsersPreEvent event = new ScimListUsersPreEvent(INITIATOR, METALAKE, 1, 10);

    Map<String, String> info = event.customInfo();
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_USER, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
    Assertions.assertEquals(
        ScimAuditInfos.STATUS_UNPROCESSED, info.get(ScimAuditInfos.INFO_STATUS));
  }

  @Test
  public void testListSuccessCustomInfo() {
    ScimListUsersEvent event = new ScimListUsersEvent(INITIATOR, METALAKE, 1, 10, 2, 5L);

    Map<String, String> info = event.customInfo();
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_USER, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
    Assertions.assertEquals(ScimAuditInfos.STATUS_SUCCESS, info.get(ScimAuditInfos.INFO_STATUS));
  }

  @Test
  public void testListFailureCustomInfo() {
    ScimListUsersFailureEvent event =
        new ScimListUsersFailureEvent(INITIATOR, METALAKE, new RuntimeException("boom"), 1, 10);

    Map<String, String> info = event.customInfo();
    Assertions.assertEquals(
        ScimAuditInfos.RESOURCE_USER, info.get(ScimAuditInfos.INFO_RESOURCE_TYPE));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_ID));
    Assertions.assertNull(info.get(ScimAuditInfos.INFO_EXTERNAL_ID));
    Assertions.assertEquals(ScimAuditInfos.STATUS_FAILURE, info.get(ScimAuditInfos.INFO_STATUS));
  }
}
