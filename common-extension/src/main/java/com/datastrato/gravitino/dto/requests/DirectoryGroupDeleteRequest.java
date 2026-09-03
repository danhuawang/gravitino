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
 * Request to delete Directory Groups (Configure → Directory → Groups).
 *
 * <p>Only {@link IdentitySource#LOCAL} groups are supported. The server rejects any non-Local
 * {@code origin}; existence is not checked before calling the IdP remove API.
 */
@Getter
@EqualsAndHashCode(callSuper = false)
@ToString
public class DirectoryGroupDeleteRequest implements RESTRequest {

  @JsonProperty("groups")
  private DirectoryGroupDelete[] groups;

  /** Default constructor for Jackson deserialization. */
  private DirectoryGroupDeleteRequest() {
    this(null);
  }

  /**
   * Creates a Directory Groups delete request.
   *
   * @param groups Groups with name and origin.
   */
  public DirectoryGroupDeleteRequest(DirectoryGroupDelete[] groups) {
    this.groups = groups;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(
        groups != null && groups.length > 0, "\"groups\" field is required and cannot be empty");
    for (DirectoryGroupDelete group : groups) {
      Preconditions.checkArgument(group != null, "group entry in \"groups\" cannot be null");
      group.validate();
    }
  }

  /** One Directory group row for a delete request. */
  @Getter
  @EqualsAndHashCode
  @ToString
  public static class DirectoryGroupDelete {

    @JsonProperty("name")
    private String name;

    @JsonProperty("origin")
    private IdentitySource origin;

    /** Default constructor for Jackson deserialization. */
    private DirectoryGroupDelete() {
      this(null, null);
    }

    /**
     * Creates a Directory group delete entry.
     *
     * @param name Group name.
     * @param origin Identity source (must be Local for this API).
     */
    public DirectoryGroupDelete(String name, IdentitySource origin) {
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
