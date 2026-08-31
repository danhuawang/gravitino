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

/** Safe application error returned for a failed connection test. */
@Getter
@ToString
@EqualsAndHashCode
public class ConnectionTestErrorDTO {
  @JsonProperty("code")
  private final Integer code;

  @JsonProperty("type")
  private final String type;

  @JsonProperty("message")
  private final String message;

  /**
   * Creates a safe connection test error.
   *
   * @param code The application error code.
   * @param type The application error type.
   * @param message The safe display message.
   */
  public ConnectionTestErrorDTO(Integer code, String type, String message) {
    this.code = code;
    this.type = type;
    this.message = message;
  }

  /** Creates an empty instance for Jackson deserialization. */
  public ConnectionTestErrorDTO() {
    this(null, null, null);
  }

  /**
   * Validates the safe error fields.
   *
   * @throws IllegalArgumentException If a required field is missing.
   */
  public void validate() {
    Preconditions.checkArgument(code != null && code > 0, "error code must be positive");
    Preconditions.checkArgument(StringUtils.isNotBlank(type), "error type cannot be blank");
    Preconditions.checkArgument(StringUtils.isNotBlank(message), "error message cannot be blank");
  }
}
