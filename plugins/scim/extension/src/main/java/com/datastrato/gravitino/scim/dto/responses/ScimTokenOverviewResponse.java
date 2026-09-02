/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.dto.responses;

import com.datastrato.gravitino.scim.dto.ScimTokenOverviewItemDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response for the SCIM token overview API. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class ScimTokenOverviewResponse extends BaseResponse {

  @JsonProperty("lastUsedAt")
  private final long lastUsedAt;

  @JsonProperty("tokenCount")
  private final long tokenCount;

  @JsonProperty("tokens")
  private final List<ScimTokenOverviewItemDTO> tokens;

  /**
   * Creates an overview response.
   *
   * @param lastUsedAt max last use epoch millis across active tokens
   * @param tokenCount number of active tokens
   * @param tokens token rows
   */
  public ScimTokenOverviewResponse(
      long lastUsedAt, long tokenCount, List<ScimTokenOverviewItemDTO> tokens) {
    super(0);
    this.lastUsedAt = lastUsedAt;
    this.tokenCount = tokenCount;
    this.tokens = tokens;
  }

  /** Default constructor for Jackson deserialization. */
  public ScimTokenOverviewResponse() {
    super();
    this.lastUsedAt = 0L;
    this.tokenCount = 0L;
    this.tokens = Collections.emptyList();
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();
    Preconditions.checkArgument(tokens != null, "tokens must not be null");
    Preconditions.checkArgument(tokenCount >= 0, "tokenCount must not be negative");
  }
}
