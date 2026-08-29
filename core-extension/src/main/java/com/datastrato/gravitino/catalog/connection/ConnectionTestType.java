/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.catalog.connection;

import com.google.common.base.Preconditions;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** Defines values stored in the {@code type} column of the connection test result table. */
public final class ConnectionTestType {

  /** The type for testing a Catalog's complete connection. */
  public static final String CATALOG = "CATALOG";

  /** Prefix for a credential provider connection test type. */
  public static final String CREDENTIAL_PREFIX = "CREDENTIAL:";

  private static final int MAX_TEST_TYPE_LENGTH = 256;
  private static final Pattern CREDENTIAL_TEST_TYPE_PATTERN =
      Pattern.compile("CREDENTIAL:[a-z0-9][a-z0-9._-]*");

  private ConnectionTestType() {}

  /**
   * Returns the connection test type for a canonical credential type.
   *
   * @param credentialType The canonical credential type.
   * @return The namespaced credential connection test type.
   */
  public static String credential(String credentialType) {
    Preconditions.checkArgument(
        credentialType != null && credentialType.matches("[a-z0-9][a-z0-9._-]*"),
        "Credential type must be canonical and lower-case");
    String testType = CREDENTIAL_PREFIX + credentialType;
    validate(testType);
    return testType;
  }

  /**
   * Returns whether a persisted test type belongs to a credential provider.
   *
   * @param testType The persisted connection test type.
   * @return {@code true} when the type is a valid credential provider test type.
   */
  public static boolean isCredential(@Nullable String testType) {
    return testType != null && CREDENTIAL_TEST_TYPE_PATTERN.matcher(testType).matches();
  }

  /**
   * Validates a persisted connection test type.
   *
   * @param testType The connection test type to validate.
   * @throws IllegalArgumentException If the value is not a supported connection test type.
   */
  public static void validate(String testType) {
    Preconditions.checkArgument(testType != null, "Connection test type cannot be null");
    Preconditions.checkArgument(
        testType.length() <= MAX_TEST_TYPE_LENGTH, "Connection test type is too long");
    Preconditions.checkArgument(
        CATALOG.equals(testType) || CREDENTIAL_TEST_TYPE_PATTERN.matcher(testType).matches(),
        "Invalid connection test type: %s",
        testType);
  }
}
