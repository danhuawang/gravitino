/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.ConnectionOverviewDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response containing one Connect Catalog overview. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class ConnectionOverviewResponse extends BaseResponse {
  @JsonProperty("connection")
  private final ConnectionOverviewDTO connection;

  /**
   * Creates a successful connection overview response.
   *
   * @param connection The connection overview.
   */
  public ConnectionOverviewResponse(ConnectionOverviewDTO connection) {
    super(0);
    this.connection = connection;
  }

  /** Creates an empty response for Jackson deserialization. */
  public ConnectionOverviewResponse() {
    super();
    this.connection = null;
  }

  /**
   * Validates the response and nested connection overview.
   *
   * @throws IllegalArgumentException If the response is invalid.
   */
  @Override
  public void validate() {
    super.validate();
    Preconditions.checkArgument(connection != null, "connection cannot be null");
    connection.validate();
  }
}
