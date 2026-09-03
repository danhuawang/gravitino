/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.requests;

import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.rest.RESTRequest;

/**
 * Request to batch update {@code enabled} for Directory Users (Configure → Directory → Users).
 *
 * <p>Only {@link IdentitySource#LOCAL} users are supported. The server rejects any non-Local {@code
 * origin} and any username missing from {@code idp_user_meta} before running the UPDATE.
 */
@Getter
@EqualsAndHashCode(callSuper = false)
@ToString
public class DirectoryUserEnabledBatchUpdateRequest implements RESTRequest {

  @JsonProperty("users")
  private DirectoryUserEnabledUpdate[] users;

  @JsonProperty("enabled")
  private Boolean enabled;

  /** Default constructor for Jackson deserialization. */
  private DirectoryUserEnabledBatchUpdateRequest() {
    this(null, null);
  }

  /**
   * Creates a new Directory Users enabled batch update request.
   *
   * @param users Users with name and origin.
   * @param enabled Target enabled flag.
   */
  public DirectoryUserEnabledBatchUpdateRequest(
      DirectoryUserEnabledUpdate[] users, Boolean enabled) {
    this.users = users;
    this.enabled = enabled;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(
        users != null && users.length > 0, "\"users\" field is required and cannot be empty");
    for (DirectoryUserEnabledUpdate user : users) {
      Preconditions.checkArgument(user != null, "user entry in \"users\" cannot be null");
      user.validate();
    }
    Preconditions.checkArgument(
        enabled != null, "\"enabled\" field is required and cannot be null");
  }

  /** One Directory user row for an enabled batch update. */
  @Getter
  @EqualsAndHashCode
  @ToString
  public static class DirectoryUserEnabledUpdate {

    @JsonProperty("name")
    private String name;

    @JsonProperty("origin")
    private IdentitySource origin;

    /** Default constructor for Jackson deserialization. */
    private DirectoryUserEnabledUpdate() {
      this(null, null);
    }

    /**
     * Creates a Directory user enabled update entry.
     *
     * @param name Username.
     * @param origin Identity source (must be Local for this API).
     */
    public DirectoryUserEnabledUpdate(String name, IdentitySource origin) {
      this.name = name;
      this.origin = origin;
    }

    /**
     * Validates this entry.
     *
     * @throws IllegalArgumentException If name or origin is missing.
     */
    public void validate() throws IllegalArgumentException {
      Preconditions.checkArgument(StringUtils.isNotBlank(name), "\"name\" cannot be blank");
      Preconditions.checkArgument(origin != null, "\"origin\" cannot be null");
    }
  }
}
