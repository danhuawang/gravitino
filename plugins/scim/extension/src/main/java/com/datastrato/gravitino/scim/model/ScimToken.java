/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.model;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.gravitino.meta.AuditInfo;

/** SCIM token metadata without plaintext secret or storage internals. */
@Getter
@EqualsAndHashCode
@ToString
@Builder(setterPrefix = "with")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ScimToken {
  private final long tokenId;
  private final long metalakeId;
  private final String tokenName;
  private final long expiresAt;
  private final AuditInfo auditInfo;
}
