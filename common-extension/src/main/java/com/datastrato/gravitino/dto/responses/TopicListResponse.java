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
import org.apache.gravitino.dto.messaging.TopicDTO;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Represents a response for a list of topics with their information. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class TopicListResponse extends BaseResponse {
  @JsonProperty("topics")
  private final TopicDTO[] topics;

  @JsonProperty("functions")
  private final FunctionDTO[] functions;

  /**
   * Creates a new TopicListResponse.
   *
   * @param topics The list of topics.
   * @param functions The list of functions.
   */
  public TopicListResponse(TopicDTO[] topics, FunctionDTO[] functions) {
    super(0);
    this.topics = topics;
    this.functions = functions;
  }

  /**
   * Creates a new TopicListResponse.
   *
   * @param topics The list of topics.
   */
  public TopicListResponse(TopicDTO[] topics) {
    this(topics, new FunctionDTO[0]);
  }

  /**
   * This is the constructor that is used by Jackson deserializer to create an instance of
   * TopicListResponse.
   */
  public TopicListResponse() {
    super();
    this.topics = null;
    this.functions = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();

    Preconditions.checkArgument(topics != null, "\"topics\" cannot be null");
    Arrays.stream(topics)
        .forEach(topic -> Preconditions.checkArgument(topic != null, "topic cannot be null"));
    Preconditions.checkArgument(functions != null, "\"functions\" cannot be null");
    Arrays.stream(functions)
        .forEach(
            function -> Preconditions.checkArgument(function != null, "function cannot be null"));
  }
}
