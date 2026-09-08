/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Map;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.dto.AuditDTO;

/** A role without securable objects or privileges. */
@Getter
@ToString
@EqualsAndHashCode
public class AssignedRoleDTO {

  @JsonProperty("name")
  private String name;

  @JsonProperty("properties")
  private Map<String, String> properties;

  @JsonProperty("audit")
  private AuditDTO audit;

  /** Default constructor for Jackson deserialization. */
  protected AssignedRoleDTO() {}

  /**
   * Creates an assigned role DTO.
   *
   * @param name The role name.
   * @param properties The role properties.
   * @param audit The role audit information.
   */
  public AssignedRoleDTO(String name, Map<String, String> properties, AuditDTO audit) {
    Preconditions.checkArgument(StringUtils.isNotBlank(name), "name cannot be blank");
    this.name = name;
    this.properties = Preconditions.checkNotNull(properties, "properties cannot be null");
    this.audit = Preconditions.checkNotNull(audit, "audit cannot be null");
  }
}
