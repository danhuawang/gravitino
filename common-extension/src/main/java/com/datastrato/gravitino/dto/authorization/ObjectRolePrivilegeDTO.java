/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.tag.MetadataObjectDTO;

/** Data transfer object representing role privileges associated with a metadata object. */
@Getter
@ToString
@EqualsAndHashCode
public class ObjectRolePrivilegeDTO {

  @JsonProperty("metadataObject")
  private MetadataObjectDTO metadataObject;

  @JsonProperty("rolePrivileges")
  private RolePrivilegeDTO[] rolePrivileges;

  /** Default constructor for Jackson deserialization. */
  protected ObjectRolePrivilegeDTO() {}

  /**
   * Creates a new ObjectRolePrivilegeDTO.
   *
   * @param metadataObject The metadata object.
   * @param rolePrivileges The role privileges associated with the metadata object.
   */
  protected ObjectRolePrivilegeDTO(
      MetadataObjectDTO metadataObject, RolePrivilegeDTO[] rolePrivileges) {
    this.metadataObject = metadataObject;
    this.rolePrivileges = rolePrivileges;
  }

  /**
   * The metadata object.
   *
   * @return The metadata object.
   */
  public MetadataObjectDTO metadataObject() {
    return metadataObject;
  }

  /**
   * The role privileges associated with the metadata object.
   *
   * @return The role privileges.
   */
  public List<RolePrivilegeDTO> rolePrivileges() {
    if (rolePrivileges == null) {
      return Collections.emptyList();
    }

    return Arrays.asList(rolePrivileges);
  }

  /**
   * Creates a new Builder for constructing an ObjectRolePrivilegeDTO.
   *
   * @return A new Builder instance.
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder class for constructing an ObjectRolePrivilegeDTO instance. */
  public static class Builder {
    private MetadataObjectDTO metadataObject;
    private RolePrivilegeDTO[] rolePrivileges;

    /**
     * Sets the metadata object.
     *
     * @param metadataObject The metadata object.
     * @return The builder instance.
     */
    public Builder withMetadataObject(MetadataObjectDTO metadataObject) {
      this.metadataObject = metadataObject;
      return this;
    }

    /**
     * Sets the role privileges.
     *
     * @param rolePrivileges The role privileges.
     * @return The builder instance.
     */
    public Builder withRolePrivileges(RolePrivilegeDTO[] rolePrivileges) {
      this.rolePrivileges = rolePrivileges;
      return this;
    }

    /**
     * Builds an ObjectRolePrivilegeDTO using the builder's properties.
     *
     * @return An ObjectRolePrivilegeDTO.
     * @throws IllegalArgumentException If the metadata object or role privileges are not set.
     */
    public ObjectRolePrivilegeDTO build() {
      Preconditions.checkArgument(metadataObject != null, "metadata object cannot be null");
      Preconditions.checkArgument(
          rolePrivileges != null && rolePrivileges.length != 0,
          "role privileges can't be null or empty");

      return new ObjectRolePrivilegeDTO(metadataObject, rolePrivileges);
    }
  }
}
