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
import javax.annotation.Nullable;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.listener.api.event.EventSource;
import org.apache.gravitino.listener.api.event.OperationStatus;

/** Base post-event for successful SCIM Group operations. */
@DeveloperApi
public abstract class ScimGroupEvent extends Event {

  @Nullable private final String resourceId;
  @Nullable private final String externalId;

  protected ScimGroupEvent(
      String initiator,
      NameIdentifier identifier,
      @Nullable String resourceId,
      @Nullable String externalId) {
    super(initiator, identifier);
    this.resourceId = resourceId;
    this.externalId = externalId;
  }

  @Override
  public OperationStatus operationStatus() {
    return OperationStatus.SUCCESS;
  }

  @Override
  public EventSource eventSource() {
    return EventSource.GRAVITINO_SERVER;
  }

  @Override
  public Map<String, String> customInfo() {
    return ScimAuditInfos.of(
        ScimAuditInfos.RESOURCE_GROUP, resourceId, externalId, ScimAuditInfos.STATUS_SUCCESS);
  }

  /** Returns the SCIM / Gravitino resource id, or null when unknown. */
  @Nullable
  public String resourceId() {
    return resourceId;
  }

  /** Returns the SCIM externalId, or null when absent. */
  @Nullable
  public String externalId() {
    return externalId;
  }
}
