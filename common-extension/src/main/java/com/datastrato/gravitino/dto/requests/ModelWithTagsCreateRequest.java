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
 * Represents a request to create a model with tags.
 *
 * <p>Note: Cannot extend ModelCreateRequest directly because it uses @Builder and @Jacksonized
 * annotations, which cannot ignore the unrecognized fields in the subclasses.
 */
@Getter
@EqualsAndHashCode
@ToString
public class ModelWithTagsCreateRequest implements RESTRequest {

  @JsonProperty("name")
  private final String name;

  @JsonProperty("comment")
  private final String comment;

  @JsonProperty("properties")
  private final Map<String, String> properties;

  @Nullable
  @JsonProperty("tagsToAdd")
  private final String[] tagsToAdd;

  /**
   * Constructor for ModelWithTagsCreateRequest.
   *
   * @param name the name of the model.
   * @param comment the comment of the model.
   * @param properties the properties of the model.
   * @param tagsToAdd the tags to add to the model.
   */
  public ModelWithTagsCreateRequest(
      String name, String comment, Map<String, String> properties, @Nullable String[] tagsToAdd) {
    this.name = name;
    this.comment = comment;
    this.properties = properties;
    this.tagsToAdd = tagsToAdd;
  }

  /** Default constructor for Jackson deserialization. */
  public ModelWithTagsCreateRequest() {
    this(null, null, null, null);
  }

  /**
   * Validates the request.
   *
   * @throws IllegalArgumentException if the request is invalid.
   */
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
