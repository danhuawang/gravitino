/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;

/**
 * Lightweight role summary for enterprise {@code /authn/me}: role name plus securable objects with
 * ALLOW privilege names.
 *
 * <p>DENY privileges and role properties/audit are omitted; use role-detail APIs when the UI needs
 * full ACL.
 */
@Getter
@ToString
@EqualsAndHashCode
public class AuthMeRoleDTO {

  @JsonProperty("name")
  private final String name;

  @JsonProperty("securableObjects")
  private final List<AuthMeSecurableObjectDTO> securableObjects;

  /**
   * Creates a lightweight auth/me role.
   *
   * @param name Role name.
   * @param securableObjects Securable objects with ALLOW privileges (may be empty).
   */
  public AuthMeRoleDTO(String name, List<AuthMeSecurableObjectDTO> securableObjects) {
    Preconditions.checkArgument(StringUtils.isNotBlank(name), "name cannot be blank");
    this.name = name;
    this.securableObjects = securableObjects == null ? Collections.emptyList() : securableObjects;
  }

  /** Jackson deserializer constructor. */
  public AuthMeRoleDTO() {
    this.name = null;
    this.securableObjects = Collections.emptyList();
  }
}
