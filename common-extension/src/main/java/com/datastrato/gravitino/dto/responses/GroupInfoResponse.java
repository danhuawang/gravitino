/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.authorization.IdentityType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.dto.responses.BaseResponse;

/** Response for group lookup before adding a group into a metalake. */
@Getter
@EqualsAndHashCode(callSuper = true)
@ToString
public class GroupInfoResponse extends BaseResponse {

  @JsonProperty("groupName")
  private String groupName;

  @JsonProperty("type")
  private IdentityType type;

  @JsonProperty("comment")
  private String comment;

  @JsonProperty("members")
  private List<String> members = Collections.emptyList();

  /** Default constructor for Jackson deserialization. */
  public GroupInfoResponse() {
    super(0);
  }

  /**
   * Creates a response.
   *
   * @param groupName The group name.
   * @param type The identity type.
   * @param comment The group comment.
   * @param members Member usernames.
   */
  public GroupInfoResponse(
      String groupName,
      IdentityType type,
      @Nullable String comment,
      @Nullable List<String> members) {
    super(0);
    this.groupName = groupName;
    this.type = type;
    this.comment = comment == null ? "" : comment;
    this.members = members == null ? Collections.emptyList() : members;
  }
}
