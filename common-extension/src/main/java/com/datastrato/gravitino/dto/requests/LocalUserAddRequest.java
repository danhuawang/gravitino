/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.rest.RESTRequest;

/**
 * Request to add an existing local IdP user into a metalake {@code user_meta} row.
 *
 * <p>The username must already exist in the built-in IdP. This API does not create login
 * credentials.
 */
@Getter
@EqualsAndHashCode(callSuper = false)
@ToString
public class LocalUserAddRequest implements RESTRequest {

  private static final int MAX_NAME_LENGTH = 128;

  @JsonProperty("name")
  private String name;

  @Nullable
  @JsonProperty("roles")
  private List<String> roles;

  @Nullable
  @JsonProperty("enabled")
  private Boolean enabled;

  /** Default constructor for Jackson deserialization. */
  private LocalUserAddRequest() {
    this(null, null, null);
  }

  /**
   * Creates a new request.
   *
   * @param name The username (must already exist in the built-in IdP).
   * @param roles Optional metalake roles to grant.
   * @param enabled Whether the metalake user is enabled; {@code null} means enabled.
   */
  public LocalUserAddRequest(String name, List<String> roles, Boolean enabled) {
    this.name = name;
    this.roles = roles == null ? Collections.emptyList() : roles;
    this.enabled = enabled;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(name), "\"name\" field is required and cannot be empty");
    Preconditions.checkArgument(!name.contains(":"), "User name cannot contain a colon (:)");
    Preconditions.checkArgument(
        name.length() <= MAX_NAME_LENGTH,
        "Username must not exceed %s characters",
        MAX_NAME_LENGTH);
    if (roles != null) {
      for (String role : roles) {
        Preconditions.checkArgument(StringUtils.isNotBlank(role), "role cannot be blank");
      }
    }
  }
}
