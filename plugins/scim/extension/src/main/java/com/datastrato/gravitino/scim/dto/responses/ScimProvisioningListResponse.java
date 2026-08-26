/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.dto.responses;

import com.datastrato.gravitino.scim.dto.ScimProvisioningDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response for the SCIM Provisioning metalake overview list. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class ScimProvisioningListResponse extends BaseResponse {

  @JsonProperty("metalakes")
  private final List<ScimProvisioningDTO> metalakes;

  /**
   * Creates a list response.
   *
   * @param metalakes provisioning rows
   */
  public ScimProvisioningListResponse(List<ScimProvisioningDTO> metalakes) {
    super(0);
    this.metalakes = metalakes;
  }

  /** Default constructor for Jackson deserialization. */
  public ScimProvisioningListResponse() {
    super();
    this.metalakes = Collections.emptyList();
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();
    Preconditions.checkArgument(metalakes != null, "metalakes must not be null");
  }
}
