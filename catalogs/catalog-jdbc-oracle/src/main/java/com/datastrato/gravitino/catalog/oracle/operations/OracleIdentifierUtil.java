/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.catalog.oracle.operations;

import com.datastrato.gravitino.catalog.oracle.OracleCatalogCapability;
import com.google.common.base.Preconditions;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;

/**
 * Converts a Gravitino logical identifier to its physical Oracle form, following Oracle's own
 * native case-folding rule: an unquoted identifier is folded to uppercase and compared
 * case-insensitively; a quoted identifier (e.g. {@code "Foo"}) preserves its exact case and is
 * compared case-sensitively.
 *
 * <p>Gravitino encodes "this identifier is quoted" directly in the input name string using literal
 * double-quote characters (e.g. {@code "MyTable"}, including the two {@code "} characters, means
 * "case-sensitive, exact case {@code MyTable}"). This encoding is a transient signal, evaluated
 * once by {@link #toPhysicalName} whenever a name is specified — the resulting physical name is a
 * plain string, never itself containing a literal quote character, so a later reference to the same
 * object must re-supply the quoted form to be treated as case-sensitive again.
 */
public final class OracleIdentifierUtil {

  private static final char QUOTE = '"';
  private static final String DOUBLE_QUOTE = "\"";

  private OracleIdentifierUtil() {}

  /** Returns true if {@code name} is syntactically quoted: starts and ends with {@code "}. */
  public static boolean isQuoted(String name) {
    return name.length() >= 2 && name.charAt(0) == QUOTE && name.charAt(name.length() - 1) == QUOTE;
  }

  /**
   * Strips the surrounding quotes from a quoted identifier.
   *
   * <p>Oracle does not support an embedded {@code "} inside a quoted identifier at all — unlike
   * ANSI SQL/PostgreSQL, there is no {@code ""}-doubling escape (Oracle rejects it with {@code
   * ORA-25716: The identifier contains a double quotation mark}) — so any {@code "} found inside
   * the quotes, doubled or not, means the input cannot address a real Oracle object.
   *
   * <p>Example: {@code unquote("\"MyTable\"")} returns {@code MyTable}.
   *
   * @throws IllegalArgumentException if {@code quotedName} is not quoted, or its content contains
   *     an embedded {@code "}.
   */
  public static String unquote(String quotedName) {
    Preconditions.checkArgument(isQuoted(quotedName), "Not a quoted identifier: %s", quotedName);
    String inner = quotedName.substring(1, quotedName.length() - 1);
    Preconditions.checkArgument(
        inner.indexOf(QUOTE) < 0,
        "Oracle does not support an embedded '\"' in a quoted identifier: %s",
        quotedName);
    return inner;
  }

  /**
   * Wraps {@code content} in double quotes.
   *
   * <p>Example: given the plain, unquoted content {@code MyTable} (6 characters, no quote marks),
   * {@code quote("MyTable")} returns the 8-character string consisting of {@code MyTable} with a
   * literal {@code "} character added at the start and another at the end.
   *
   * @throws IllegalArgumentException if {@code content} contains a {@code "}, since Oracle cannot
   *     represent an identifier with an embedded double quote (see {@link #unquote}).
   */
  public static String quote(String content) {
    Preconditions.checkArgument(
        content.indexOf(QUOTE) < 0,
        "Oracle does not support an embedded '\"' in a quoted identifier: %s",
        content);
    return DOUBLE_QUOTE + content + DOUBLE_QUOTE;
  }

  /**
   * Returns the physical Oracle identifier for a Gravitino logical name: a quoted logical name is
   * unquoted and returned with its case preserved; an unquoted logical name is uppercased, matching
   * Oracle's own unquoted-identifier folding rule.
   *
   * <p>Example: {@code toPhysicalName("app_user")} returns {@code APP_USER}; {@code
   * toPhysicalName("\"MyTable\"")} returns {@code MyTable}.
   */
  public static String toPhysicalName(String name) {
    Preconditions.checkArgument(
        StringUtils.isNotEmpty(name), "Identifier name cannot be null or empty.");
    if (isQuoted(name)) {
      return unquote(name);
    }
    return name.toUpperCase(Locale.ROOT);
  }

  /**
   * Converts a Gravitino logical identifier to its physical Oracle form and wraps it in double
   * quotes, ready to be embedded in generated SQL. For a quoted logical name, this re-quotes the
   * unquoted content (guarding against malformed input reaching the generated SQL) with its case
   * preserved. For an unquoted logical name, this uppercases it first: quoting the uppercased name
   * is physically equivalent to using an unquoted identifier (Oracle folds unquoted to uppercase),
   * but also protects reserved words such as {@code comment}, {@code number}, or {@code date} that
   * would fail as unquoted identifiers.
   *
   * <p>Example: {@code quotedName("app_user")} returns {@code "APP_USER"}; {@code
   * quotedName("\"MyTable\"")} returns {@code "MyTable"}.
   *
   * @throws IllegalArgumentException if {@code name}'s content contains an embedded {@code "} (see
   *     {@link #quote}) — this also covers index/partition names, which are not validated against
   *     {@link OracleCatalogCapability#ORACLE_NAME_PATTERN}.
   */
  public static String quotedName(String name) {
    return quote(toPhysicalName(name));
  }
}
