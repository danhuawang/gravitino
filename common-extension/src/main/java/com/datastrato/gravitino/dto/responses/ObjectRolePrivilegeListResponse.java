/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.authorization.ObjectRolePrivilegeDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Represents a response for listing metadata objects with their role privileges. */
@Getter
@ToString
@EqualsAndHashCode(callSuper = true)
public class ObjectRolePrivilegeListResponse extends BaseResponse {

  @JsonProperty("objectRolePrivileges")
  private final ObjectRolePrivilegeDTO[] objectRolePrivileges;

  /**
   * Creates a new ObjectRolePrivilegeListResponse.
   *
   * @param objectRolePrivileges The metadata object role privileges.
   */
  public ObjectRolePrivilegeListResponse(ObjectRolePrivilegeDTO[] objectRolePrivileges) {
    super(0);
    this.objectRolePrivileges = objectRolePrivileges;
  }

  /** Default constructor for ObjectRolePrivilegeListResponse. */
  public ObjectRolePrivilegeListResponse() {
    super();
    this.objectRolePrivileges = null;
  }

  /**
   * Creates a new Builder for constructing an ObjectRolePrivilegeListResponse.
   *
   * @return A new Builder instance.
   */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();

    Preconditions.checkArgument(
        objectRolePrivileges != null, "\"objectRolePrivileges\" must not be null");
    Arrays.stream(objectRolePrivileges)
        .forEach(
            objectRolePrivilege -> {
              Preconditions.checkArgument(
                  objectRolePrivilege != null, "object role privilege must not be null");
              Preconditions.checkArgument(
                  objectRolePrivilege.metadataObject() != null, "metadata object must not be null");
              Preconditions.checkArgument(
                  objectRolePrivilege.rolePrivileges() != null
                      && !objectRolePrivilege.rolePrivileges().isEmpty(),
                  "role privileges can't be null or empty");
            });
  }

  /** Builder class for constructing an ObjectRolePrivilegeListResponse instance. */
  public static class Builder {
    private ObjectRolePrivilegeDTO[] objectRolePrivileges;

    /**
     * Sets the metadata object role privileges.
     *
     * @param objectRolePrivileges The metadata object role privileges.
     * @return The builder instance.
     */
    public Builder withObjectRolePrivileges(ObjectRolePrivilegeDTO[] objectRolePrivileges) {
      this.objectRolePrivileges = objectRolePrivileges;
      return this;
    }

    /**
     * Builds an ObjectRolePrivilegeListResponse using the builder's properties.
     *
     * @return An ObjectRolePrivilegeListResponse.
     * @throws IllegalArgumentException If objectRolePrivileges is not set.
     */
    public ObjectRolePrivilegeListResponse build() {
      Preconditions.checkArgument(
          objectRolePrivileges != null, "object role privileges cannot be null");
      return new ObjectRolePrivilegeListResponse(objectRolePrivileges);
    }
  }
}
