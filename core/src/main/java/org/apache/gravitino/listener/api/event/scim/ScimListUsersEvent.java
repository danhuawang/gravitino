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
import org.apache.gravitino.listener.api.event.ListEvent;
import org.apache.gravitino.listener.api.event.OperationType;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** Event triggered after a successful SCIM list/find Users operation. */
@DeveloperApi
public class ScimListUsersEvent extends ScimUserEvent implements ListEvent {

  private final int startIndex;
  private final int count;
  private final int pageSize;
  private final long totalCount;

  /**
   * Creates a SCIM list Users success event.
   *
   * @param initiator authenticated principal that initiated the request
   * @param metalake target metalake
   * @param startIndex SCIM 1-based startIndex; 0 when unset
   * @param count requested page size; 0 when unset
   * @param pageSize number of users returned in this page
   * @param totalCount total matching users
   */
  public ScimListUsersEvent(
      String initiator, String metalake, int startIndex, int count, int pageSize, long totalCount) {
    super(initiator, NameIdentifierUtil.ofMetalake(metalake), null, null);
    this.startIndex = startIndex;
    this.count = count;
    this.pageSize = pageSize;
    this.totalCount = totalCount;
  }

  /** Returns the SCIM startIndex. */
  public int startIndex() {
    return startIndex;
  }

  /** Returns the requested page size. */
  public int count() {
    return count;
  }

  /** Returns the total matching users. */
  public long totalCount() {
    return totalCount;
  }

  @Override
  public int resultCount() {
    return pageSize;
  }

  @Override
  public OperationType operationType() {
    return OperationType.LIST_USERS_PAGED;
  }
}
