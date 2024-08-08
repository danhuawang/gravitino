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
import org.apache.gravitino.dto.messaging.TopicDTO;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Represents a response for a list of topics with their information. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class TopicListResponse extends BaseResponse {
  @JsonProperty("topics")
  private final TopicDTO[] topics;

  /**
   * Creates a new TopicListResponse.
   *
   * @param topics The list of topics.
   */
  public TopicListResponse(TopicDTO[] topics) {
    super(0);
    this.topics = topics;
  }

  /**
   * This is the constructor that is used by Jackson deserializer to create an instance of
   * TopicListResponse.
   */
  public TopicListResponse() {
    super();
    this.topics = null;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();

    Preconditions.checkArgument(topics != null, "\"topics\" cannot be null");
    Arrays.stream(topics)
        .forEach(topic -> Preconditions.checkArgument(topic != null, "topic cannot be null"));
  }
}
