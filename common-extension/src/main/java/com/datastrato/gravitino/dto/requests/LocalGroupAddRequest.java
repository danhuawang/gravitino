/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
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
 * Request to add an existing local IdP group into a metalake.
 *
 * <p>The group name must already exist in the built-in IdP. This API does not create a built-in IdP
 * group.
 */
@Getter
@EqualsAndHashCode(callSuper = false)
@ToString
public class LocalGroupAddRequest implements RESTRequest {

  private static final int MAX_NAME_LENGTH = 128;

  @JsonProperty("name")
  private String name;

  @Nullable
  @JsonProperty("roles")
  private List<String> roles;

  /** Default constructor for Jackson deserialization. */
  private LocalGroupAddRequest() {
    this(null, null);
  }

  /**
   * Creates a new request.
   *
   * @param name The group name.
   * @param roles Optional metalake roles to grant.
   */
  public LocalGroupAddRequest(String name, List<String> roles) {
    this.name = name;
    this.roles = roles == null ? Collections.emptyList() : roles;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(name), "\"name\" field is required and cannot be empty");
    Preconditions.checkArgument(
        name.length() <= MAX_NAME_LENGTH,
        "Group name must not exceed %s characters",
        MAX_NAME_LENGTH);
    if (roles != null) {
      for (String role : roles) {
        Preconditions.checkArgument(StringUtils.isNotBlank(role), "role cannot be blank");
      }
    }
  }
}
