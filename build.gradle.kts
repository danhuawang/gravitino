/*
 * Copyright 2023 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

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
    // Ignore gitSubmodule files which should be dealt by itself
    "gravitino-oss/**",

    // Ignore files we track but do not need headers
    "**/.github/**/*",
    "gravitino-oss/dev/docker/kerberos-hive/kadm5.acl",
    "**/*.log",
    "**/*.out",
    "**/node_modules/**",
    "**/.node/**",
    "**/.npm/**",
    "**/licenses/*.txt",
    "**/licenses/*.md",
    "**/LICENSE.*"
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
  val submoduleDir = projectDir.dir("gravitino-oss")

  val compileDistribution by registering {
    group = "datastrato gravitino distribution"
    outputs.dir(projectDir.dir("distribution/package"))

    dependsOn("copyOssDistribution", "copySubprojectDependencies", "copySubprojectLib")

    doLast {
      copy {
        from(submoduleDir.dir("conf")) { into("package/conf") }
        from(submoduleDir.dir("bin")) { into("package/bin") }
        into(outputDir)
        rename { fileName ->
          fileName.replace(".template", "")
        }
        fileMode = 0b111101101
      }

      // Modify gravitino.sh
      val shellFile = file(outputDir.dir("package/bin/gravitino.sh"))
      if (shellFile.exists()) {
        val content = shellFile.readText()
        val updatedContent = content.replace(
          "GRAVITINO_SERVER_NAME=org.apache.gravitino.server.GravitinoServer",
          "GRAVITINO_SERVER_NAME=org.apache.gravitino.server.DatastratoGravitinoServer"
        )
        shellFile.writeText(updatedContent)
      }

      // Modify gravitino.conf
      val confFile = file(outputDir.dir("package/conf/gravitino.conf"))
      if (confFile.exists()) {
        val newLine = "\n# Comma separated list of REST API packages to expand\n" +
          "gravitino.server.rest.extensionPackages = com.datastrato.gravitino.server.web.rest\n"
        confFile.appendText(newLine)
      }
    }
  }

  val assembleDistribution by registering(Tar::class) {
    into("${rootProject.name}-$version-bin")
    from(compileDistribution.map { it.outputs.files.single() })
    compression = Compression.GZIP
    archiveFileName.set("${rootProject.name}-$version-bin.tar.gz")
    destinationDirectory.set(projectDir.dir("distribution"))
  }

  register("copyOssDistribution", Copy::class) {
    group = "datastrato gravitino distribution"
    // Use OSS packages as the base directory
    dependsOn(gradle.includedBuild("gravitino-oss").task(":compileDistribution"))
    from(submoduleDir.dir("distribution"))
    into(outputDir)
  }

  register("copySubprojectDependencies", Copy::class) {
    group = "datastrato gravitino distribution"
    dependsOn("copyOssDistribution")
    subprojects.forEach {
      if (it.name != "docs") {
        from(it.configurations.runtimeClasspath)
        into("distribution/package/libs")
      }
    }
  }

  register("copySubprojectLib", Copy::class) {
    group = "datastrato gravitino distribution"
    dependsOn("copyOssDistribution")
    subprojects.forEach {
      if (it.name != "docs") {
        dependsOn("${it.name}:build")
        from("${it.name}/build/libs")
        into("distribution/package/libs")
        include("*.jar")
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
      }
    }
  }

  clean {
    doLast {
      // Clean up all subprojects of submodule
      exec {
        commandLine("./gradlew", "-p", "gravitino-oss", "clean")
      }

      // Clean up the distribution directory
      project.delete(outputDir)
    }
  }
}
