/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.rest.RESTRequest;

/**
 * Request to change a Local IdP user password after verifying the current password.
 *
 * <p>JSON fields: {@code username}, {@code oldPassword}, {@code newPassword}.
 */
@Getter
@EqualsAndHashCode(callSuper = false)
@ToString
public class IdpPasswordChangeRequest implements RESTRequest {

  private static final int MAX_NAME_LENGTH = 128;
  private static final int MIN_PASSWORD_LENGTH = 12;
  private static final int MAX_PASSWORD_LENGTH = 64;

  @JsonProperty("username")
  private String username;

  @JsonProperty("oldPassword")
  @ToString.Exclude
  private String oldPassword;

  @JsonProperty("newPassword")
  @ToString.Exclude
  private String newPassword;

  /** Default constructor for Jackson deserialization. */
  private IdpPasswordChangeRequest() {
    this(null, null, null);
  }

  /**
   * Creates a password-change request.
   *
   * @param username Local IdP username.
   * @param oldPassword Current plaintext password.
   * @param newPassword New plaintext password.
   */
  public IdpPasswordChangeRequest(String username, String oldPassword, String newPassword) {
    this.username = username;
    this.oldPassword = oldPassword;
    this.newPassword = newPassword;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(username), "\"username\" field is required and cannot be empty");
    Preconditions.checkArgument(!username.contains(":"), "User name cannot contain a colon (:)");
    Preconditions.checkArgument(
        username.length() <= MAX_NAME_LENGTH,
        "Username must not exceed %s characters",
        MAX_NAME_LENGTH);
    Preconditions.checkArgument(
        StringUtils.isNotBlank(oldPassword),
        "\"oldPassword\" field is required and cannot be empty");
    Preconditions.checkArgument(
        StringUtils.isNotBlank(newPassword),
        "\"newPassword\" field is required and cannot be empty");
    Preconditions.checkArgument(
        newPassword.length() >= MIN_PASSWORD_LENGTH && newPassword.length() <= MAX_PASSWORD_LENGTH,
        "Password must be at least %s characters long and at most %s characters long",
        MIN_PASSWORD_LENGTH,
        MAX_PASSWORD_LENGTH);
    Preconditions.checkArgument(
        !oldPassword.equals(newPassword), "\"newPassword\" must differ from \"oldPassword\"");
  }
}
