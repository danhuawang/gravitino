/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.license;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Header classification for the repo licensing policy.
 *
 * <p>{@link #isViolation(Path)} is the new-file rule: a file fails iff its header region carries
 * both a Datastrato copyright ({@code Pvt Ltd} or {@code Inc.}) and {@link #APACHE_SENTENCE}.
 * Upstream ASF ports and copyright-only Datastrato files pass, including {@code Pvt Ltd}.
 *
 * <p>{@link #isListedFileViolation(String)} is the listed-file rule used by {@code
 * checkDatastratoLicenseHeaders}: the header must be copyright-only {@code Copyright YYYY
 * Datastrato Inc.}. {@code Pvt Ltd}, the Apache grant line, and a missing Datastrato copyright
 * fail. The year is not checked.
 */
public final class LicenseHeaderClassifier {

  private static final Pattern DATASTRATO_HEADER =
      Pattern.compile("Copyright\\s+\\d{4}\\s+Datastrato (?:Pvt Ltd|Inc)\\.");

  private static final Pattern APPROVED_DATASTRATO_HEADER =
      Pattern.compile("Copyright\\s+\\d{4}\\s+Datastrato Inc\\.");

  /** Apache license sentence that new Datastrato files must NOT carry. */
  public static final String APACHE_SENTENCE =
      "This software is licensed under the Apache License version 2.";

  /** Listed-file failure when the header is not {@code Copyright YYYY Datastrato Inc.}. */
  public static final String LISTED_MISSING_APPROVED_HEADER =
      "missing Copyright YYYY Datastrato Inc.";

  /** Listed-file failure when the header still carries {@link #APACHE_SENTENCE}. */
  public static final String LISTED_APACHE_GRANT_NOT_ALLOWED =
      "Apache grant sentence is not allowed";

  /** Number of leading lines considered the header region. */
  public static final int HEADER_LINE_COUNT = 20;

  /** Source extensions covered by the new-file and enterprise header checks. */
  private static final Set<String> SUPPORTED_EXTENSIONS =
      Collections.unmodifiableSet(
          new HashSet<>(
              java.util.Arrays.asList("java", "scala", "kt", "kts", "py", "sh", "template", "conf")));

  /** Exact file names (no extension) covered by the checks. */
  private static final Set<String> SUPPORTED_FILE_NAMES =
      Collections.unmodifiableSet(
          new HashSet<>(java.util.Arrays.asList("Dockerfile", "Jenkinsfile")));

  private LicenseHeaderClassifier() {}

  /**
   * Whether a file is in scope for the license checks: a supported source extension, or a {@code
   * Dockerfile}/{@code Dockerfile.*}/{@code Jenkinsfile} name. Non-source files (e.g. {@code .md},
   * {@code .json}) are out of scope and intentionally skipped.
   */
  public static boolean isSupportedFile(String fileName) {
    String name = fileName;
    int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
    if (slash >= 0) {
      name = fileName.substring(slash + 1);
    }
    int dot = name.lastIndexOf('.');
    String ext = (dot >= 0 && dot < name.length() - 1) ? name.substring(dot + 1) : "";
    if (!ext.isEmpty() && SUPPORTED_EXTENSIONS.contains(ext)) {
      return true;
    }
    return name.equals("Dockerfile") || name.startsWith("Dockerfile.") || name.equals("Jenkinsfile");
  }

  /** Returns the first {@link #HEADER_LINE_COUNT} lines of {@code file}, joined with newlines. */
  public static String readHeader(Path file) throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      StringBuilder sb = new StringBuilder();
      String line;
      int n = 0;
      while (n < HEADER_LINE_COUNT && (line = reader.readLine()) != null) {
        if (n > 0) {
          sb.append("\n");
        }
        sb.append(line);
        n++;
      }
      return sb.toString();
    }
  }

  /**
   * Whether {@code header} contains a Datastrato copyright line ({@code Pvt Ltd} or {@code Inc.}).
   */
  public static boolean hasDatastratoCopyright(String header) {
    return DATASTRATO_HEADER.matcher(header).find();
  }

  /**
   * Whether {@code header} contains the approved listed-file form {@code Copyright YYYY Datastrato
   * Inc.}.
   */
  public static boolean hasApprovedDatastratoCopyright(String header) {
    return APPROVED_DATASTRATO_HEADER.matcher(header).find();
  }

  /** Whether {@code header} contains the Apache license sentence. */
  public static boolean hasApacheSentence(String header) {
    return header.contains(APACHE_SENTENCE);
  }

  /**
   * Whether a listed enterprise file fails the post-migration check: it lacks {@code Datastrato
   * Inc.} copyright, or still carries {@link #APACHE_SENTENCE}.
   */
  public static boolean isListedFileViolation(String header) {
    return listedFileViolationReason(header) != null;
  }

  /**
   * Why a listed enterprise file fails {@code checkDatastratoLicenseHeaders}, or {@code null} if it
   * passes.
   */
  public static String listedFileViolationReason(String header) {
    if (!hasApprovedDatastratoCopyright(header)) {
      return LISTED_MISSING_APPROVED_HEADER;
    }
    if (hasApacheSentence(header)) {
      return LISTED_APACHE_GRANT_NOT_ALLOWED;
    }
    return null;
  }

  /**
   * Whether {@code file} violates the new-file policy: it carries both a Datastrato copyright and the
   * Apache license sentence in its header region.
   */
  public static boolean isViolation(Path file) throws IOException {
    String header = readHeader(file);
    return hasDatastratoCopyright(header) && hasApacheSentence(header);
  }
}
