/*
 * Copyright 2026 Datastrato Inc.
 *
 * Added enterprise files have a copyright-only header.
 *
 * Who: files git-added vs the PR / git base.
 * Pass: ASF ports, and copyright-only Datastrato including Pvt Ltd.
 * Fail: a Datastrato copyright (Pvt Ltd or Inc.) plus the Apache grant.
 * Not: the enterprise-file Inc.-only check, and not RAT.
 *
 * From: LicenseHeaderClassifier (shouldCheckHeader, readHeader,
 *       isAddedEnterpriseFileViolation);
 *       NewFileFinder (base ref, added files).
 * Not from shared-paths: who is git-added, not the enterprise include list.
 */

import org.apache.gravitino.license.LicenseHeaderClassifier
import org.apache.gravitino.license.NewFileFinder

tasks.register("checkAddedEnterpriseFilesHaveEnterpriseOnlyHeader") {
  group = "verification"
  description =
    "Checks added enterprise files have a copyright-only header (no Apache grant)."

  // Explicit override: gradle property, else GitHub PR base ref. Blank GITHUB_BASE_REF
  // is ignored (Gradle 8.2 maps a present empty env var; it does not have Provider.filter).
  // When neither is set, ask Git for the current branch's remote upstream and fall back
  // to the remote default. Local feature branches that track their remote head should
  // set -PlicenseBaseRef to the intended target branch.
  val configuredBaseRef =
    providers
      .gradleProperty("licenseBaseRef")
      .orElse(
        providers.environmentVariable("GITHUB_BASE_REF").map { branch ->
          NewFileFinder.originRefFromGithubBranch(branch)
        }
      )

  doLast {
    val baseRef =
      NewFileFinder.resolveBaseRef(
        rootDir,
        if (configuredBaseRef.isPresent) configuredBaseRef.get() else null
      )
    logger.lifecycle("checkAddedEnterpriseFilesHaveEnterpriseOnlyHeader: using base ref '$baseRef'")

    // Resolve added files vs the base ref. Fail (don't skip) if the base ref is
    // unavailable so the new-file enforcement cannot be bypassed by a shallow
    // or fresh checkout; CI must fetch the base ref or pass -PlicenseBaseRef.
    val addedFiles =
      NewFileFinder.addedFiles(rootDir, baseRef)
        ?: throw GradleException(
          "checkAddedEnterpriseFilesHaveEnterpriseOnlyHeader could not resolve base ref '$baseRef'. " +
            "Fetch the base ref (e.g. origin/main or origin/branch-1.3) or set -PlicenseBaseRef."
        )

    val violations = mutableListOf<String>()
    addedFiles.forEach { relPath ->
      val file = rootDir.resolve(relPath)
      if (!file.isFile) return@forEach
      if (!LicenseHeaderClassifier.shouldCheckHeader(file.name)) return@forEach
      val header = LicenseHeaderClassifier.readHeader(file.toPath())
      if (LicenseHeaderClassifier.isAddedEnterpriseFileViolation(header)) {
        violations.add(relPath)
      }
    }

    if (violations.isNotEmpty()) {
      throw GradleException(
        "checkAddedEnterpriseFilesHaveEnterpriseOnlyHeader failed for ${violations.size} file(s). " +
          "New Datastrato files must use the copyright-only header " +
          "(remove the 'This software is licensed under the Apache License version 2.' line):\n" +
          violations.sorted().joinToString("\n")
      )
    }
  }
}
