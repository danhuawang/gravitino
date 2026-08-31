/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/**
 * Response containing aggregate information about a metalake.
 *
 * <p>The counts are read directly from Gravitino rather than from the search index so that they are
 * available regardless of the search configuration and do not depend on asynchronous index updates.
 */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class MetalakeSummaryResponse extends BaseResponse {

  @JsonProperty("catalogCount")
  private final long catalogCount;

  /** Null when access control is disabled because user data is unavailable. */
  @Nullable
  @JsonProperty("userCount")
  private final Long userCount;

  /** Null when access control is disabled because role data is unavailable. */
  @Nullable
  @JsonProperty("roleCount")
  private final Long roleCount;

  /**
   * Creates a metalake summary response.
   *
   * @param catalogCount The non-null number of catalogs in the metalake.
   * @param userCount The number of users in the metalake, null when access control is disabled.
   * @param roleCount The number of roles in the metalake, null when access control is disabled.
   */
  @JsonCreator
  public MetalakeSummaryResponse(
      @JsonProperty("catalogCount") Long catalogCount,
      @Nullable @JsonProperty("userCount") Long userCount,
      @Nullable @JsonProperty("roleCount") Long roleCount) {
    super(0);
    Preconditions.checkArgument(catalogCount != null, "catalogCount cannot be null");
    Preconditions.checkArgument(catalogCount >= 0, "catalogCount cannot be negative");
    Preconditions.checkArgument(
        userCount == null || userCount >= 0, "userCount cannot be negative");
    Preconditions.checkArgument(
        roleCount == null || roleCount >= 0, "roleCount cannot be negative");
    this.catalogCount = catalogCount;
    this.userCount = userCount;
    this.roleCount = roleCount;
  }
}
