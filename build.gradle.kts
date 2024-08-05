/*
 * Copyright 2023 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.os.OperatingSystem
import org.gradle.kotlin.dsl.support.serviceOf

plugins {
  `maven-publish`
  id("java")
  id("idea")
  id("jacoco")

  alias(libs.plugins.gradle.extensions)
  alias(libs.plugins.rat)

  // Spotless version < 6.19.0 (https://github.com/diffplug/spotless/issues/1819) has an issue
  // running against JDK21, but we cannot upgrade the spotless to 6.19.0 or later since it only
  // support JDK11+. So we don't support JDK21 and thrown an exception for now.
  if (JavaVersion.current() >= JavaVersion.VERSION_1_8 &&
    JavaVersion.current() <= JavaVersion.VERSION_17
  ) {
    alias(libs.plugins.spotless)
  } else {
    throw GradleException(
      "Gravitino Gradle toolchain current doesn't support " +
        "Java version: ${JavaVersion.current()}. Please use JDK8 to 17."
    )
  }
}

repositories { mavenCentral() }

allprojects {
  apply(plugin = "com.diffplug.spotless")
  repositories {
    mavenCentral()
    mavenLocal()
  }

  plugins.withType<com.diffplug.gradle.spotless.SpotlessPlugin>().configureEach {
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
      java {
        // Fix the Google Java Format version to 1.7. Since JDK8 only support Google Java Format
        // 1.7, which is not compatible with JDK17. We will use a newer version when we upgrade to
        // JDK17.
        googleJavaFormat("1.7")
        removeUnusedImports()
        trimTrailingWhitespace()
        replaceRegex(
          "Remove wildcard imports",
          "import\\s+[^\\*\\s]+\\*;(\\r\\n|\\r|\\n)",
          "$1"
        )
        replaceRegex(
          "Remove static wildcard imports",
          "import\\s+(?:static\\s+)?[^*\\s]+\\*;(\\r\\n|\\r|\\n)",
          "$1"
        )

        targetExclude("**/build/**")
      }

      kotlinGradle {
        target("*.gradle.kts")
        ktlint().editorConfigOverride(mapOf("indent_size" to 2, "continuation_indent_size" to "2"))
      }
    }
  }
}

subprojects {
  apply(plugin = "jacoco")
  apply(plugin = "java")

  group = "com.datastrato.enterprise.gravitino"
  version = "$version"

  java {
    toolchain {
      // Some JDK vendors like Homebrew installed OpenJDK 17 have problems in building trino-connector:
      // It will cause tests of Trino-connector hanging forever on macOS, to avoid this issue and
      // other vendor-related problems, Gravitino will use the specified AMAZON OpenJDK 17 to build
      // Trino-connector on macOS.
      if (project.name == "trino-connector-extension") {
        if (OperatingSystem.current().isMacOsX) {
          vendor.set(JvmVendorSpec.AMAZON)
        }
        languageVersion.set(JavaLanguageVersion.of(17))
      } else {
        languageVersion.set(JavaLanguageVersion.of(8))
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
      }
    }
  }

  tasks.test {
    useJUnitPlatform()
  }
}

tasks.rat {
  substringMatcher("DS", "Datastrato", "Copyright 2023 Datastrato Pvt Ltd.")
  substringMatcher("DS", "Datastrato", "Copyright 2024 Datastrato Pvt Ltd.")
  approvedLicense("Datastrato")
  approvedLicense("Apache License Version 2.0")

  // Set input directory to that of the root project instead of the CWD. This
  // makes .gitignore rules (added below) work properly.
  inputDir.set(project.rootDir)

  val exclusions = mutableListOf(
    // Ignore files we track but do not need headers
    "**/.github/**/*",
    "gravitino-oss/dev/docker/**/*.xml",
    "gravitino-oss/dev/docker/**/*.conf",
    "gravitino-oss/dev/docker/kerberos-hive/kadm5.acl",
    "**/*.log",
    "**/licenses/*.txt",
    "**/licenses/*.md",
    "gravitino-oss/integration-test/**/*.sql",
    "gravitino-oss/integration-test/**/*.txt",
    "gravitino-oss/docs/**/*.md",
    "gravitino-oss/integration-test/**",
    "gravitino-oss/web/.**",
    "gravitino-oss/web/next-env.d.ts",
    "gravitino-oss/web/dist/**/*",
    "gravitino-oss/web/node_modules/**/*",
    "gravitino-oss/web/src/lib/utils/axios/**/*",
    "gravitino-oss/web/src/lib/enums/httpEnum.js",
    "gravitino-oss/web/src/types/axios.d.ts",
    "gravitino-oss/web/yarn.lock",
    "gravitino-oss/web/package-lock.json",
    "gravitino-oss/web/pnpm-lock.yaml",
    "gravitino-oss/web/src/lib/icons/svg/**/*.svg",
    "gravitino-oss/DISCLAIMER_WIP.txt",
    "gravitino-oss/DISCLAIMER.txt",
    "**/LICENSE.*",
    "**/NOTICE.*",
    "**/testsets/*",
    "gravitino-oss/ROADMAP.md",
    "gravitino-oss/clients/client-python/.pytest_cache/*",
    "gravitino-oss/clients/client-python/gravitino.egg-info/*",
    "gravitino-oss/clients/client-python/gravitino/utils/exceptions.py",
    "gravitino-oss/clients/client-python/gravitino/utils/http_client.py"
  )

  // Add .gitignore excludes to the Apache Rat exclusion list.
  val gitIgnore = project(":").file(".gitignore")
  if (gitIgnore.exists()) {
    val gitIgnoreExcludes = gitIgnore.readLines().filter {
      it.isNotEmpty() && !it.startsWith("#")
    }
    exclusions.addAll(gitIgnoreExcludes)
  }

  verbose.set(true)
  failOnError.set(true)
  setExcludes(exclusions)
}
tasks.check.get().dependsOn(tasks.rat)

jacoco {
  toolVersion = "0.8.10"
  reportsDirectory.set(layout.buildDirectory.dir("JacocoReport"))
}

tasks {
  val projectDir = layout.projectDirectory
  val outputDir = projectDir.dir("distribution")

  val assembleDistribution by registering(Tar::class) {
    dependsOn("assembleTrinoConnector")
    dependsOn("copyTrino", "build")
    group = "gravitino distribution"
  }

  val copyTrino by registering(Copy::class) {
    from("gravitino-oss/trino-connector/build/libs", "trino-connector-extension/build/libs")
    destinationDir = file("$projectDir/distribution/${rootProject.name}-trino-connector")
  }

  val assembleTrinoConnector by registering(Tar::class) {
    dependsOn(copyTrino)
    group = "gravitino distribution"
    finalizedBy("checksumTrinoConnector")
    into("${rootProject.name}-trino-connector-$version") {
      from("gravitino-oss/trino-connector/build/libs")
      from("trino-connector-extension/build/libs")
    }
    compression = Compression.GZIP
    archiveFileName.set("${rootProject.name}-trino-connector-$version.tar.gz")
    destinationDirectory.set(projectDir.dir("distribution"))
  }

  register("checksumTrinoConnector") {
    group = "gravitino distribution"
    dependsOn(assembleTrinoConnector)
    val archiveFile = assembleTrinoConnector.flatMap { it.archiveFile }
    val checksumFile = archiveFile.map { archive ->
      archive.asFile.let { it.resolveSibling("${it.name}.sha256") }
    }
    inputs.file(archiveFile)
    outputs.file(checksumFile)
    doLast {
      checksumFile.get().writeText(
        serviceOf<ChecksumService>().sha256(archiveFile.get().asFile).toString()
      )
    }
  }

  clean {
    group = "gravitino distribution"
    delete(outputDir)
  }
}
