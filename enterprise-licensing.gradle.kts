/*
 * Copyright 2026 Datastrato Inc.
 *
 * Applies the three enterprise license jobs.
 *
 * Who: root check, via apply(from = "enterprise-licensing.gradle.kts").
 * Not: printRatFailures and rat stay in build.gradle.kts.
 *
 * From: build.gradle.kts apply(from = "enterprise-licensing.gradle.kts").
 * To:   check depends on the three jobs below. Apply shared-paths first
 *       so the jobs can read extra.enterpriseFilePatterns.
 *
 *   0. shared-paths (enterpriseFilePatterns, generatedFilePatterns)
 *   1. check-enterprise-files-have-enterprise-only-header
 *   2. check-added-enterprise-files-have-enterprise-only-header
 *   3. check-non-enterprise-files-have-asf-header
 */

apply(from = "enterprise-licensing/shared-paths.gradle.kts")
apply(from = "enterprise-licensing/check-enterprise-files-have-enterprise-only-header.gradle.kts")
apply(from = "enterprise-licensing/check-added-enterprise-files-have-enterprise-only-header.gradle.kts")
apply(from = "enterprise-licensing/check-non-enterprise-files-have-asf-header.gradle.kts")

tasks.named("check").get().dependsOn(
  tasks.named("checkEnterpriseFilesHaveEnterpriseOnlyHeader"),
  tasks.named("checkAddedEnterpriseFilesHaveEnterpriseOnlyHeader"),
  tasks.named("checkNonEnterpriseFilesHaveAsfHeader")
)
