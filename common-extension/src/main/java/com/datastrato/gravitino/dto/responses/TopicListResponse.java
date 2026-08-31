/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.function.ExtendedFunctionDTO;
import com.datastrato.gravitino.dto.messaging.ExtendedTopicDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Represents a response for a list of extended topics with their information. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class TopicListResponse extends BaseResponse {
  @JsonProperty("topics")
  private final ExtendedTopicDTO[] topics;

  @JsonProperty("functions")
  private final ExtendedFunctionDTO[] functions;

  /**
   * Creates a new TopicListResponse.
   *
   * @param topics The list of extended topics.
   * @param functions The list of extended functions.
   */
  public TopicListResponse(ExtendedTopicDTO[] topics, ExtendedFunctionDTO[] functions) {
    super(0);
    this.topics = topics;
    this.functions = functions;
  }

  /**
   * Creates a new TopicListResponse.
   *
   * @param topics The list of extended topics.
   */
  public TopicListResponse(ExtendedTopicDTO[] topics) {
    this(topics, new ExtendedFunctionDTO[0]);
  }

  /** Default constructor for Jackson deserialization. */
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
