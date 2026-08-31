/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.ConnectionDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Represents a response containing connections and summary counts for Connect UI. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class ConnectionListResponse extends BaseResponse {

  @JsonProperty("connections")
  private final ConnectionDTO[] connections;

  @JsonProperty("catalogCount")
  private final Integer catalogCount;

  @JsonProperty("systemCount")
  private final Integer systemCount;

  /**
   * Creates a new ConnectionListResponse.
   *
   * @param connections The array of connections.
   * @param catalogCount The total catalog count.
   * @param systemCount The distinct system count.
   */
  public ConnectionListResponse(
      ConnectionDTO[] connections, Integer catalogCount, Integer systemCount) {
    super(0);
    this.connections = connections != null ? connections : new ConnectionDTO[0];
    this.catalogCount = catalogCount != null ? catalogCount : this.connections.length;
    this.systemCount = systemCount != null ? systemCount : 0;
  }

  /** Default constructor for Jackson deserialization. */
  public ConnectionListResponse() {
    super();
    this.connections = null;
    this.catalogCount = null;
    this.systemCount = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();

    Preconditions.checkArgument(connections != null, "\"connections\" cannot be null");
    Arrays.stream(connections)
        .forEach(
            connection -> {
              Preconditions.checkArgument(connection != null, "connection cannot be null");
              connection.validate();
            });
    if (catalogCount != null) {
      Preconditions.checkArgument(catalogCount >= 0, "\"catalogCount\" cannot be negative");
    }
    if (systemCount != null) {
      Preconditions.checkArgument(systemCount >= 0, "\"systemCount\" cannot be negative");
    }
  }
}
