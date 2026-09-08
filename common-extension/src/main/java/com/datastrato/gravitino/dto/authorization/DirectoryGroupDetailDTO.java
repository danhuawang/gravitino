/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.time.Instant;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;

/**
 * Directory group detail DTO for Configure → Directory → Group Overview.
 *
 * <p>{@code origin} is {@link IdentitySource#LOCAL} or {@link IdentitySource#PROVISIONED}. {@code
 * memberCount} is the identity-store membership count (Members on the Overview). {@code createTime}
 * is reserved; identity stores do not persist creation time yet.
 */
public class DirectoryGroupDetailDTO {

  @JsonProperty("name")
  private String name;

  @JsonProperty("memberCount")
  private int memberCount;

  @JsonProperty("origin")
  private IdentitySource origin;

  @Nullable
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonProperty("createTime")
  private Instant createTime;

  /** Default constructor for Jackson deserialization. */
  private DirectoryGroupDetailDTO() {}

  private DirectoryGroupDetailDTO(
      String name, int memberCount, IdentitySource origin, @Nullable Instant createTime) {
    this.name = name;
    this.memberCount = memberCount;
    this.origin = origin;
    this.createTime = createTime;
  }

  /**
   * @return Group name.
   */
  public String name() {
    return name;
  }

  /**
   * @return Identity-store member count.
   */
  public int memberCount() {
    return memberCount;
  }

  /**
   * @return {@link IdentitySource} serialized as {@code origin}.
   */
  public IdentitySource origin() {
    return origin;
  }

  /**
   * @return Creation time when available; currently always {@code null}.
   */
  @Nullable
  public Instant createTime() {
    return createTime;
  }

  /**
   * Builds a detail DTO.
   *
   * @param name Group name.
   * @param memberCount Identity-store member count.
   * @param origin Local or Provisioned.
   * @param createTime Optional creation time.
   * @return Detail DTO.
   */
  public static DirectoryGroupDetailDTO of(
      String name, int memberCount, IdentitySource origin, @Nullable Instant createTime) {
    Preconditions.checkArgument(StringUtils.isNotBlank(name), "name cannot be blank");
    Preconditions.checkArgument(memberCount >= 0, "memberCount cannot be negative");
    Preconditions.checkArgument(origin != null, "origin cannot be null");
    Preconditions.checkArgument(
        origin == IdentitySource.LOCAL || origin == IdentitySource.PROVISIONED,
        "origin must be Local or Provisioned");
    return new DirectoryGroupDetailDTO(name, memberCount, origin, createTime);
  }
}
