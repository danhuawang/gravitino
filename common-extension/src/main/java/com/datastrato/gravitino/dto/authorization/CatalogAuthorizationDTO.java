/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;

/** Authorization information grouped by catalog. */
@Getter
@ToString
@EqualsAndHashCode
public class CatalogAuthorizationDTO {

  @JsonProperty("catalog")
  private String catalog;

  @JsonProperty("objects")
  private ObjectAuthorizationDTO[] objects;

  @JsonProperty("roles")
  private String[] roles;

  @JsonProperty("users")
  private String[] users;

  @JsonProperty("groups")
  private String[] groups;

  @JsonProperty("memberCount")
  private int memberCount;

  @JsonProperty("privilegedPrincipalCount")
  private int privilegedPrincipalCount;

  @JsonProperty("privileged")
  private double privileged;

  /** Default constructor for Jackson deserialization. */
  protected CatalogAuthorizationDTO() {}

  /**
   * Creates a catalog authorization DTO.
   *
   * @param catalog The catalog name.
   * @param objects The objects with explicit role privileges in the catalog.
   * @param roles The roles covering the objects.
   * @param users The users associated with the catalog through the roles.
   * @param groups The groups associated with the catalog through the roles.
   * @param privilegedPrincipalCount The number of principals holding an allowed write or admin
   *     privilege.
   */
  public CatalogAuthorizationDTO(
      String catalog,
      ObjectAuthorizationDTO[] objects,
      String[] roles,
      String[] users,
      String[] groups,
      int privilegedPrincipalCount) {
    Preconditions.checkArgument(StringUtils.isNotBlank(catalog), "catalog cannot be blank");
    Preconditions.checkArgument(objects != null, "objects cannot be null");
    Preconditions.checkArgument(roles != null, "roles cannot be null");
    Preconditions.checkArgument(users != null, "users cannot be null");
    Preconditions.checkArgument(groups != null, "groups cannot be null");
    Preconditions.checkArgument(
        privilegedPrincipalCount >= 0, "privileged principal count cannot be negative");
    this.catalog = catalog;
    this.objects = objects;
    this.roles = roles;
    this.users = users;
    this.groups = groups;
    this.memberCount = users.length + groups.length;
    Preconditions.checkArgument(
        privilegedPrincipalCount <= memberCount,
        "privileged principal count cannot exceed member count");
    this.privilegedPrincipalCount = privilegedPrincipalCount;
    this.privileged = memberCount == 0 ? 0.0 : (double) privilegedPrincipalCount / memberCount;
  }
}
