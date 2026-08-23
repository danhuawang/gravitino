/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.dto.messaging.TopicDTO;
import org.apache.gravitino.dto.policy.PolicyDTO;
import org.apache.gravitino.dto.tag.TagDTO;

/** Represents a Topic DTO extended with tags and policies. */
@Getter
@ToString
@EqualsAndHashCode
public class ExtendedTopicDTO {

  @JsonUnwrapped @Nullable private final TopicDTO topicDTO;

  @JsonProperty("tags")
  @Nullable
  private final TagDTO[] tags;

  @JsonProperty("policies")
  @Nullable
  private final PolicyDTO[] policies;

  /**
   * Constructs an ExtendedTopicDTO.
   *
   * @param topicDTO The base TopicDTO.
   * @param tags Associated tags.
   * @param policies Associated policies.
   */
  public ExtendedTopicDTO(TopicDTO topicDTO, TagDTO[] tags, PolicyDTO[] policies) {
    this.topicDTO = topicDTO;
    this.tags = tags != null ? tags : new TagDTO[0];
    this.policies = policies != null ? policies : new PolicyDTO[0];
  }

  /** Default constructor for Jackson deserialization. */
  public ExtendedTopicDTO() {
    this.topicDTO = null;
    this.tags = null;
    this.policies = null;
  }

  /**
   * Returns the topic name.
   *
   * @return The topic name.
   */
  public String name() {
    return topicDTO != null ? topicDTO.name() : null;
  }

  /**
   * Validates the ExtendedTopicDTO instance.
   *
   * @throws IllegalArgumentException if required fields are missing or invalid.
   */
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(topicDTO != null, "\"topicDTO\" cannot be null");
    Preconditions.checkArgument(
        StringUtils.isNotBlank(topicDTO.name()), "topic name cannot be blank");
    Preconditions.checkArgument(tags != null, "\"tags\" cannot be null");
    Arrays.stream(tags)
        .forEach(tag -> Preconditions.checkArgument(tag != null, "tag cannot be null"));
    Preconditions.checkArgument(policies != null, "\"policies\" cannot be null");
    Arrays.stream(policies)
        .forEach(policy -> Preconditions.checkArgument(policy != null, "policy cannot be null"));
  }
}
