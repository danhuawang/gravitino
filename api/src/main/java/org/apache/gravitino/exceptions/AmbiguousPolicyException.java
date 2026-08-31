/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.exceptions;

import com.google.common.base.Preconditions;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import java.util.Arrays;

/** Exception thrown when an operation requires one policy but multiple policies apply. */
public class AmbiguousPolicyException extends IllegalArgumentException {

  /** Names of the policies that caused the ambiguity. */
  private final String[] matchedPolicyNames;

  /**
   * Constructs an exception with the specified detail message.
   *
   * @param message the detail message
   * @param args the arguments to the message
   */
  @FormatMethod
  public AmbiguousPolicyException(@FormatString String message, Object... args) {
    this(new String[0], message, args);
  }

  /**
   * Constructs an exception with the policies that caused the ambiguity.
   *
   * @param matchedPolicyNames names of the matching policies
   * @param message the detail message
   * @param args the arguments to the message
   */
  @FormatMethod
  public AmbiguousPolicyException(
      String[] matchedPolicyNames, @FormatString String message, Object... args) {
    super(String.format(message, args));
    Preconditions.checkArgument(matchedPolicyNames != null, "matchedPolicyNames cannot be null");

    this.matchedPolicyNames = matchedPolicyNames.clone();
    Arrays.sort(this.matchedPolicyNames);
  }

  /**
   * Returns the matching policy names in deterministic order.
   *
   * @return defensively copied policy names
   */
  public String[] matchedPolicyNames() {
    return matchedPolicyNames.clone();
  }
}
