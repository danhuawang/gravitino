/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package org.apache.gravitino.listener.api.event.scim;

import java.util.Map;
import javax.annotation.Nullable;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.listener.api.event.EventSource;
import org.apache.gravitino.listener.api.event.PreEvent;

/** Base pre-event for SCIM Group operations. */
@DeveloperApi
public abstract class ScimGroupPreEvent extends PreEvent {

  @Nullable private final String resourceId;
  @Nullable private final String externalId;

  protected ScimGroupPreEvent(
      String initiator,
      NameIdentifier identifier,
      @Nullable String resourceId,
      @Nullable String externalId) {
    super(initiator, identifier);
    this.resourceId = resourceId;
    this.externalId = externalId;
  }

  @Override
  public EventSource eventSource() {
    return EventSource.GRAVITINO_SERVER;
  }

  @Override
  public Map<String, String> customInfo() {
    return ScimAuditInfos.of(ScimAuditInfos.RESOURCE_GROUP, resourceId, externalId);
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
