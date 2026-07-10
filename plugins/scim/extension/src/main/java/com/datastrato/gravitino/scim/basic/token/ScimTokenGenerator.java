/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.basic.token;

import com.google.common.base.Preconditions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;

/** Generates opaque SCIM bearer tokens and their SHA-256 digests for persistence. */
public final class ScimTokenGenerator {
  public static final String TOKEN_PREFIX = "gravitino_scim_";
  private static final int TOKEN_ENTROPY_BYTES = 32;
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private ScimTokenGenerator() {}

  /**
   * Generates a new opaque bearer token and its SHA-256 hex digest.
   *
   * @return generated token material
   */
  public static GeneratedToken generate() {
    byte[] entropy = new byte[TOKEN_ENTROPY_BYTES];
    SECURE_RANDOM.nextBytes(entropy);
    String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    String tokenValue = TOKEN_PREFIX + encoded;
    return new GeneratedToken(tokenValue, hashToken(tokenValue));
  }

  /**
   * Returns whether the presented bearer token uses the SCIM opaque prefix.
   *
   * @param bearerToken presented bearer token
   * @return true when the token starts with {@link #TOKEN_PREFIX}
   */
  public static boolean hasValidPrefix(String bearerToken) {
    return StringUtils.isNotBlank(bearerToken) && bearerToken.startsWith(TOKEN_PREFIX);
  }

  /**
   * Computes the lowercase SHA-256 hex digest of the full bearer token.
   *
   * @param tokenValue full bearer token value
   * @return SHA-256 hex digest
   */
  public static String hashToken(String tokenValue) {
    Preconditions.checkArgument(StringUtils.isNotBlank(tokenValue), "tokenValue is empty");
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashed = digest.digest(tokenValue.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(hashed.length * 2);
      for (byte value : hashed) {
        builder.append(String.format("%02x", value));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
    }
  }

  /** Generated opaque token material. */
  @Getter
  @EqualsAndHashCode
  @ToString(exclude = "tokenValue")
  @AllArgsConstructor(access = AccessLevel.PRIVATE)
  public static final class GeneratedToken {
    private final String tokenValue;
    private final String tokenHash;
  }
}
