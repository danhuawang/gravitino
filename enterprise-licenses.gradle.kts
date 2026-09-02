/*
 * Copyright 2026 Datastrato Pvt Ltd.
 *
 * Enterprise license policy and checks, extracted from the upstream-owned
 * build.gradle.kts so upstream syncs do not touch Datastrato license logic.
 *
 * Applied from build.gradle.kts via:
 *   apply(from = "enterprise-licenses.gradle.kts")
 *
 * Policy (three cases):
 *   1. Upstream (Apache) source keeps the ASF Apache 2.0 header (approved by `rat`).
 *   2. New Datastrato code uses the copyright-only header below, with no
 *      Apache license sentence.
 *   3. Old legacy Datastrato code (copyright + Apache sentence) is left
 *      untouched for a later cleanup.
 *
 * This file owns the Datastrato header pattern, the enterprise module list,
 * the dynamic detection of Datastrato headers outside those modules, and the
 * two enterprise checkers. It exposes the combined Apache-Rat exclusion
 * list via `extra["enterpriseRatExcludes"]` so build.gradle.kts can fold it
 * into the upstream `rat` task additively (the `RatTask` type is only importable
 * there). This file never touches the `rat` task directly. `printRatFailures`
 * stays in build.gradle.kts because it needs the `RatTask` type.
 */

import org.apache.gravitino.license.LicenseHeaderClassifier
import org.apache.gravitino.license.NewFileFinder

val datastratoLicenseCheckIncludes = listOf(
  "authorization-jdbc-enterprise/**",
  "common-extension/**",
  "core-extension/**",
  "datastrato-server/**",
  "docs-enterprise/**",
  "ENTERPRISE_DEVELOPMENT.md",
  "lineage-extension/**",
  "metrics/**",
  "qa/**",
  "search/**",
  "test/search-integration-test/**",
  "test/test-common/**",
  "bin/index.sh.template",
  "bin/gravitino-metrics-service.sh.template",
  "bin/opensearch/**",
  "conf/gravitino-metrics-server.conf.template",
  "catalogs/catalog-jdbc-oracle/**/*",
  "integration-test-common/src/test/java/org/apache/gravitino/integration/test/container/OracleContainer.java",
  "spark-connector/**/jdbc/oracle/*TypeConverter*",
  "spark-connector/**/jdbc/sqlserver/*TypeConverter*",
  "catalogs/catalog-jdbc-bigquery/**/*",
  "catalogs/catalog-jdbc-maxcompute/**/*",
  "catalogs/catalog-jdbc-sqlserver/**/*",
  "bundles/vault-compatible/transit/**",
  "bundles/vault-compatible/secret/**",
  "bundles/vault/**",
  "bundles/openbao/**",
  "src/test/java/com/datastrato/gravitino/transit/packaging/TransitProviderDiscoveryProbe.java",
  "licensing/**",
  "scripts/enterprise/**",
  "plugins/scim/**",
  "core/src/main/java/org/apache/gravitino/listener/api/event/scim/**",
  "core/src/test/java/org/apache/gravitino/listener/api/event/scim/**"
)

val datastratoHeaderExtensions = setOf(
  "java",
  "scala",
  "kt",
  "kts",
  "py",
  "sh",
  "template",
  "conf"
)

// Dynamically detect source files carrying the Datastrato proprietary header that live
// outside the paths already covered by datastratoLicenseCheckIncludes. These are excluded
// from Apache Rat (which only approves Apache License 2.0) so enterprise files can carry
// the copyright-only header in any module (e.g. api/) without being flagged as unapproved
// licenses. The header format itself is verified by checkDatastratoLicenseHeaders.
//
// Lazy: the fileTree scan only runs when the provider is resolved (i.e. when a
// license check task executes), not on every Gradle configuration. This keeps

// cold operations like `gradle help`/`tasks`/`compileJava` from scanning the whole repo.
val extraDatastratoHeaderFiles = fileTree(rootDir) {
  datastratoHeaderExtensions.forEach { include("**/*.$it") }
  // Dockerfiles may carry a suffix (e.g. Dockerfile.iceberg-rest); match the base
  // name and any suffixed variants. Content filtering keeps only Datastrato headers.
  include("**/Dockerfile")
  include("**/Dockerfile.*")
  include("**/Jenkinsfile")
  exclude(
    "**/build/**",
    "**/.gradle/**",
    "**/.idea/**",
    "**/node_modules/**",
    "**/dist/**",
    "**/.node/**",
    "**/.git/**",
    "**/.venv/**",
    // Cloud / local Python ITs create `venv/` (not `.venv/`); scanning those .py files as
    // UTF-8 blows up configuration (MalformedInputException) on later ./gradlew invocations.
    "**/venv/**",
    "**/__pycache__/**",
    "**/.pytest_cache/**"
  )
  // Avoid double-scanning files already covered by the enterprise module globs.
  datastratoLicenseCheckIncludes.forEach { exclude(it) }
}.files.filter { file ->
  LicenseHeaderClassifier.hasDatastratoCopyright(LicenseHeaderClassifier.readHeader(file.toPath()))
}.map { it.relativeTo(rootDir).path.replace(File.separatorChar, '/') }

// Mixed-distribution root files are not Apache-headered source. LICENSE is the
// short mixed statement; LICENSE-ENTERPRISE is the proprietary placeholder;
// LICENSE-APACHE holds the Apache 2.0 text that used to live in LICENSE.
val mixedDistributionLicenseFiles =
  listOf(
    "LICENSE",
    "LICENSE-APACHE",
    "LICENSE-ENTERPRISE"
  )

// Combined Apache-Rat exclusion list for enterprise files. Consumed by the
// `rat` task in build.gradle.kts (the RatTask type is only importable there)
// via `project.extra["enterpriseRatExcludes"]`, so this file does not touch `rat`.
// `project.extra` (not the script-local `extra`) is used so the value is visible
// to build.gradle.kts, which shares the same project extra properties.
project.extra["enterpriseRatExcludes"] =
  datastratoLicenseCheckIncludes + extraDatastratoHeaderFiles + mixedDistributionLicenseFiles
tasks.register("checkDatastratoLicenseHeaders") {
  group = "verification"
  description = "Checks Datastrato header format for enterprise-owned files."

  val supportedExtensions = setOf(
    "java",
    "scala",
    "kt",
    "kts",
    "py",
    "sh",
    "template",
    "conf"
  )

  doLast {
    val filesToCheck = fileTree(rootDir) {
      datastratoLicenseCheckIncludes.forEach { include(it) }
      exclude(
        "**/build/**",
        "**/.gradle/**",
        "**/.idea/**",
        "**/node_modules/**",
        "**/dist/**",
        "**/.node/**"
      )
    }.files.filter { file ->
      file.isFile &&
        (
          supportedExtensions.contains(file.extension) ||
            file.name == "Dockerfile" ||
            file.name.startsWith("Dockerfile.") ||
            file.name == "Jenkinsfile"
          )
    }

    val violations = mutableListOf<String>()
    filesToCheck.forEach { file ->
      val hasCopyright =
        LicenseHeaderClassifier.hasDatastratoCopyright(LicenseHeaderClassifier.readHeader(file.toPath()))
      // Per the repo licensing policy, enterprise files carry the Datastrato
      // proprietary header (copyright only); the Apache license sentence is no
      // longer required and is omitted from new enterprise files.
      if (!hasCopyright) {
        violations.add(file.relativeTo(rootDir).path)
      }
    }

    if (violations.isNotEmpty()) {
      throw GradleException(
        "Datastrato license header check failed for ${violations.size} file(s):\n" +
          violations.sorted().joinToString("\n")
      )
    }
  }
}
tasks.register("checkNewFileLicenseHeaders") {
  group = "verification"
  description =
    "Ensures newly-added Datastrato files use the copyright-only header (no Apache license sentence)."

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
    logger.lifecycle("checkNewFileLicenseHeaders: using base ref '$baseRef'")

    // Resolve added files vs the base ref. Fail (don't skip) if the base ref is
    // unavailable so the new-file enforcement cannot be bypassed by a shallow
    // or fresh checkout; CI must fetch the base ref or pass -PlicenseBaseRef.
    val addedFiles =
      NewFileFinder.addedFiles(rootDir, baseRef)
        ?: throw GradleException(
          "checkNewFileLicenseHeaders could not resolve base ref '$baseRef'. " +
            "Fetch the base ref (e.g. origin/main or origin/branch-1.3) or set -PlicenseBaseRef."
        )

    val violations = mutableListOf<String>()
    addedFiles.forEach { relPath ->
      val file = rootDir.resolve(relPath)
      if (!file.isFile) return@forEach
      val isSupported = LicenseHeaderClassifier.isSupportedFile(file.name)
      if (!isSupported) return@forEach
      if (LicenseHeaderClassifier.isViolation(file.toPath())) {
        violations.add(relPath)
      }
    }

    if (violations.isNotEmpty()) {
      throw GradleException(
        "New-file license check failed for ${violations.size} file(s). " +
          "New Datastrato files must use the copyright-only header " +
          "(remove the 'This software is licensed under the Apache License version 2.' line):\n" +
          violations.sorted().joinToString("\n")
      )
    }
  }
}

// Wire the enterprise checkers into the `check` lifecycle task. The upstream
// `tasks.check.get().dependsOn(tasks.rat)` remains in build.gradle.kts.
tasks.named("check").get().dependsOn(
  tasks.named("checkDatastratoLicenseHeaders"),
  tasks.named("checkNewFileLicenseHeaders")
)
