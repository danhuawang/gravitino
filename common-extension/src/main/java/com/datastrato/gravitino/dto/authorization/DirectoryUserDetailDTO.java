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
 * Directory user detail DTO for Configure → Directory → User Overview.
 *
 * <p>{@code origin} is {@link IdentitySource#LOCAL} or {@link IdentitySource#PROVISIONED}. {@code
 * externalId} is set for Provisioned users from {@code scim_user_meta}; Local users omit it. {@code
 * createTime} is reserved; identity stores do not persist creation time yet.
 */
public class DirectoryUserDetailDTO {

  @JsonProperty("name")
  private String name;

  @JsonProperty("enabled")
  private boolean enabled;

  @JsonProperty("origin")
  private IdentitySource origin;

  @Nullable
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonProperty("externalId")
  private String externalId;

  @Nullable
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonProperty("createTime")
  private Instant createTime;

  /** Default constructor for Jackson deserialization. */
  private DirectoryUserDetailDTO() {}

  private DirectoryUserDetailDTO(
      String name,
      boolean enabled,
      IdentitySource origin,
      @Nullable String externalId,
      @Nullable Instant createTime) {
    this.name = name;
    this.enabled = enabled;
    this.origin = origin;
    this.externalId = externalId;
    this.createTime = createTime;
  }

  /**
   * @return Username.
   */
  public String name() {
    return name;
  }

  /**
   * @return Whether the user is enabled (Active / Suspended in the UI).
   */
  public boolean enabled() {
    return enabled;
  }

  /**
   * @return {@link IdentitySource} serialized as {@code origin}.
   */
  public IdentitySource origin() {
    return origin;
  }

  /**
   * @return SCIM external id when Provisioned; {@code null} for Local.
   */
  @Nullable
  public String externalId() {
    return externalId;
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
   * @param name Username.
   * @param enabled Enabled flag.
   * @param origin Local or Provisioned.
   * @param externalId Optional SCIM external id.
   * @param createTime Optional creation time.
   * @return Detail DTO.
   */
  public static DirectoryUserDetailDTO of(
      String name,
      boolean enabled,
      IdentitySource origin,
      @Nullable String externalId,
      @Nullable Instant createTime) {
    Preconditions.checkArgument(StringUtils.isNotBlank(name), "name cannot be blank");
    Preconditions.checkArgument(origin != null, "origin cannot be null");
    Preconditions.checkArgument(
        origin == IdentitySource.LOCAL || origin == IdentitySource.PROVISIONED,
        "origin must be Local or Provisioned");
    return new DirectoryUserDetailDTO(name, enabled, origin, externalId, createTime);
  }
}
