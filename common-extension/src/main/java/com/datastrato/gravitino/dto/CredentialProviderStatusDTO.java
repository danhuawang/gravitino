/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;

/** Latest test status for one credential provider configured on a Catalog. */
@Getter
@ToString
@EqualsAndHashCode
public class CredentialProviderStatusDTO {

  @JsonProperty("type")
  private final String type;

  @JsonProperty("testStatus")
  private final ConnectionTestStatusDTO testStatus;

  /**
   * Creates a credential provider status.
   *
   * @param type The canonical credential provider type.
   * @param testStatus The latest valid manual test status.
   */
  public CredentialProviderStatusDTO(String type, ConnectionTestStatusDTO testStatus) {
    this.type = type;
    this.testStatus = testStatus;
  }

  /** Creates an empty instance for Jackson deserialization. */
  public CredentialProviderStatusDTO() {
    this(null, null);
  }

  /**
   * Validates the credential provider status.
   *
   * @throws IllegalArgumentException If a required field is missing or invalid.
   */
  public void validate() {
    Preconditions.checkArgument(StringUtils.isNotBlank(type), "type cannot be blank");
    Preconditions.checkArgument(testStatus != null, "testStatus cannot be null");
    testStatus.validate();
  }
}
