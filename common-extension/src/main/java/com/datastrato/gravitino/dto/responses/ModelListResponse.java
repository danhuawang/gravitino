/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.function.ExtendedFunctionDTO;
import com.datastrato.gravitino.dto.model.ExtendedModelDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Represents a response for a list of extended models with their information. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class ModelListResponse extends BaseResponse {

  @JsonProperty("models")
  private final ExtendedModelDTO[] models;

  @JsonProperty("functions")
  private final ExtendedFunctionDTO[] functions;

  /**
   * Creates a new ModelListResponse.
   *
   * @param models The list of extended models.
   * @param functions The list of extended functions.
   */
  public ModelListResponse(ExtendedModelDTO[] models, ExtendedFunctionDTO[] functions) {
    super(0);
    this.models = models;
    this.functions = functions;
  }

  /**
   * Creates a new ModelListResponse.
   *
   * @param models The list of extended models.
   */
  public ModelListResponse(ExtendedModelDTO[] models) {
    this(models, new ExtendedFunctionDTO[0]);
  }

  /** Default constructor for Jackson deserialization. */
  public ModelListResponse() {
    this.models = null;
    this.functions = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();

    Preconditions.checkArgument(models != null, "\"models\" cannot be null");
    Arrays.stream(models)
        .forEach(model -> Preconditions.checkArgument(model != null, "model cannot be null"));
    Preconditions.checkArgument(functions != null, "\"functions\" cannot be null");
    Arrays.stream(functions)
        .forEach(
            function -> Preconditions.checkArgument(function != null, "function cannot be null"));
  }
}
