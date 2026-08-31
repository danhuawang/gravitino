/*
 * Copyright 2026 Datastrato Inc.
 */

package org.apache.gravitino.listener.api.event.scim;

import java.util.Map;
import javax.annotation.Nullable;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.listener.api.event.EventSource;
import org.apache.gravitino.listener.api.event.FailureEvent;

/** Base failure event for SCIM Group operations. */
@DeveloperApi
public abstract class ScimGroupFailureEvent extends FailureEvent {

  @Nullable private final String resourceId;
  @Nullable private final String externalId;

  protected ScimGroupFailureEvent(
      String initiator,
      NameIdentifier identifier,
      Exception exception,
      @Nullable String resourceId,
      @Nullable String externalId) {
    super(initiator, identifier, exception);
    this.resourceId = resourceId;
    this.externalId = externalId;
  }

  @Override
  public EventSource eventSource() {
    return EventSource.GRAVITINO_SERVER;
  }

  @Override
  public String remoteAddress() {
    String address = super.remoteAddress();
    return "unknown".equals(address) ? "" : address;
  }

  @Override
  public Map<String, String> customInfo() {
    return ScimAuditInfos.ofFailure(
        ScimAuditInfos.RESOURCE_GROUP, resourceId, externalId, exception());
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
