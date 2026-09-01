/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.dto.responses;

import com.datastrato.gravitino.scim.v2.dto.ScimProvisioningDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** SCIM v2 provisioning overview response. */
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor
public class ScimProvisioningListResponse {
  @JsonProperty("provisioning")
  private List<ScimProvisioningDTO> provisioning;
}
