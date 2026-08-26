/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package org.apache.gravitino.listener.api.event.scim;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.listener.api.event.EventSource;
import org.apache.gravitino.listener.api.event.OperationStatus;

/** Base post-event for successful SCIM User operations. */
@DeveloperApi
public abstract class ScimUserEvent extends Event {

  @Nullable private final String resourceId;
  @Nullable private final String externalId;
  @Nullable private final Map<String, String> extraInfo;

  protected ScimUserEvent(
      String initiator,
      NameIdentifier identifier,
      @Nullable String resourceId,
      @Nullable String externalId) {
    this(initiator, identifier, resourceId, externalId, null);
  }

  protected ScimUserEvent(
      String initiator,
      NameIdentifier identifier,
      @Nullable String resourceId,
      @Nullable String externalId,
      @Nullable Map<String, String> extraInfo) {
    super(initiator, identifier);
    this.resourceId = resourceId;
    this.externalId = externalId;
    this.extraInfo = extraInfo == null ? null : ImmutableMap.copyOf(extraInfo);
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
  public String remoteAddress() {
    String address = super.remoteAddress();
    return "unknown".equals(address) ? "" : address;
  }

  @Override
  public Map<String, String> customInfo() {
    return ScimAuditInfos.of(ScimAuditInfos.RESOURCE_USER, resourceId, externalId, extraInfo);
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
