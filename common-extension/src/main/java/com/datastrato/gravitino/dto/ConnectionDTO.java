/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;

/** Data transfer object representing connection information for Connect UI. */
@Getter
@ToString
@EqualsAndHashCode
public class ConnectionDTO {

  @JsonProperty("name")
  private final String name;

  @JsonProperty("type")
  private final String type;

  @JsonProperty("endpoint")
  private final String endpoint;

  @JsonProperty("credential")
  private final String credential;

  @JsonProperty("schemaCount")
  @JsonInclude(JsonInclude.Include.ALWAYS)
  @Nullable
  private final Long schemaCount;

  /**
   * Constructs a new ConnectionDTO.
   *
   * @param name The connection name.
   * @param type The connection display type.
   * @param endpoint The connection endpoint.
   * @param credential The connection credential type.
   * @param schemaCount The number of schemas in this connection, or {@code null} if the count could
   *     not be retrieved.
   */
  public ConnectionDTO(
      String name, String type, String endpoint, String credential, @Nullable Long schemaCount) {
    this.name = name;
    this.type = type;
    this.endpoint = endpoint;
    this.credential = credential;
    this.schemaCount = schemaCount;
  }

  /** Default constructor for Jackson deserialization. */
  public ConnectionDTO() {
    this(null, null, null, null, null);
  }

  /**
   * Validates the ConnectionDTO instance.
   *
   * <p>A {@code null} schema count is valid and indicates that the count could not be retrieved.
   *
   * @throws IllegalArgumentException if required fields are missing.
   */
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(StringUtils.isNotBlank(name), "\"name\" cannot be blank");
    Preconditions.checkArgument(StringUtils.isNotBlank(type), "\"type\" cannot be blank");
    Preconditions.checkArgument(StringUtils.isNotBlank(endpoint), "\"endpoint\" cannot be blank");
    Preconditions.checkArgument(
        StringUtils.isNotBlank(credential), "\"credential\" cannot be blank");
    if (schemaCount != null) {
      Preconditions.checkArgument(schemaCount >= 0, "\"schemaCount\" cannot be negative");
    }
  }
}
