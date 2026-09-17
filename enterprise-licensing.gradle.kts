/*
 * Copyright 2026 Datastrato Inc.
 *
 * Applies the enterprise license jobs.
 *
 * Who: root check, via apply(from = "enterprise-licensing.gradle.kts").
 * Not: printRatFailures and rat stay in build.gradle.kts.
 *
 * From: build.gradle.kts apply(from = "enterprise-licensing.gradle.kts").
 * To:   check depends on the jobs below. Apply shared-paths first
 *       so the jobs can read extra.enterpriseFilePatterns.
 *
 *   0. shared-paths (enterpriseFilePatterns, generatedFilePatterns)
 *   1. check-enterprise-files-have-enterprise-only-header
 *   2. check-added-enterprise-files-have-enterprise-only-header
 *   3. check-non-enterprise-files-have-asf-header
 *   4. check-connector-jars
 *   5. test-enterprise-license-headers (live negative regression)
 */

apply(from = "enterprise-licensing/shared-paths.gradle.kts")
apply(from = "enterprise-licensing/check-enterprise-files-have-enterprise-only-header.gradle.kts")
apply(from = "enterprise-licensing/check-added-enterprise-files-have-enterprise-only-header.gradle.kts")
apply(from = "enterprise-licensing/check-non-enterprise-files-have-asf-header.gradle.kts")
apply(from = "enterprise-licensing/check-connector-jars.gradle.kts")

tasks.register<Exec>("testEnterpriseLicenseHeaders") {
  group = "verification"
  description = "Exercises the live enterprise license task against negative cases."
  workingDir = rootDir
  commandLine("bash", "dev/ci/test_enterprise_license_headers.sh")
}

tasks.named("check").get().dependsOn(
  tasks.named("checkEnterpriseFilesHaveEnterpriseOnlyHeader"),
  tasks.named("checkAddedEnterpriseFilesHaveEnterpriseOnlyHeader"),
  tasks.named("checkNonEnterpriseFilesHaveAsfHeader"),
  tasks.named("testEnterpriseLicenseHeaders"),
  tasks.named("checkConnectorJars")
)
