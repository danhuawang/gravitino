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

import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** Event triggered before a SCIM list/find Groups operation. */
@DeveloperApi
public class ScimListGroupsPreEvent extends ScimGroupPreEvent {

  private final int startIndex;
  private final int count;

  /**
   * Creates a SCIM list Groups pre-event.
   *
   * @param initiator authenticated principal that initiated the request
   * @param metalake target metalake
   * @param startIndex SCIM 1-based startIndex; 0 when unset
   * @param count requested page size; 0 when unset
   */
  public ScimListGroupsPreEvent(String initiator, String metalake, int startIndex, int count) {
    super(initiator, NameIdentifierUtil.ofMetalake(metalake), null, null);
    this.startIndex = startIndex;
    this.count = count;
  }

  /** Returns the SCIM startIndex. */
  public int startIndex() {
    return startIndex;
  }

  /** Returns the requested page size. */
  public int count() {
    return count;
  }

  @Override
  public OperationType operationType() {
    return OperationType.LIST_GROUPS_PAGED;
  }
}
