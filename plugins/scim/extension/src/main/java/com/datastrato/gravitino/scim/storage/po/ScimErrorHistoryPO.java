/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.po;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Persistent object for SCIM protocol error history rows in {@code scim_error_history}.
 *
 * <p>{@code metalakeName} is used only to resolve {@code metalake_id} on insert and is not stored.
 */
@Getter
@EqualsAndHashCode
@ToString
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(setterPrefix = "with")
public class ScimErrorHistoryPO {
  private Long errorId;
  private Long metalakeId;
  private String metalakeName;
  private String httpMethod;
  private String requestPath;
  private Integer httpStatus;
  private String scimType;
  private String errorDetail;
  private String principal;
  private Long createdAt;
}
