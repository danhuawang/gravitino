/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.model;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/** SCIM token metadata plus the one-time plaintext bearer value. */
@Getter
@EqualsAndHashCode
@ToString(exclude = "tokenValue")
@Builder(setterPrefix = "with")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CreatedScimToken {
  private final String tokenName;
  private final String tokenValue;
  private final long expiresAt;
}
