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

/** Persistent object for SCIM token metadata rows in {@code scim_token_meta}. */
@Getter
@EqualsAndHashCode
@ToString
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(setterPrefix = "with")
public class ScimTokenMetaPO {
  private Long tokenId;
  private Long metalakeId;
  private String tokenName;
  private String tokenHash;
  private Long expiresAt;
  private String auditInfo;
  private Long deletedAt;
  private Long updatedAt;
}
