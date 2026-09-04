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
 * Request to create a Local Directory Group in {@code idp_group_meta} and optionally add IdP user
 * members.
 */
@Getter
@EqualsAndHashCode(callSuper = false)
@ToString
public class DirectoryGroupAddRequest implements RESTRequest {

  private static final int MAX_NAME_LENGTH = 128;
  private static final int MAX_COMMENT_LENGTH = 1024;

  @JsonProperty("name")
  private String name;

  @Nullable
  @JsonProperty("comment")
  private String comment;

  @Nullable
  @JsonProperty("members")
  private List<String> members;

  /** Default constructor for Jackson deserialization. */
  private DirectoryGroupAddRequest() {
    this(null, null, null);
  }

  /**
   * Creates a Directory Group add request.
   *
   * @param name Group name to create in {@code idp_group_meta}.
   * @param comment Optional group comment; {@code null} is treated as empty.
   * @param members Optional built-in IdP usernames to add as members; {@code null} means none.
   */
  public DirectoryGroupAddRequest(String name, String comment, List<String> members) {
    this.name = name;
    this.comment = comment;
    this.members = members == null ? Collections.emptyList() : members;
  }

  @Override
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(name), "\"name\" field is required and cannot be empty");
    Preconditions.checkArgument(
        name.length() <= MAX_NAME_LENGTH,
        "Group name must not exceed %s characters",
        MAX_NAME_LENGTH);
    if (comment != null) {
      Preconditions.checkArgument(
          comment.codePointCount(0, comment.length()) <= MAX_COMMENT_LENGTH,
          "Group comment must not exceed %s characters",
          MAX_COMMENT_LENGTH);
    }
    if (members != null) {
      for (String member : members) {
        Preconditions.checkArgument(
            StringUtils.isNotBlank(member), "member in \"members\" cannot be blank");
        Preconditions.checkArgument(!member.contains(":"), "User name cannot contain a colon (:)");
        Preconditions.checkArgument(
            member.length() <= MAX_NAME_LENGTH,
            "Username must not exceed %s characters",
            MAX_NAME_LENGTH);
      }
    }
  }
}
