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
import org.apache.gravitino.file.Fileset;
import org.apache.gravitino.rest.RESTRequest;

/**
 * Represents a request to create a fileset with tags.
 *
 * <p>Note: Cannot extend FilesetCreateRequest directly because it uses @Builder and @Jacksonized
 * annotations, which cannot ignore the unrecognized fields in the subclasses.
 */
@Getter
@EqualsAndHashCode
@ToString
public class FilesetWithTagsCreateRequest implements RESTRequest {

  @JsonProperty("name")
  private final String name;

  @Nullable
  @JsonProperty("comment")
  private final String comment;

  @Nullable
  @JsonProperty("type")
  private final Fileset.Type type;

  @Nullable
  @JsonProperty("storageLocation")
  private final String storageLocation;

  @Nullable
  @JsonProperty("storageLocations")
  private Map<String, String> storageLocations;

  @Nullable
  @JsonProperty("properties")
  private final Map<String, String> properties;

  @Nullable
  @JsonProperty("tagsToAdd")
  private final String[] tagsToAdd;

  /**
   * Constructor for FilesetWithTagsCreateRequest.
   *
   * @param name the name of the fileset.
   * @param comment the comment of the fileset.
   * @param type the type of the fileset.
   * @param storageLocation the storage location of the fileset.
   * @param properties the properties of the fileset.
   * @param tagsToAdd the tags to add to the fileset.
   */
  public FilesetWithTagsCreateRequest(
      String name,
      @Nullable String comment,
      @Nullable Fileset.Type type,
      @Nullable String storageLocation,
      @Nullable Map<String, String> properties,
      @Nullable String[] tagsToAdd) {
    this.name = name;
    this.comment = comment;
    this.type = type;
    this.storageLocation = storageLocation;
    this.properties = properties;
    this.tagsToAdd = tagsToAdd;
  }

  /** This is the constructor that is used by Jackson deserializer */
  public FilesetWithTagsCreateRequest() {
    this(null, null, null, null, null, null);
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
