/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.license;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Header classification for the repo licensing policy.
 *
 * <p>Public API, in caller order: {@link #shouldCheckHeader} (name kind), {@link #readHeader}
 * (I/O), then the two job predicates, then the header facts.
 *
 * <p>{@link #enterpriseOnlyHeaderViolationReason(String)} is the Inc. job: copyright-only {@code
 * Copyright YYYY Datastrato Inc.}. {@code Pvt Ltd}, the grant line, and a missing Inc. copyright
 * fail. The year is not checked.
 *
 * <p>{@link #isAddedEnterpriseFileViolation(String)} is the added-file job: fail iff the header
 * carries both a Datastrato copyright ({@code Pvt Ltd} or {@code Inc.}) and {@link
 * #APACHE_SENTENCE}. ASF ports and copyright-only Datastrato files pass, including {@code Pvt Ltd}.
 */
public final class LicenseHeaderClassifier {

  private static final Pattern DATASTRATO_HEADER =
      Pattern.compile("Copyright\\s+\\d{4}\\s+Datastrato (?:Pvt Ltd|Inc)\\.");

  private static final Pattern APPROVED_DATASTRATO_HEADER =
      Pattern.compile("Copyright\\s+\\d{4}\\s+Datastrato Inc\\.");

  /** Apache license sentence that new Datastrato files must NOT carry. */
  public static final String APACHE_SENTENCE =
      "This software is licensed under the Apache License version 2.";

  /** Enterprise-file failure when the header is not {@code Copyright YYYY Datastrato Inc.}. */
  public static final String ENTERPRISE_ONLY_MISSING_INC_COPYRIGHT =
      "missing Copyright YYYY Datastrato Inc.";

  /** Enterprise-file failure when the header still carries {@link #APACHE_SENTENCE}. */
  public static final String ENTERPRISE_ONLY_APACHE_GRANT_NOT_ALLOWED =
      "Apache grant sentence is not allowed";

  /** Number of leading lines considered the header region. */
  public static final int HEADER_LINE_COUNT = 20;

  /** Extensions of files that carry a license header in this repo. */
  private static final Set<String> EXTENSIONS_WITH_LICENSE_HEADERS =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  // keep-sorted start
                  "conf",
                  "java",
                  "kt",
                  "kts",
                  "py",
                  "scala",
                  "sh",
                  "template"
                  // keep-sorted end
                  )));

  /** Exact basenames that carry a license header in this repo. */
  private static final Set<String> FILE_NAMES_WITH_LICENSE_HEADERS =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  // keep-sorted start
                  "Dockerfile",
                  "Jenkinsfile"
                  // keep-sorted end
                  )));

  /** Basename prefixes that carry a license header (e.g. {@code Dockerfile.}). */
  private static final Set<String> FILE_NAME_PREFIXES_WITH_LICENSE_HEADERS =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  // keep-sorted start
                  "Dockerfile."
                  // keep-sorted end
                  )));

  private LicenseHeaderClassifier() {}

  /**
   * Whether we should header-check this basename. Matches the extension, exact-name, and prefix
   * lists below. Does not read the file.
   */
  public static boolean shouldCheckHeader(String fileName) {
    return hasLicenseHeaderExtension(fileName)
        || isLicenseHeaderFileName(fileName)
        || hasLicenseHeaderPrefix(fileName);
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
   * Why a header fails {@code checkEnterpriseFilesHaveEnterpriseOnlyHeader}, or {@code null} if it
   * passes.
   */
  public static String enterpriseOnlyHeaderViolationReason(String header) {
    if (!hasApprovedDatastratoCopyright(header)) {
      return ENTERPRISE_ONLY_MISSING_INC_COPYRIGHT;
    }
    if (hasApacheSentence(header)) {
      return ENTERPRISE_ONLY_APACHE_GRANT_NOT_ALLOWED;
    }
    return null;
  }

  /**
   * Whether {@code header} fails the added-file rule: it carries both a Datastrato copyright and
   * the Apache license sentence.
   */
  public static boolean isAddedEnterpriseFileViolation(String header) {
    return hasDatastratoCopyright(header) && hasApacheSentence(header);
  }

  /**
   * Whether {@code header} contains a Datastrato copyright line ({@code Pvt Ltd} or {@code Inc.}).
   */
  public static boolean hasDatastratoCopyright(String header) {
    return DATASTRATO_HEADER.matcher(header).find();
  }

  /**
   * Whether {@code header} contains the approved Inc. form {@code Copyright YYYY Datastrato
   * Inc.}.
   */
  public static boolean hasApprovedDatastratoCopyright(String header) {
    return APPROVED_DATASTRATO_HEADER.matcher(header).find();
  }

  /** Whether {@code header} contains the Apache license sentence. */
  public static boolean hasApacheSentence(String header) {
    return header.contains(APACHE_SENTENCE);
  }

  private static boolean hasLicenseHeaderExtension(String name) {
    int dot = name.lastIndexOf('.');
    return dot >= 0 && EXTENSIONS_WITH_LICENSE_HEADERS.contains(name.substring(dot + 1));
  }

  private static boolean isLicenseHeaderFileName(String name) {
    return FILE_NAMES_WITH_LICENSE_HEADERS.contains(name);
  }

  private static boolean hasLicenseHeaderPrefix(String name) {
    return FILE_NAME_PREFIXES_WITH_LICENSE_HEADERS.stream().anyMatch(name::startsWith);
  }
}
