/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;

/**
 * Directory group DTO for Configure → Directory → Groups.
 *
 * <p>{@code origin} is {@link IdentitySource#LOCAL} ({@code idp_group_meta}), {@link
 * IdentitySource#PROVISIONED} ({@code scim_group_meta}), or {@link IdentitySource#JIT} (metalake
 * {@code group_meta} only).
 */
public class DirectoryGroupDTO {

  /** Read model for building {@link DirectoryGroupDTO}. */
  public interface DirectoryGroupView {
    /**
     * @return Group name.
     */
    String name();

    /**
     * @return Identity-store member count (0 for JIT).
     */
    int memberCount();

    /**
     * @return Identity source for the Directory Groups table.
     */
    IdentitySource origin();

    /**
     * @return Metalake names containing this group.
     */
    List<String> metalakes();
  }

  @JsonProperty("name")
  private String name;

  @JsonProperty("memberCount")
  private int memberCount;

  @JsonProperty("origin")
  private IdentitySource origin;

  @JsonProperty("metalakes")
  private List<String> metalakes = Collections.emptyList();

  /** Default constructor for Jackson deserialization. */
  private DirectoryGroupDTO() {}

  private DirectoryGroupDTO(
      String name, int memberCount, IdentitySource origin, List<String> metalakes) {
    this.name = name;
    this.memberCount = memberCount;
    this.origin = origin;
    this.metalakes = metalakes == null ? Collections.emptyList() : metalakes;
  }

  /**
   * @return Group name.
   */
  public String name() {
    return name;
  }

  /**
   * @return Identity-store member count (0 for JIT).
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
   * @return Metalake names containing this group.
   */
  public List<String> metalakes() {
    return metalakes;
  }

  /**
   * Builds a DTO from a directory group view.
   *
   * @param group Directory group view.
   * @return DTO.
   */
  public static DirectoryGroupDTO from(DirectoryGroupView group) {
    Preconditions.checkArgument(group != null, "group cannot be null");
    return from(group.name(), group.memberCount(), group.origin(), group.metalakes());
  }

  /**
   * Converts directory group views to DTOs.
   *
   * @param groups Directory group views.
   * @return DTO array.
   */
  public static DirectoryGroupDTO[] from(Iterable<? extends DirectoryGroupView> groups) {
    Preconditions.checkArgument(groups != null, "groups cannot be null");
    List<DirectoryGroupDTO> result = new ArrayList<>();
    for (DirectoryGroupView group : groups) {
      result.add(from(group));
    }
    return result.toArray(new DirectoryGroupDTO[0]);
  }

  private static DirectoryGroupDTO from(
      String name, int memberCount, IdentitySource origin, @Nullable List<String> metalakes) {
    Preconditions.checkArgument(StringUtils.isNotBlank(name), "name cannot be blank");
    Preconditions.checkArgument(origin != null, "origin cannot be null");
    Preconditions.checkArgument(memberCount >= 0, "memberCount cannot be negative");
    return new DirectoryGroupDTO(name, memberCount, origin, metalakes);
  }
}
