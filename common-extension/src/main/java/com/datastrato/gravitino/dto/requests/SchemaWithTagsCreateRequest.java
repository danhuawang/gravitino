/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.requests.SchemaCreateRequest;

@Getter
@EqualsAndHashCode(callSuper = false)
@ToString
public class SchemaWithTagsCreateRequest extends SchemaCreateRequest {

  @Nullable
  @JsonProperty("tagsToAdd")
  private final String[] tagsToAdd;

  /**
   * Creates a new SchemaWithTagsCreateRequest.
   *
   * @param name The name of the schema.
   * @param comment The comment of the schema.
   * @param properties The properties of the schema.
   * @param tagsToAdd The tags to add, can be null.
   */
  public SchemaWithTagsCreateRequest(
      String name, String comment, Map<String, String> properties, String[] tagsToAdd) {
    super(name, comment, properties);
    this.tagsToAdd = tagsToAdd;
  }

  /** This is the constructor that is used by Jackson deserializer */
  public SchemaWithTagsCreateRequest() {
    this(null, null, null, null);
  }
}
