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
 *   6. apply-image-license-catalog (regenerate LICENSE.bin/NOTICE.bin from CSV)
 *   7. test-image-license-catalog
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

tasks.register<Exec>("applyImageLicenseCatalog") {
  group = "verification"
  description = "Regenerate LICENSE.bin, NOTICE.bin, and image /licenses from the verified CSV."
  workingDir = rootDir
  commandLine("bash", "dev/docker/licenses-catalog/apply.sh")
  inputs.dir(layout.projectDirectory.dir("dev/docker/licenses-catalog/data"))
  inputs.file(layout.projectDirectory.file("dev/docker/licenses-catalog/apply.py"))
}

tasks.register<Exec>("testImageLicenseCatalog") {
  group = "verification"
  description = "Checks generated image/binary third-party catalogs against the CSV."
  workingDir = rootDir
  dependsOn("applyImageLicenseCatalog")
  commandLine("bash", "dev/docker/licenses-catalog/test/run.sh")
}

project.afterEvaluate {
  tasks.named("compileDistribution").configure { dependsOn("applyImageLicenseCatalog") }
}

tasks.named("check").get().dependsOn(
  tasks.named("checkEnterpriseFilesHaveEnterpriseOnlyHeader"),
  tasks.named("checkAddedEnterpriseFilesHaveEnterpriseOnlyHeader"),
  tasks.named("checkNonEnterpriseFilesHaveAsfHeader"),
  tasks.named("testEnterpriseLicenseHeaders"),
  tasks.named("testImageLicenseCatalog"),
  tasks.named("checkConnectorJars")
)
