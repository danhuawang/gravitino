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

import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** Event triggered before a SCIM get Group operation. */
@DeveloperApi
public class ScimGetGroupPreEvent extends ScimGroupPreEvent {

  /**
   * Creates a SCIM get Group pre-event.
   *
   * @param initiator authenticated principal that initiated the request
   * @param metalake target metalake
   * @param resourceId SCIM / Gravitino id being retrieved
   */
  public ScimGetGroupPreEvent(String initiator, String metalake, String resourceId) {
    super(initiator, identifierFor(metalake, resourceId), resourceId, null);
  }

  @Override
  public OperationType operationType() {
    return OperationType.GET_GROUP_BY_ID;
  }

  private static NameIdentifier identifierFor(String metalake, String resourceId) {
    try {
      return AuthorizationUtils.ofGroupId(metalake, Long.parseLong(resourceId));
    } catch (NumberFormatException e) {
      return NameIdentifierUtil.ofMetalake(metalake);
    }
  }
}
