/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.rel;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.rel.ColumnDTO;
import org.apache.gravitino.dto.tag.TagDTO;

/** Represents a column DTO extended with its effective tags. */
@Getter
@ToString
@EqualsAndHashCode
public class ExtendedColumnDTO {

  @JsonUnwrapped @Nullable private final ColumnDTO columnDTO;

  @JsonProperty("tags")
  @Nullable
  private final TagDTO[] tags;

  /** Default constructor for Jackson deserialization. */
  protected ExtendedColumnDTO() {
    this.columnDTO = null;
    this.tags = null;
  }

  /**
   * Creates an extended column DTO.
   *
   * @param columnDTO The base column DTO.
   * @param tags The effective tags. A null value is normalized to an empty array.
   */
  public ExtendedColumnDTO(ColumnDTO columnDTO, @Nullable TagDTO[] tags) {
    this.columnDTO = Preconditions.checkNotNull(columnDTO, "columnDTO cannot be null");
    this.tags = tags == null ? new TagDTO[0] : tags;
  }

  /**
   * Returns the column name.
   *
   * @return The column name, or {@code null} when the DTO has not been deserialized yet.
   */
  @Nullable
  public String name() {
    return columnDTO == null ? null : columnDTO.name();
  }

  /**
   * Validates the extended column DTO.
   *
   * @throws IllegalArgumentException If the base column, tags, or any tag is null.
   */
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(columnDTO != null, "columnDTO cannot be null");
    columnDTO.validate();
    Preconditions.checkArgument(tags != null, "tags cannot be null");
    Arrays.stream(tags)
        .forEach(tag -> Preconditions.checkArgument(tag != null, "tag cannot be null"));
  }
}
