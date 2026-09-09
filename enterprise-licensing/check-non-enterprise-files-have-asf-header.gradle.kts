/*
 * Copyright 2026 Datastrato Inc.
 *
 * Non-enterprise files have an ASF Apache 2.0 header.
 *
 * This script does not check headers. It builds filesWithheldFromRat.
 * checkNonEnterpriseFilesHaveAsfHeader depends on rat; rat checks the rest.
 *
 * Who: files withheld from RAT — enterpriseFilePatterns +
 * nonEnterpriseFilesWithDatastratoCopyright.
 * generatedFilePatterns are skipped in that walk; they are not withheld.
 * Pass: ASF header (Apache RAT).
 * Fail: RAT unapproved licenses. Withheld files are not Inc.-checked.
 * Not: printRatFailures and the rat task stay in build.gradle.kts.
 *
 * From: shared-paths.gradle.kts (enterpriseFilePatterns, generatedFilePatterns);
 *       LicenseHeaderClassifier (shouldCheckHeader, readHeader, hasDatastratoCopyright).
 * To:   build.gradle.kts tasks.rat via extra["filesWithheldFromRat"].
 *       dependsOn("rat") — that task is defined in build.gradle.kts.
 * When: filesWithheldFromRat is built at configure time. build.gradle.kts
 *       tasks.rat { setExcludes(...) } reads it in the same configure, after
 *       this apply. rat does not read extra again at execute. rat runs when
 *       this task, `rat`, or `check` is invoked.
 */

import java.io.File
import org.apache.gravitino.license.LicenseHeaderClassifier

// From enterprise-licensing/shared-paths.gradle.kts (parent applies it first).
val enterpriseFilePatterns: List<String> by extra
val generatedFilePatterns: List<String> by extra

tasks.register("checkNonEnterpriseFilesHaveAsfHeader") {
  group = "verification"
  description = "Runs Apache RAT on files not withheld below."
  // Thin alias. rat (build.gradle.kts) runs first whenever this task or
  // check is invoked. The withhold list below is already set at configure.
  dependsOn("rat")
}

// Made of:
//   enterpriseFilePatterns — whole enterprise trees, by glob. No header read.
//   mixedDistributionLicenseFiles — root distribution files that do not carry
//   an Apache source header.
//   nonEnterpriseFilesWithDatastratoCopyright — leftover files (not those
//   globs, not generated) that already have a Datastrato copyright.
// Generated is a walk skip, not a withhold.
// Used at configure time only: build.gradle.kts tasks.rat { setExcludes(
//   excludes + extra["filesWithheldFromRat"]) } runs after this apply.
val mixedDistributionLicenseFiles =
  listOf(
    "LICENSE",
    "LICENSE-APACHE",
    "LICENSE-ENTERPRISE"
  )

val filesWithheldFromRat =
  enterpriseFilePatterns +
    mixedDistributionLicenseFiles +
    nonEnterpriseFilesWithDatastratoCopyright()
project.extra["filesWithheldFromRat"] = filesWithheldFromRat

fun nonEnterpriseFilesWithDatastratoCopyright(): List<String> =
  nonEnterpriseFiles()
    .filter { hasDatastratoCopyright(it) }
    .map { repoRelativePath(it) }
    .sorted()

fun nonEnterpriseFiles(): Collection<File> =
  fileTree(rootDir) {
    generatedFilePatterns.forEach { exclude(it) }
    enterpriseFilePatterns.forEach { exclude(it) }
  }.files

fun hasDatastratoCopyright(file: File): Boolean {
  if (!file.isFile || !LicenseHeaderClassifier.shouldCheckHeader(file.name)) {
    return false
  }
  val header = LicenseHeaderClassifier.readHeader(file.toPath())
  return LicenseHeaderClassifier.hasDatastratoCopyright(header)
}

// Force '/' so paths match enterpriseFilePatterns in filesWithheldFromRat.
fun repoRelativePath(file: File): String =
  file.relativeTo(rootDir).path.replace(File.separatorChar, '/')
