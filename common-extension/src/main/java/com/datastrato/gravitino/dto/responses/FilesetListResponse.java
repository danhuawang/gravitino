/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.file.ExtendedFilesetDTO;
import com.datastrato.gravitino.dto.function.ExtendedFunctionDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Represents a response for a list of extended filesets with their information. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class FilesetListResponse extends BaseResponse {

  @JsonProperty("filesets")
  private final ExtendedFilesetDTO[] filesets;

  @JsonProperty("functions")
  private final ExtendedFunctionDTO[] functions;

  /**
   * Creates a new FilesetListResponse.
   *
   * @param filesets The list of extended filesets.
   * @param functions The list of extended functions.
   */
  public FilesetListResponse(ExtendedFilesetDTO[] filesets, ExtendedFunctionDTO[] functions) {
    super(0);
    this.filesets = filesets;
    this.functions = functions;
  }

  /**
   * Creates a new FilesetListResponse.
   *
   * @param filesets The list of extended filesets.
   */
  public FilesetListResponse(ExtendedFilesetDTO[] filesets) {
    this(filesets, new ExtendedFunctionDTO[0]);
  }

  /** Default constructor for Jackson deserialization. */
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
