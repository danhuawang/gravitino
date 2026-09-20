/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim;

import javax.annotation.Nullable;

/**
 * Maximum lengths of SCIM and JIT fields persisted by the enterprise schema.
 *
 * <p>These values must match {@code scim_user_meta}, {@code scim_group_meta}, and {@code
 * scim_token_meta}, {@code jit_user_meta}, and {@code jit_group_meta} in {@code
 * scripts/enterprise/{h2,mysql,postgresql}/enterprise-schema-*.sql}. Values that cannot be stored
 * are rejected before they reach the database.
 */
public final class ScimFieldLimits {

  /** {@code user_name} and {@code group_name} column width. */
  public static final int MAX_NAME_LENGTH = 128;

  /** {@code external_id} column width. */
  public static final int MAX_EXTERNAL_ID_LENGTH = 256;

  /** {@code group_comment} column width. */
  public static final int MAX_GROUP_COMMENT_LENGTH = 1024;

  /** {@code token_name} column width. */
  public static final int MAX_TOKEN_NAME_LENGTH = 256;

  private ScimFieldLimits() {}

  /**
   * Checks the fields written to {@code scim_user_meta}.
   *
   * @param userName SCIM userName
   * @param externalId optional SCIM externalId
   */
  public static void checkUser(@Nullable String userName, @Nullable String externalId) {
    checkMaxLength(userName, MAX_NAME_LENGTH, "userName", "user");
    checkMaxLength(externalId, MAX_EXTERNAL_ID_LENGTH, "externalId", "user");
  }

  /**
   * Checks the fields written to {@code scim_group_meta}.
   *
   * @param displayName SCIM displayName
   * @param externalId optional SCIM externalId
   * @param groupComment group comment stored in {@code group_comment}
   */
  public static void checkGroup(
      @Nullable String displayName, @Nullable String externalId, @Nullable String groupComment) {
    checkMaxLength(displayName, MAX_NAME_LENGTH, "displayName", "group");
    checkMaxLength(externalId, MAX_EXTERNAL_ID_LENGTH, "externalId", "group");
    checkMaxLength(groupComment, MAX_GROUP_COMMENT_LENGTH, "group comment", "group");
  }

  /**
   * Checks one externalId update.
   *
   * @param externalId optional SCIM externalId
   * @param resource {@code user} or {@code group}
   */
  public static void checkExternalId(@Nullable String externalId, String resource) {
    checkMaxLength(externalId, MAX_EXTERNAL_ID_LENGTH, "externalId", resource);
  }

  /**
   * Checks the client-supplied name written to {@code scim_token_meta}.
   *
   * @param tokenName SCIM token name
   */
  public static void checkToken(@Nullable String tokenName) {
    checkMaxLength(tokenName, MAX_TOKEN_NAME_LENGTH, "tokenName", "token");
  }

  /**
   * Checks that a value does not exceed the given maximum number of characters, counted in Unicode
   * code points.
   *
   * @param value the value to check; {@code null} always passes
   * @param maxLength the maximum number of characters allowed
   * @param fieldName the SCIM field name used in the error message
   * @param resource the SCIM resource, {@code user} or {@code group}
   * @throws IllegalArgumentException if the value exceeds {@code maxLength}
   */
  public static void checkMaxLength(
      @Nullable String value, int maxLength, String fieldName, String resource) {
    if (exceedsMaxLength(value, maxLength)) {
      throw new IllegalArgumentException(exceedMaxLengthMessage(fieldName, resource, maxLength));
    }
  }

  /**
   * Checks a name written to a JIT identity table.
   *
   * @param value the user or group name to check
   * @param fieldName {@code userName} or {@code groupName}
   * @throws IllegalArgumentException if the name exceeds the JIT column width
   */
  public static void checkJitName(@Nullable String value, String fieldName) {
    if (exceedsMaxLength(value, MAX_NAME_LENGTH)) {
      throw new IllegalArgumentException(
          String.format(
              "The %s of the JIT identity must not exceed %d characters",
              fieldName, MAX_NAME_LENGTH));
    }
  }

  private static boolean exceedsMaxLength(@Nullable String value, int maxLength) {
    // MySQL (utf8mb4) and PostgreSQL count a supplementary character as one character.
    return value != null && value.codePointCount(0, value.length()) > maxLength;
  }

  static String exceedMaxLengthMessage(String fieldName, String resource, int maxLength) {
    return String.format(
        "The %s of the SCIM %s must not exceed %d characters", fieldName, resource, maxLength);
  }
}
