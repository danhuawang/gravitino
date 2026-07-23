/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.oracle;

import com.datastrato.gravitino.catalog.oracle.operations.OracleIdentifierUtil;
import com.google.common.collect.ImmutableSet;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.connector.capability.CapabilityResult;

/**
 * Oracle-specific {@link Capability}: an unquoted identifier is folded to uppercase and validated
 * against Oracle's unquoted-identifier grammar, while a quoted identifier (e.g. {@code "MyTable"})
 * preserves its exact case and is validated only against Oracle's quoted-identifier length limit.
 */
public class OracleCatalogCapability implements Capability {
  /**
   * Regular expression explanation: ^[A-Za-z][A-Za-z0-9_$#]{0,29}$
   *
   * <p>^ - Start of the string
   *
   * <p>[A-Za-z] - Must start with a letter (upper or lower case)
   *
   * <p>[A-Za-z0-9_$#]{0,29} - Followed by 0 to 29 characters of letters, digits, underscores,
   * dollar signs, or hash signs
   *
   * <p>$ - End of the string
   *
   * <p>Total length: 1 to 30 characters (Oracle's maximum identifier length for versions &lt; 12.2)
   */
  public static final String ORACLE_NAME_PATTERN = "^[A-Za-z][A-Za-z0-9_$#]{0,29}$";

  /**
   * Maximum length, in bytes of the database character set, of the content inside a quoted Oracle
   * identifier: the same 30-byte hard limit as {@link #ORACLE_NAME_PATTERN} (Oracle's maximum
   * identifier length for versions &lt; 12.2), since quoting does not relax Oracle's length
   * restriction. Oracle enforces this limit in bytes, not characters, so this is checked against
   * the UTF-8 encoded length rather than {@link String#length()} — otherwise a multi-byte name
   * (e.g. CJK characters) could pass this check but still be rejected by Oracle as too long.
   */
  private static final int MAX_QUOTED_NAME_LENGTH_BYTES = 30;

  /**
   * Characters Gravitino rejects inside a quoted Oracle identifier's content, even though Oracle
   * itself accepts each of them verbatim: {@code .} would be misparsed by {@link
   * MetadataObject#fullName()}'s unescaped dot-separated join.
   *
   * <p>NUL corrupts the generated DDL statement's own syntax (confirmed against a live Oracle
   * instance: it causes {@code ORA-01740: missing double quote in identifier} rather than a clean
   * rejection). Other rare control characters (e.g. U+0001, tab) round-trip fine and are not
   * restricted.
   */
  private static final Set<Character> ILLEGAL_QUOTED_CONTENT_CHARACTERS =
      ImmutableSet.of('.', '\0');

  @Override
  public CapabilityResult specificationOnName(Scope scope, String name) {
    if (OracleIdentifierUtil.isQuoted(name)) {
      String inner;
      try {
        inner = OracleIdentifierUtil.unquote(name);
      } catch (IllegalArgumentException e) {
        return CapabilityResult.unsupported(
            String.format("The %s name '%s' is a malformed quoted identifier.", scope, name));
      }
      if (inner.isEmpty()) {
        return CapabilityResult.unsupported(
            String.format("The %s name '%s' has empty quoted content.", scope, name));
      }
      int innerByteLength = inner.getBytes(StandardCharsets.UTF_8).length;
      if (innerByteLength > MAX_QUOTED_NAME_LENGTH_BYTES) {
        return CapabilityResult.unsupported(
            String.format(
                "The %s name '%s' exceeds the maximum quoted identifier length of %d bytes.",
                scope, name, MAX_QUOTED_NAME_LENGTH_BYTES));
      }
      for (char illegal : ILLEGAL_QUOTED_CONTENT_CHARACTERS) {
        if (inner.indexOf(illegal) >= 0) {
          return CapabilityResult.unsupported(
              String.format(
                  "The %s name '%s' contains an illegal character '%s'.", scope, name, illegal));
        }
      }
      return CapabilityResult.SUPPORTED;
    }

    if (!name.matches(ORACLE_NAME_PATTERN)) {
      return CapabilityResult.unsupported(
          String.format("The %s name '%s' is illegal.", scope, name));
    }
    return CapabilityResult.SUPPORTED;
  }

  /**
   * Oracle identifiers are folded based on Gravitino's quoted-name convention, not a simple
   * case-sensitive/insensitive toggle. See {@link #normalizeName(Scope, String)} for the actual
   * folding rule.
   */
  @Override
  public CapabilityResult caseSensitiveOnName(Scope scope) {
    return CapabilityResult.unsupported(
        "Oracle identifiers are folded via a custom rule; see normalizeName().");
  }

  /**
   * Converts {@code name} to its physical Oracle form ({@link
   * OracleIdentifierUtil#toPhysicalName}): a quoted name (e.g. {@code "MyTable"}) is unquoted with
   * its case preserved; an unquoted name is folded to uppercase, matching Oracle's own
   * case-insensitive, uppercase-folding rule. The quoting is a one-time signal evaluated here — the
   * returned name is a plain string that never itself contains a literal quote character, so a
   * later reference to the same object must re-supply the quoted form to be normalized the same way
   * again.
   */
  @Override
  public String normalizeName(Scope scope, String name) {
    return OracleIdentifierUtil.toPhysicalName(name);
  }
}
