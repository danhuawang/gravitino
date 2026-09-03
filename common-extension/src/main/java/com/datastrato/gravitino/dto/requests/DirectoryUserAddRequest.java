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
 * Request to create a Local Directory User in {@code idp_user_meta} and optionally add the user to
 * built-in IdP groups.
 */
@Getter
@EqualsAndHashCode(callSuper = false)
@ToString
public class DirectoryUserAddRequest implements RESTRequest {

  private static final int MAX_NAME_LENGTH = 128;
  private static final int MIN_PASSWORD_LENGTH = 12;
  private static final int MAX_PASSWORD_LENGTH = 64;

  @JsonProperty("name")
  private String name;

  @JsonProperty("password")
  @ToString.Exclude
  private String password;

  @Nullable
  @JsonProperty("groupNames")
  private List<String> groupNames;

  /** Default constructor for Jackson deserialization. */
  private DirectoryUserAddRequest() {
    this(null, null, null);
  }

  /**
   * Creates a Directory User add request.
   *
   * @param name Username to create in {@code idp_user_meta}.
   * @param password Plaintext password.
   * @param groupNames Optional built-in IdP group names to join; {@code null} means none.
   */
  public DirectoryUserAddRequest(String name, String password, List<String> groupNames) {
    this.name = name;
    this.password = password;
    this.groupNames = groupNames == null ? Collections.emptyList() : groupNames;
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
    Preconditions.checkArgument(
        StringUtils.isNotBlank(password), "\"password\" field is required and cannot be empty");
    Preconditions.checkArgument(
        password.length() >= MIN_PASSWORD_LENGTH && password.length() <= MAX_PASSWORD_LENGTH,
        "Password must be at least %s characters long and at most %s characters long",
        MIN_PASSWORD_LENGTH,
        MAX_PASSWORD_LENGTH);
    if (groupNames != null) {
      for (String groupName : groupNames) {
        Preconditions.checkArgument(
            StringUtils.isNotBlank(groupName), "group name in \"groupNames\" cannot be blank");
        Preconditions.checkArgument(
            groupName.length() <= MAX_NAME_LENGTH,
            "Group name must not exceed %s characters",
            MAX_NAME_LENGTH);
      }
    }
  }
}
