/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.dto.responses;

import com.datastrato.gravitino.scim.dto.ScimTokenSummaryDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response for listing active SCIM tokens in a metalake. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class ScimTokenListResponse extends BaseResponse {

  @JsonProperty("tokens")
  private final List<ScimTokenSummaryDTO> tokens;

  /**
   * Creates a list response.
   *
   * @param tokens token rows
   */
  public ScimTokenListResponse(List<ScimTokenSummaryDTO> tokens) {
    super(0);
    this.tokens = tokens;
  }

  /** Default constructor for Jackson deserialization. */
  public ScimTokenListResponse() {
    super();
    this.tokens = Collections.emptyList();
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();
    Preconditions.checkArgument(tokens != null, "tokens must not be null");
  }
}
