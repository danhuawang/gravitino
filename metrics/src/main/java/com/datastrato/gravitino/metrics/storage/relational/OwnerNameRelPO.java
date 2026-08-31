/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics.storage.relational;

import com.google.common.base.Preconditions;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/** This class is the persistent object of owner relation. */
@Getter
public class OwnerNameRelPO {

  Long metalakeId;
  String ownerName;
  String ownerType;
  Long metadataObjectId;
  String metadataObjectType;

  private OwnerNameRelPO() {}

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private final OwnerNameRelPO ownerRelPO;

    private Builder() {
      this.ownerRelPO = new OwnerNameRelPO();
    }

    public Builder withMetalakeId(Long metalakeId) {
      ownerRelPO.metalakeId = metalakeId;
      return this;
    }

    public Builder withOwnerId(String ownerId) {
      ownerRelPO.ownerName = ownerId;
      return this;
    }

    public Builder withOwnerType(String ownerType) {
      ownerRelPO.ownerType = ownerType;
      return this;
    }

    public Builder withMetadataObjectId(Long metadataObjectId) {
      ownerRelPO.metadataObjectId = metadataObjectId;
      return this;
    }

    public Builder withMetadataObjectType(String metadataObjectType) {
      ownerRelPO.metadataObjectType = metadataObjectType;
      return this;
    }

    public OwnerNameRelPO build() {
      validate();
      return ownerRelPO;
    }

    private void validate() {
      Preconditions.checkArgument(ownerRelPO.ownerName != null, "Owner id is required");
      Preconditions.checkArgument(
          StringUtils.isNotBlank(ownerRelPO.ownerType), "Owner type is required");
      Preconditions.checkArgument(
          ownerRelPO.metadataObjectId != null, "Metadata object id is required");
      Preconditions.checkArgument(
          ownerRelPO.metadataObjectType != null, "Metadata object type is required");
    }
  }
}
