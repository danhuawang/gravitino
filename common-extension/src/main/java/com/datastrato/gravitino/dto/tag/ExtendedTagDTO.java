/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.dto.tag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.base.Preconditions;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.authorization.OwnerDTO;
import org.apache.gravitino.dto.tag.TagDTO;

/** Data transfer object representing a tag and its owner. */
@Getter
@ToString
@EqualsAndHashCode
public class ExtendedTagDTO {

  @JsonUnwrapped private final TagDTO tag;

  @Nullable
  @JsonProperty("owner")
  private final OwnerDTO owner;

  /** Default constructor for Jackson deserialization. */
  protected ExtendedTagDTO() {
    this.tag = null;
    this.owner = null;
  }

  /**
   * Creates a new ExtendedTagDTO.
   *
   * @param tag The tag DTO.
   * @param owner The owner DTO, or {@code null} if the tag has no owner.
   */
  public ExtendedTagDTO(TagDTO tag, @Nullable OwnerDTO owner) {
    this.tag = tag;
    this.owner = owner;
  }

  /**
   * The tag DTO.
   *
   * @return The tag DTO.
   */
  public TagDTO tag() {
    return tag;
  }

  /**
   * The owner DTO.
   *
   * @return The owner DTO, or {@code null} if the tag has no owner.
   */
  @Nullable
  public OwnerDTO owner() {
    return owner;
  }

  /**
   * Creates a builder for an ExtendedTagDTO.
   *
   * @return A new builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for ExtendedTagDTO instances. */
  public static class Builder {
    private TagDTO tag;
    @Nullable private OwnerDTO owner;

    /**
     * Sets the tag DTO.
     *
     * @param tag The tag DTO.
     * @return This builder.
     */
    public Builder withTag(TagDTO tag) {
      this.tag = tag;
      return this;
    }

    /**
     * Sets the owner DTO.
     *
     * @param owner The owner DTO, or {@code null} if the tag has no owner.
     * @return This builder.
     */
    public Builder withOwner(@Nullable OwnerDTO owner) {
      this.owner = owner;
      return this;
    }

    /**
     * Builds an ExtendedTagDTO.
     *
     * @return The extended tag DTO.
     * @throws IllegalArgumentException If the tag is null.
     */
    public ExtendedTagDTO build() {
      Preconditions.checkArgument(tag != null, "tag cannot be null");
      return new ExtendedTagDTO(tag, owner);
    }
  }
}
