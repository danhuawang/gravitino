/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto.authorization;

import com.datastrato.gravitino.authorization.IdpNameStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;

/**
 * An IdP user or group name plus whether it is already added to a metalake.
 *
 * <p>Serialized with {@code name} and {@code status}. {@code status} is {@code true} when the
 * identity already exists in that metalake.
 */
@Getter
@ToString
@EqualsAndHashCode
public class IdpNameStatusDTO {

  @JsonProperty("name")
  private final String name;

  @JsonProperty("status")
  private final boolean status;

  /**
   * Creates a name/status DTO.
   *
   * @param name The IdP user or group name.
   * @param status Whether the name is already added to the metalake.
   */
  public IdpNameStatusDTO(String name, boolean status) {
    this.name = name;
    this.status = status;
  }

  /** Jackson deserializer constructor. */
  public IdpNameStatusDTO() {
    this.name = null;
    this.status = false;
  }

  /**
   * Converts a domain name/status pair to a DTO.
   *
   * @param item The IdP name and metalake membership status.
   * @return The DTO.
   */
  public static IdpNameStatusDTO from(IdpNameStatus item) {
    Preconditions.checkArgument(item != null, "item cannot be null");
    return new IdpNameStatusDTO(item.name(), item.status());
  }

  /**
   * Converts domain name/status pairs to DTOs.
   *
   * @param items The IdP names and metalake membership statuses.
   * @return The DTOs.
   */
  public static IdpNameStatusDTO[] from(IdpNameStatus[] items) {
    Preconditions.checkArgument(items != null, "items cannot be null");
    IdpNameStatusDTO[] dtos = new IdpNameStatusDTO[items.length];
    for (int i = 0; i < items.length; i++) {
      dtos[i] = from(items[i]);
    }
    return dtos;
  }

  /**
   * @throws IllegalArgumentException If {@code name} is blank.
   */
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(StringUtils.isNotBlank(name), "name must not be blank");
  }
}
