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
 * Request to delete Directory Users (Configure → Directory → Users).
 *
 * <p>Only {@link IdentitySource#LOCAL} users are supported. The server rejects any non-Local {@code
 * origin}; existence is not checked before calling the IdP remove API.
 */
@Getter
@EqualsAndHashCode(callSuper = false)
@ToString
public class DirectoryUserDeleteRequest implements RESTRequest {

  @JsonProperty("users")
  private DirectoryUserDelete[] users;

  /** Default constructor for Jackson deserialization. */
  private DirectoryUserDeleteRequest() {
    this(null);
  }

  /**
   * Creates a Directory Users delete request.
   *
   * @param users Users with name and origin.
   */
  public DirectoryUserDeleteRequest(DirectoryUserDelete[] users) {
    this.users = users;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(
        users != null && users.length > 0, "\"users\" field is required and cannot be empty");
    for (DirectoryUserDelete user : users) {
      Preconditions.checkArgument(user != null, "user entry in \"users\" cannot be null");
      user.validate();
    }
  }

  /** One Directory user row for a delete request. */
  @Getter
  @EqualsAndHashCode
  @ToString
  public static class DirectoryUserDelete {

    @JsonProperty("name")
    private String name;

    @JsonProperty("origin")
    private IdentitySource origin;

    /** Default constructor for Jackson deserialization. */
    private DirectoryUserDelete() {
      this(null, null);
    }

    /**
     * Creates a Directory user delete entry.
     *
     * @param name Username.
     * @param origin Identity source (must be Local for this API).
     */
    public DirectoryUserDelete(String name, IdentitySource origin) {
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
