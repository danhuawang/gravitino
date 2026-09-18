/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;

/**
 * Directory user DTO for Configure → Directory → Users.
 *
 * <p>{@code origin} is {@link IdentitySource#LOCAL} ({@code idp_user_meta}), {@link
 * IdentitySource#PROVISIONED} ({@code scim_user_meta}), or omitted ({@code null}) for metalake-only
 * names — matching metalake security Users.
 */
public class DirectoryUserDTO {

  /** Read model for building {@link DirectoryUserDTO}. */
  public interface DirectoryUserView {
    /**
     * @return Username.
     */
    String name();

    /**
     * @return Whether the user is enabled.
     */
    boolean enabled();

    /**
     * @return Identity source for the Directory Users table, or {@code null} when metalake-only.
     */
    @Nullable
    IdentitySource origin();

    /**
     * @return Identity-store group names.
     */
    List<String> groups();

    /**
     * @return Metalake names containing this username.
     */
    List<String> metalakes();
  }

  @JsonProperty("name")
  private String name;

  @JsonProperty("enabled")
  private boolean enabled;

  @Nullable
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonProperty("origin")
  private IdentitySource origin;

  @JsonProperty("groups")
  private List<String> groups = Collections.emptyList();

  @JsonProperty("metalakes")
  private List<String> metalakes = Collections.emptyList();

  /** Default constructor for Jackson deserialization. */
  private DirectoryUserDTO() {}

  private DirectoryUserDTO(
      String name,
      boolean enabled,
      @Nullable IdentitySource origin,
      List<String> groups,
      List<String> metalakes) {
    this.name = name;
    this.enabled = enabled;
    this.origin = origin;
    this.groups = groups == null ? Collections.emptyList() : groups;
    this.metalakes = metalakes == null ? Collections.emptyList() : metalakes;
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
   * @return Identity-store group names.
   */
  public List<String> groups() {
    return groups;
  }

  /**
   * @return Metalake names containing this username.
   */
  public List<String> metalakes() {
    return metalakes;
  }

  /**
   * Builds a DTO from a directory user view.
   *
   * @param user Directory user view.
   * @return DTO.
   */
  public static DirectoryUserDTO from(DirectoryUserView user) {
    Preconditions.checkArgument(user != null, "user cannot be null");
    return from(user.name(), user.enabled(), user.origin(), user.groups(), user.metalakes());
  }

  /**
   * Converts directory user views to DTOs.
   *
   * @param users Directory user views.
   * @return DTO array.
   */
  public static DirectoryUserDTO[] from(Iterable<? extends DirectoryUserView> users) {
    Preconditions.checkArgument(users != null, "users cannot be null");
    List<DirectoryUserDTO> result = new ArrayList<>();
    for (DirectoryUserView user : users) {
      result.add(from(user));
    }
    return result.toArray(new DirectoryUserDTO[0]);
  }

  private static DirectoryUserDTO from(
      String name,
      boolean enabled,
      @Nullable IdentitySource origin,
      @Nullable List<String> groups,
      @Nullable List<String> metalakes) {
    Preconditions.checkArgument(StringUtils.isNotBlank(name), "name cannot be blank");
    return new DirectoryUserDTO(name, enabled, origin, groups, metalakes);
  }
}
