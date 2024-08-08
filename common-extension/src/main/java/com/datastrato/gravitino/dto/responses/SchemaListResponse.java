/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.dto.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.SchemaDTO;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Represents a response for a list of schemas with their information. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class SchemaListResponse extends BaseResponse {

  @JsonProperty("schemas")
  private final SchemaDTO[] schemas;

  /**
   * Creates a new SchemaListResponse.
   *
   * @param schemas The list of schemas.
   */
  public SchemaListResponse(SchemaDTO[] schemas) {
    super(0);
    this.schemas = schemas;
  }

  /**
   * This is the constructor that is used by Jackson deserializer to create an instance of
   * SchemaListResponse.
   */
  public SchemaListResponse() {
    super();
    this.schemas = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();

    Preconditions.checkArgument(schemas != null, "\"schemas\" cannot be null");
    Arrays.stream(schemas)
        .forEach(schema -> Preconditions.checkArgument(schema != null, "schema cannot be null"));
  }
}
