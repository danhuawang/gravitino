/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.messaging.TopicDTO;
import org.apache.gravitino.dto.responses.TopicResponse;

/** Represents a response containing topic information with tags. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class TopicWithTagsResponse extends TopicResponse {

  @JsonProperty("tags")
  private final String[] tags;

  /**
   * Constructor for TopicWithTagsResponse.
   *
   * @param topicDTO The topic data transfer object.
   * @param tags The tags for the topic.
   */
  public TopicWithTagsResponse(TopicDTO topicDTO, String[] tags) {
    super(topicDTO);
    this.tags = tags;
  }

  /** Default constructor for TopicWithTagsResponse. (Used for Jackson deserialization.) */
  public TopicWithTagsResponse() {
    super();
    this.tags = null;
  }

  /**
   * Validates the response data.
   *
   * @throws IllegalArgumentException if the topic name, type or audit is not set.
   */
  @Override
  public void validate() throws IllegalArgumentException {
    if (tags == null) {
      throw new IllegalArgumentException("\"tags\" must not be null");
    }
    super.validate();
  }
}
