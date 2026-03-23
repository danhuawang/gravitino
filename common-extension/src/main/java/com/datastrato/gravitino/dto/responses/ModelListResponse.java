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
import org.apache.gravitino.dto.function.FunctionDTO;
import org.apache.gravitino.dto.model.ModelDTO;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Represents a response for a list of models with their information. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class ModelListResponse extends BaseResponse {

  @JsonProperty("models")
  private final ModelDTO[] models;

  @JsonProperty("functions")
  private final FunctionDTO[] functions;

  /**
   * Creates a new ModelListResponse.
   *
   * @param models The list of models.
   * @param functions The list of functions.
   */
  public ModelListResponse(ModelDTO[] models, FunctionDTO[] functions) {
    super(0);
    this.models = models;
    this.functions = functions;
  }

  /**
   * Creates a new ModelListResponse.
   *
   * @param models The list of models.
   */
  public ModelListResponse(ModelDTO[] models) {
    this(models, new FunctionDTO[0]);
  }

  /**
   * This is the constructor that is used by Jackson deserializer to create an instance of
   * ModelListResponse.
   */
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
