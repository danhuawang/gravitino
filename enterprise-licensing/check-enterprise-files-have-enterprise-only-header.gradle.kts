/*
 * Copyright 2026 Datastrato Inc.
 *
 * Enterprise files have an Inc. copyright-only header.
 *
 * Who: enterpriseFilePatterns, minus generatedFilePatterns, names that
 * should not be header-checked, and
 * enterpriseFilesExcludedFromEnterpriseOnlyHeader.
 * Pass: Copyright YYYY Datastrato Inc. with no Apache grant.
 * Fail: Pvt Ltd, grant line, or missing Inc. copyright. Year is not
 * checked.
 * Not: the added-file check (Pvt Ltd still allowed there).
 *
 * From: shared-paths.gradle.kts (enterpriseFilePatterns, generatedFilePatterns);
 *       LicenseHeaderClassifier (shouldCheckEnterpriseOnlyHeader, readHeader,
 *       enterpriseOnlyHeaderViolationReason).
 */

import java.io.File
import org.apache.gravitino.license.LicenseHeaderClassifier

// From enterprise-licensing/shared-paths.gradle.kts (parent applies it first).
val enterpriseFilePatterns: List<String> by extra
val generatedFilePatterns: List<String> by extra

// Repo-relative paths that match the who-list but are not required to
// have the Inc. copyright-only header. Empty until we have one.
val enterpriseFilesExcludedFromEnterpriseOnlyHeader = setOf<String>(
  // keep-sorted start
  // keep-sorted end
)

tasks.register("checkEnterpriseFilesHaveEnterpriseOnlyHeader") {
  group = "verification"
  description =
    "Checks enterprise files have an Inc. copyright-only header."

  // Execution only. The register block runs on every Gradle configure.
  doLast {
    val files = enterpriseFiles()
    val violations = findEnterpriseOnlyHeaderViolations(files)
    if (violations.isNotEmpty()) {
      throw enterpriseOnlyHeaderException(violations)
    }
    logger.lifecycle(
      "checkEnterpriseFilesHaveEnterpriseOnlyHeader: ${files.size} file(s) ok"
    )
  }
}

fun enterpriseFiles(): List<String> =
  fileTree(rootDir) {
    enterpriseFilePatterns.forEach { include(it) }
    generatedFilePatterns.forEach { exclude(it) }
  }.files
    .filter { it.isFile && LicenseHeaderClassifier.shouldCheckEnterpriseOnlyHeader(it.name) }
    .map { repoRelativePath(it) }
    .filter { it !in enterpriseFilesExcludedFromEnterpriseOnlyHeader }
    .sorted()

// Force '/' so paths match enterpriseFilesExcludedFromEnterpriseOnlyHeader.
fun repoRelativePath(file: File): String =
  file.relativeTo(rootDir).path.replace(File.separatorChar, '/')

fun findEnterpriseOnlyHeaderViolations(relativePaths: List<String>): List<String> =
  relativePaths.mapNotNull { findEnterpriseOnlyHeaderViolation(it) }

// Violation → the repo-relative path. Pass → null.
fun findEnterpriseOnlyHeaderViolation(relativePath: String): String? {
  val header = LicenseHeaderClassifier.readHeader(rootDir.resolve(relativePath).toPath())
  // Inc. copyright-only. Pvt Ltd fails. Year is not checked.
  if (LicenseHeaderClassifier.enterpriseOnlyHeaderViolationReason(header) == null) {
    return null
  }
  return relativePath
}

fun enterpriseOnlyHeaderException(violations: List<String>): GradleException =
  GradleException(
    "checkEnterpriseFilesHaveEnterpriseOnlyHeader failed for ${violations.size} file(s). " +
      "Enterprise files must use copyright-only `Copyright YYYY Datastrato Inc.`:\n" +
      violations.joinToString("\n")
  )
