/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.v2.storage.po;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** Persistent object for SCIM v2 token metadata rows in {@code v2_scim_token_meta}. */
@Getter
@EqualsAndHashCode
@ToString
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(setterPrefix = "with")
public class ScimTokenMetaPO {
  private Long tokenId;
  private String tokenName;
  private String tokenHash;
  private Long expiresAt;
  private String auditInfo;
  private Long deletedAt;
  private Long updatedAt;
  @Builder.Default private Long lastUsedAt = 0L;
}
