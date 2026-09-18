/*
 * Copyright 2026 Datastrato Inc.
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
   * Default maximum identifier length, in bytes, when the connected Oracle server's actual limit is
   * unknown (e.g. the capability is built without a connection). 128 is the limit for Oracle 12.2
   * and later, which covers every currently supported Oracle release; older servers report their
   * lower limit (30) and it is applied instead (see {@link OracleCatalog#newCapability()}).
   */
  public static final int DEFAULT_MAX_IDENTIFIER_LENGTH_BYTES = 128;

  /**
   * The unquoted-identifier grammar without the length bound: it must start with a letter and
   * otherwise contain only letters, digits, underscores, dollar signs, or hash signs. The length is
   * enforced separately against {@link #maxIdentifierLengthBytes} so that the limit can follow the
   * connected Oracle server's version rather than being hardcoded.
   *
   * <p>An unquoted identifier is ASCII-only under this grammar, so its character count equals its
   * byte length and comparing {@link String#length()} against the byte limit is exact.
   */
  private static final String ORACLE_UNQUOTED_NAME_PATTERN = "^[A-Za-z][A-Za-z0-9_$#]*$";

  /**
   * Maximum length, in bytes of the database character set, of an Oracle identifier for the
   * connected server. Oracle enforces this limit in bytes, not characters, so a quoted identifier's
   * content is checked against its UTF-8 encoded length rather than {@link String#length()} —
   * otherwise a multi-byte name (e.g. CJK characters) could pass this check but still be rejected
   * by Oracle as too long. Oracle raised this limit from 30 to 128 bytes in 12.2.
   */
  private final int maxIdentifierLengthBytes;

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

  /**
   * Creates a capability with the {@link #DEFAULT_MAX_IDENTIFIER_LENGTH_BYTES default} identifier
   * length limit, used when the connected Oracle server's actual limit has not been resolved.
   */
  public OracleCatalogCapability() {
    this(DEFAULT_MAX_IDENTIFIER_LENGTH_BYTES);
  }

  /**
   * Creates a capability that enforces the given maximum identifier length, in bytes.
   *
   * @param maxIdentifierLengthBytes the connected Oracle server's maximum identifier length in
   *     bytes (30 before 12.2, 128 for 12.2 and later); a non-positive value falls back to {@link
   *     #DEFAULT_MAX_IDENTIFIER_LENGTH_BYTES}.
   */
  public OracleCatalogCapability(int maxIdentifierLengthBytes) {
    this.maxIdentifierLengthBytes =
        maxIdentifierLengthBytes > 0
            ? maxIdentifierLengthBytes
            : DEFAULT_MAX_IDENTIFIER_LENGTH_BYTES;
  }

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
      if (innerByteLength > maxIdentifierLengthBytes) {
        return CapabilityResult.unsupported(
            String.format(
                "The %s name '%s' exceeds the maximum quoted identifier length of %d bytes.",
                scope, name, maxIdentifierLengthBytes));
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

    if (!name.matches(ORACLE_UNQUOTED_NAME_PATTERN)) {
      return CapabilityResult.unsupported(
          String.format("The %s name '%s' is illegal.", scope, name));
    }
    // An unquoted identifier is ASCII-only under ORACLE_UNQUOTED_NAME_PATTERN, so its character
    // count equals its byte length; enforce the server's identifier limit separately from the
    // grammar so the bound follows the connected Oracle version.
    if (name.length() > maxIdentifierLengthBytes) {
      return CapabilityResult.unsupported(
          String.format(
              "The %s name '%s' exceeds the maximum identifier length of %d bytes.",
              scope, name, maxIdentifierLengthBytes));
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
