/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.rest.RESTRequest;

/**
 * Represents a request to create a topic with tags.
 *
 * <p>Note: Cannot extend TopicCreateRequest directly because it uses @Builder and @Jacksonized
 * annotations, which cannot ignore the unrecognized fields in the subclasses.
 */
@Getter
@EqualsAndHashCode
@ToString
public class TopicWithTagsCreateRequest implements RESTRequest {

  @JsonProperty("name")
  private final String name;

  @Nullable
  @JsonProperty("comment")
  private final String comment;

  @Nullable
  @JsonProperty("properties")
  private final Map<String, String> properties;

  @Nullable
  @JsonProperty("tagsToAdd")
  private final String[] tagsToAdd;

  /** Default constructor for Jackson deserialization. */
  public TopicWithTagsCreateRequest() {
    this(null, null, null, null);
  }

  /**
   * Creates a topic create request.
   *
   * @param name The name of the topic.
   * @param comment The comment of the topic.
   * @param properties The properties of the topic.
   */
  public TopicWithTagsCreateRequest(
      String name,
      @Nullable String comment,
      @Nullable Map<String, String> properties,
      @Nullable String[] tagsToAdd) {
    this.name = name;
    this.comment = comment;
    this.properties = properties;
    this.tagsToAdd = tagsToAdd;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(name), "\"name\" field is required and cannot be empty");

    if (tagsToAdd != null) {
      for (String tag : tagsToAdd) {
        Preconditions.checkArgument(
            StringUtils.isNotBlank(tag), "tagsToAdd must not contain null or empty tag names");
      }
    }
  }
}
