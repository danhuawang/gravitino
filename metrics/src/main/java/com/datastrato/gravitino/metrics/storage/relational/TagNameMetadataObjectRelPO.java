/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics.storage.relational;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import lombok.Getter;

@Getter
public class TagNameMetadataObjectRelPO {
  private String tagName;
  private Long objectId;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TagNameMetadataObjectRelPO)) {
      return false;
    }
    TagNameMetadataObjectRelPO tagNameMetadataObjectRelPO = (TagNameMetadataObjectRelPO) o;
    return Objects.equal(getTagName(), tagNameMetadataObjectRelPO.getTagName())
        && Objects.equal(getObjectId(), tagNameMetadataObjectRelPO.getObjectId());
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(getTagName(), getObjectId());
  }

  public static class Builder {
    private final TagNameMetadataObjectRelPO tagNameMetadataObjectRelPO;

    private Builder() {
      tagNameMetadataObjectRelPO = new TagNameMetadataObjectRelPO();
    }

    public Builder withTagName(String tagName) {
      tagNameMetadataObjectRelPO.tagName = tagName;
      return this;
    }

    public Builder withObjectId(Long objectId) {
      tagNameMetadataObjectRelPO.objectId = objectId;
      return this;
    }

    private void validate() {
      Preconditions.checkArgument(
          tagNameMetadataObjectRelPO.tagName != null, "Tag name is required");
      Preconditions.checkArgument(
          tagNameMetadataObjectRelPO.objectId != null, "Object id is required");
    }

    public TagNameMetadataObjectRelPO build() {
      validate();
      return tagNameMetadataObjectRelPO;
    }
  }

  /**
   * Creates a new instance of {@link Builder}.
   *
   * @return The new instance.
   */
  public static Builder builder() {
    return new Builder();
  }
}
