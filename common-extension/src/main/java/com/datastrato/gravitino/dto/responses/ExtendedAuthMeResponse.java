/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.authorization.AuthMeRoleDTO;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.dto.responses.BaseResponse;

/**
 * Enterprise extension of OSS {@code /authn/me}: authenticated principal, service-admin flag, all
 * identity-store origins for the user, metalakes owned by the user, and lightweight roles (name +
 * securable objects with ALLOW privilege names) keyed by metalake. Roles include both direct user
 * grants and group-inherited grants.
 */
@Getter
@EqualsAndHashCode(callSuper = true)
@ToString
public class ExtendedAuthMeResponse extends BaseResponse {

  @JsonProperty("principal")
  private final String principal;

  @JsonProperty("serviceAdmin")
  private final boolean serviceAdmin;

  @JsonProperty("origins")
  private final List<IdentitySource> origins;

  @JsonProperty("ownedMetalakes")
  private final List<String> ownedMetalakes;

  @JsonProperty("roles")
  private final Map<String, List<AuthMeRoleDTO>> roles;

  /**
   * Creates an enterprise auth/me response.
   *
   * @param principal Server-resolved principal name.
   * @param serviceAdmin Whether the principal is a service administrator.
   * @param origins Identity-store origins present for the user (may be empty).
   * @param ownedMetalakes Metalake names for which the user is the USER owner (may be empty).
   * @param roles Lightweight roles keyed by metalake name (may be empty).
   */
  public ExtendedAuthMeResponse(
      String principal,
      boolean serviceAdmin,
      List<IdentitySource> origins,
      List<String> ownedMetalakes,
      Map<String, List<AuthMeRoleDTO>> roles) {
    super(0);
    this.principal = principal;
    this.serviceAdmin = serviceAdmin;
    this.origins = origins == null ? Collections.emptyList() : origins;
    this.ownedMetalakes = ownedMetalakes == null ? Collections.emptyList() : ownedMetalakes;
    this.roles = roles == null ? Collections.emptyMap() : roles;
  }

  /** Jackson deserializer constructor. */
  public ExtendedAuthMeResponse() {
    super();
    this.principal = null;
    this.serviceAdmin = false;
    this.origins = Collections.emptyList();
    this.ownedMetalakes = Collections.emptyList();
    this.roles = Collections.emptyMap();
  }

  @Override
  public void validate() throws IllegalArgumentException {
    super.validate();
    Preconditions.checkArgument(
        StringUtils.isNotBlank(principal), "'principal' must not be null or empty");
    Preconditions.checkArgument(origins != null, "'origins' must not be null");
    Preconditions.checkArgument(ownedMetalakes != null, "'ownedMetalakes' must not be null");
    Preconditions.checkArgument(roles != null, "'roles' must not be null");
  }
}
