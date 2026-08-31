/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.tag.MetadataObjectDTO;

/** A metadata object with its explicit role privileges. */
@Getter
@ToString
@EqualsAndHashCode
public class ObjectAuthorizationDTO {

  @JsonProperty("metadataObject")
  private MetadataObjectDTO metadataObject;

  @JsonProperty("rolePrivileges")
  private RolePrivilegeDTO[] rolePrivileges;

  /** Default constructor for Jackson deserialization. */
  protected ObjectAuthorizationDTO() {}

  /**
   * Creates an object authorization DTO.
   *
   * @param metadataObject The metadata object.
   * @param rolePrivileges The explicit role privileges on the object.
   */
  public ObjectAuthorizationDTO(
      MetadataObjectDTO metadataObject, RolePrivilegeDTO[] rolePrivileges) {
    Preconditions.checkArgument(metadataObject != null, "metadata object cannot be null");
    Preconditions.checkArgument(
        rolePrivileges != null && rolePrivileges.length > 0,
        "role privileges cannot be null or empty");
    this.metadataObject = metadataObject;
    this.rolePrivileges = rolePrivileges;
  }
}
