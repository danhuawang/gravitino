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
import org.apache.gravitino.dto.file.FilesetDTO;
import org.apache.gravitino.dto.function.FunctionDTO;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Represents a response for a list of filesets with their information. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class FilesetListResponse extends BaseResponse {

  @JsonProperty("filesets")
  private final FilesetDTO[] filesets;

  @JsonProperty("functions")
  private final FunctionDTO[] functions;

  /**
   * Creates a new FilesetListResponse.
   *
   * @param filesets The list of filesets.
   * @param functions The list of functions.
   */
  public FilesetListResponse(FilesetDTO[] filesets, FunctionDTO[] functions) {
    super(0);
    this.filesets = filesets;
    this.functions = functions;
  }

  /**
   * Creates a new FilesetListResponse.
   *
   * @param filesets The list of filesets.
   */
  public FilesetListResponse(FilesetDTO[] filesets) {
    this(filesets, new FunctionDTO[0]);
  }

  /**
   * This is the constructor that is used by Jackson deserializer to create an instance of
   * FilesetListResponse.
   */
  public FilesetListResponse() {
    super();
    this.filesets = null;
    this.functions = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();

    Preconditions.checkArgument(filesets != null, "\"filesets\" cannot be null");
    Arrays.stream(filesets)
        .forEach(fileset -> Preconditions.checkArgument(fileset != null, "fileset cannot be null"));
    Preconditions.checkArgument(functions != null, "\"functions\" cannot be null");
    Arrays.stream(functions)
        .forEach(
            function -> Preconditions.checkArgument(function != null, "function cannot be null"));
  }
}
