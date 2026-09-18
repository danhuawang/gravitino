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
 * <p>{@code origin} is {@link IdentitySource#LOCAL}, {@link IdentitySource#PROVISIONED}, {@link
 * IdentitySource#JIT}, or omitted ({@code null}) for metalake-only names. {@code externalId} is set
 * for Provisioned users from {@code scim_user_meta}. {@code createTime} comes from identity-store
 * {@code audit_info.createTime} for Local / Provisioned, or the earliest {@code
 * user_meta.audit_info.createTime} for metalake-only (Created on Overview).
 */
public class DirectoryUserDetailDTO {

  @JsonProperty("name")
  private String name;

  @JsonProperty("enabled")
  private boolean enabled;

  @Nullable
  @JsonInclude(JsonInclude.Include.NON_NULL)
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
      @Nullable IdentitySource origin,
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
   * @return {@link IdentitySource} serialized as {@code origin}, or {@code null} when
   *     metalake-only.
   */
  @Nullable
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
   * @return Creation time from identity-store {@code audit_info}; {@code null} when unavailable.
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
   * @param origin Local, Provisioned, JIT, or {@code null} when metalake-only.
   * @param externalId Optional SCIM external id.
   * @param createTime Optional creation time.
   * @return Detail DTO.
   */
  public static DirectoryUserDetailDTO of(
      String name,
      boolean enabled,
      @Nullable IdentitySource origin,
      @Nullable String externalId,
      @Nullable Instant createTime) {
    Preconditions.checkArgument(StringUtils.isNotBlank(name), "name cannot be blank");
    return new DirectoryUserDetailDTO(name, enabled, origin, externalId, createTime);
  }
}
