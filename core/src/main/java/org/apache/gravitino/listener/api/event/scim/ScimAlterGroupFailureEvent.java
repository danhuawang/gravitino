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

import javax.annotation.Nullable;
import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** Event triggered when a SCIM alter Group operation fails. */
@DeveloperApi
public class ScimAlterGroupFailureEvent extends ScimGroupFailureEvent {

  private final String groupName;

  /**
   * Creates a SCIM alter Group failure event.
   *
   * @param initiator authenticated principal that initiated the request
   * @param metalake target metalake
   * @param exception failure cause
   * @param groupName group name when known
   * @param resourceId SCIM / Gravitino id when known; may be null
   * @param externalId SCIM externalId; may be null
   */
  public ScimAlterGroupFailureEvent(
      String initiator,
      String metalake,
      Exception exception,
      String groupName,
      @Nullable String resourceId,
      @Nullable String externalId) {
    super(
        initiator,
        NameIdentifierUtil.ofGroup(metalake, groupName),
        exception,
        resourceId,
        externalId);
    this.groupName = groupName;
  }

  /** Returns the group name. */
  public String groupName() {
    return groupName;
  }

  @Override
  public OperationType operationType() {
    return OperationType.ALTER_GROUP;
  }
}
