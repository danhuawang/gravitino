/*
 * Copyright 2023 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
import org.gradle.internal.os.OperatingSystem
import java.io.IOException
import java.util.Locale

plugins {
  `maven-publish`
  id("java")
  id("idea")
  id("jacoco")

  alias(libs.plugins.gradle.extensions)
  alias(libs.plugins.rat)
  alias(libs.plugins.tasktree)

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

  val setTestEnvironment: (Test) -> Unit = { param ->
    param.doFirst {
      // Default use MiniGravitino to run integration tests
      param.environment("GRAVITINO_ROOT_DIR", project.rootDir.path)
      param.environment("IT_PROJECT_DIR", project.buildDir.path)
      // If the environment variable `HADOOP_USER_NAME` is not customized in submodule,
      // then set it to "anonymous"
      if (param.environment["HADOOP_USER_NAME"] == null) {
        param.environment("HADOOP_USER_NAME", "anonymous")
      }
      param.environment("HADOOP_HOME", "/tmp")
      param.environment("PROJECT_VERSION", project.version)

      // Gravitino CI Docker image
      param.environment("GRAVITINO_CI_HIVE_DOCKER_IMAGE", "apache/gravitino-ci:hive-0.1.17")
      param.environment("GRAVITINO_CI_KERBEROS_HIVE_DOCKER_IMAGE", "apache/gravitino-ci:kerberos-hive-0.1.5")
      param.environment("GRAVITINO_CI_DORIS_DOCKER_IMAGE", "apache/gravitino-ci:doris-0.1.5")
      param.environment("GRAVITINO_CI_TRINO_DOCKER_IMAGE", "apache/gravitino-ci:trino-0.1.6")
      param.environment("GRAVITINO_CI_RANGER_DOCKER_IMAGE", "apache/gravitino-ci:ranger-0.1.1")
      param.environment("GRAVITINO_CI_KAFKA_DOCKER_IMAGE", "apache/kafka:3.7.0")
      param.environment("GRAVITINO_CI_LOCALSTACK_DOCKER_IMAGE", "localstack/localstack:latest")

      val dockerRunning = project.rootProject.extra["dockerRunning"] as? Boolean ?: false
      val macDockerConnector = project.rootProject.extra["macDockerConnector"] as? Boolean ?: false
      if (OperatingSystem.current().isMacOsX() &&
        dockerRunning &&
        macDockerConnector
      ) {
        param.environment("NEED_CREATE_DOCKER_NETWORK", "true")
      }

      // Change poll image pause time from 30s to 60s
      param.environment("TESTCONTAINERS_PULL_PAUSE_TIMEOUT", "60")
      val jdbcDatabase = project.properties["jdbcBackend"] as? String ?: "h2"
      param.environment("jdbcBackend", jdbcDatabase)

      val testMode = project.properties["testMode"] as? String ?: "embedded"
      param.systemProperty("gravitino.log.path", "build/${project.name}-integration-test.log")
      project.delete("build/${project.name}-integration-test.log")
      if (testMode == "deploy") {
        param.environment("GRAVITINO_HOME", project.rootDir.path + "/distribution/package")
        param.systemProperty("testMode", "deploy")
      } else if (testMode == "embedded") {
        param.environment("GRAVITINO_HOME", project.rootDir.path)
        param.environment("GRAVITINO_TEST", "true")
        param.environment("GRAVITINO_WAR", project.rootDir.path + "/web/web/dist/")
        param.systemProperty("testMode", "embedded")
      } else {
        throw GradleException("Gravitino integration tests only support [-PtestMode=embedded] or [-PtestMode=deploy] mode!")
      }

      param.useJUnitPlatform()
      val skipUTs = project.hasProperty("skipTests")
      if (skipUTs) {
        // Only run integration tests
        param.include("**/integration/test/**")
      }

      param.useJUnitPlatform {
        val dockerTest = project.rootProject.extra["dockerTest"] as? Boolean ?: false
        if (!dockerTest) {
          excludeTags("gravitino-docker-test")
        }
      }
    }
  }

  extra["initTestParam"] = setTestEnvironment
}

subprojects {
  apply(plugin = "jacoco")
  apply(plugin = "java")

  group = "com.datastrato.enterprise.gravitino"
  version = "$version"

  tasks.test {
    useJUnitPlatform()
  }

  tasks.withType<Test> {
    val initTest = project.extra.get("initTestParam") as (Test) -> Unit
    initTest(this)
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

  val compileOssDistributionWithoutTest by registering(Exec::class) {
    group = "datastrato gravitino distribution"

    dependsOn(subprojects.map { ":${it.name}:build" })
    workingDir = submoduleDir.asFile
    commandLine("./gradlew", "compileDistribution", "-x", "test")
  }

  val copySubprojectDependencies by registering(Copy::class) {
    group = "datastrato gravitino distribution"
    // dependsOn("copyOssDistribution")
    subprojects.forEach {
      println("Copying dependencies from ${it.name}")
      if (it.name != "docs" && it.name != "authorization-jdbc-enterprise") {
        from(it.configurations.runtimeClasspath)
        into("distribution/package/libs")
      }
    }
  }

  val copySubprojectLib by registering(Copy::class) {
    group = "datastrato gravitino distribution"
    // dependsOn("copyOssDistribution")
    subprojects.forEach {
      if (it.name != "docs" && it.name != "authorization-jdbc-enterprise") {
        // dependsOn("${it.name}:build")
        from("${it.name}/build/libs")
        into("distribution/package/libs")
        include("*.jar")
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
      }
    }
  }

  val copyOssDistribution by registering(Copy::class) {
    group = "datastrato gravitino distribution"
    // Use OSS packages as the base directory
    dependsOn(compileOssDistributionWithoutTest)
    from(submoduleDir.dir("distribution"))
    into(outputDir)

    finalizedBy(copySubprojectDependencies, copySubprojectLib, ":authorization-jdbc-enterprise:copyLibAndConfig")
  }

  val compileDistribution by registering {
    group = "datastrato gravitino distribution"
    outputs.dir(projectDir.dir("distribution/package"))
    dependsOn(copyOssDistribution)

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
          "gravitino.server.rest.extensionPackages = com.datastrato.gravitino.server.web.rest,com.datastrato.gravitino.search.rest\n"
        confFile.appendText(newLine)

        val previewLine = "\n\n# Trino preview configuration\n" +
          "# Default value of `gravitino.datastrato.preview.jdbcUrl` is null\n" +
          "gravitino.datastrato.preview.jdbcUrl=jdbc:trino://trino:8080\n" +
          "gravitino.datastrato.preview.jdbcDriver=io.trino.jdbc.TrinoDriver\n" +
          "gravitino.datastrato.preview.jdbcUsername=admin\n" +
          "gravitino.datastrato.preview.timeoutInSec=300\n" +
          "gravitino.datastrato.preview.maxRowCount=100\n" +
          "# Default value of `gravitino.datastrato.preview.jdbcPassword` is null.\n" +
          "# Default value of `gravitino.datastrato.preview.sensitiveTags` is empty collection.\n"
        confFile.appendText(previewLine)

        val lineageLine = "\n\n# Lineage sink configuration\n" +
          "# gravitino.lineage.sinks = log,marquez\n" +
          "# gravitino.lineage.marquez.sinkClass = com.datastrato.gravitino.lineage.sink.HTTPLineageSink\n" +
          "# gravitino.lineage.marquez.url = http://localhost:6000"
        confFile.appendText(lineageLine)

        // Add the following line to the end of the file
        val defaultSearchStorage = "\n\n# Default search storage, the default value of this configuration: opensearch\n" +
          "# gravitino.datastrato.search.storage.impl = opensearch"
        val openSearchUrl = "\n# OpenSearch URL\n" +
          "# gravitino.datastrato.search.opensearch.url = https://localhost:9200"
        val openSearchUsername = "\n# OpenSearch username\n" +
          "# gravitino.datastrato.search.opensearch.username = admin"
        val openSearchPassword = "\n# OpenSearch password\n" +
          "# gravitino.datastrato.search.opensearch.password = ----\n"
        confFile.appendText(defaultSearchStorage)
        confFile.appendText(openSearchUrl)
        confFile.appendText(openSearchUsername)
        confFile.appendText(openSearchPassword)

        val searchListenerLine = "\n# Search listener configuration\n" +
          "gravitino.eventListener.names = search\n" +
          "gravitino.eventListener.search.class = com.datastrato.gravitino.search.listener.DataDiscoveryListener\n"
        confFile.appendText(searchListenerLine)
      }
      // Modify log4j2.properties
      val log4jFile = file(outputDir.dir("package/conf/log4j2.properties"))
      if (log4jFile.exists()) {
        val extraContent = """
            ## use separate file for search log
            appender.search_file.type=RollingFile
            appender.search_file.name=search_file
            appender.search_file.fileName=${'$'}{basePath}/gravitino_search.log
            appender.search_file.filePattern=${'$'}{basePath}/gravitino_search_%d{yyyyMMdd}.log.gz
            appender.search_file.layout.type=PatternLayout
            appender.search_file.layout.pattern=[%d{yyyy-MM-dd HH:mm:ss}] %m%n
            appender.search_file.policies.type=Policies

            appender.search_file.policies.time.type=TimeBasedTriggeringPolicy
            appender.search_file.policies.time.interval=1
            appender.search_file.policies.time.modulate=true
            appender.search_file.strategy.type=DefaultRolloverStrategy
            appender.search_file.strategy.delete.type=Delete
            appender.search_file.strategy.delete.basePath=${'$'}{basePath}
            appender.search_file.strategy.delete.maxDepth=10
            appender.search_file.strategy.delete.ifLastModified.type=IfLastModified
            appender.search_file.strategy.delete.ifLastModified.age=30d

            ## logger for com.datastrato.gravitino.search.*
            logger.search.name=com.datastrato.gravitino.search
            logger.search.level=info
            logger.search.appenderRef.search_file.ref=search_file
            logger.search.additivity=false
        """.trimIndent()
        log4jFile.appendText(extraContent)
      }
    }
  }

  val assembleDistribution by registering(Tar::class) {
    mustRunAfter(copySubprojectDependencies, copySubprojectLib)
    into("${rootProject.name}-$version-bin")
    from(compileDistribution.map { it.outputs.files.single() })
    compression = Compression.GZIP
    archiveFileName.set("${rootProject.name}-$version-bin.tar.gz")
    destinationDirectory.set(projectDir.dir("distribution"))
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

project.extra["dockerTest"] = false
project.extra["dockerRunning"] = false
project.extra["macDockerConnector"] = false
project.extra["isOrbStack"] = false

// The following is to check the docker status and print the tip message
fun printDockerCheckInfo() {
  checkMacDockerConnector()
  checkDockerStatus()
  checkOrbStackStatus()

  val testMode = project.properties["testMode"] as? String ?: "embedded"
  if (testMode != "deploy" && testMode != "embedded") {
    return
  }
  val dockerRunning = project.extra["dockerRunning"] as? Boolean ?: false
  val macDockerConnector = project.extra["macDockerConnector"] as? Boolean ?: false
  val isOrbStack = project.extra["isOrbStack"] as? Boolean ?: false
  val skipDockerTests = if (extra["skipDockerTests"].toString().toBoolean()) {
    // Read the environment variable (SKIP_DOCKER_TESTS) when skipDockerTests is true
    // which means users can enable the docker tests by setting the gradle properties or the environment variable.
    System.getenv("SKIP_DOCKER_TESTS")?.toBoolean() ?: true
  } else {
    false
  }

  if (skipDockerTests) {
    project.extra["dockerTest"] = false
  } else if (OperatingSystem.current().isMacOsX() &&
    dockerRunning &&
    (macDockerConnector || isOrbStack)
  ) {
    project.extra["dockerTest"] = true
  } else if (OperatingSystem.current().isLinux() && dockerRunning) {
    project.extra["dockerTest"] = true
  }

  println("------------------ Check Docker environment [enterprise] ---------------------")
  println("Docker server status ............................................ [${if (dockerRunning) "running" else "\u001B[31mstop\u001B[0m"}]")
  if (OperatingSystem.current().isMacOsX()) {
    println("mac-docker-connector status ..................................... [${if (macDockerConnector) "running" else "\u001B[31mstop\u001B[0m"}]")
    println("OrbStack status ................................................. [${if (dockerRunning && isOrbStack) "yes" else "\u001B[31mno\u001B[0m"}]")
  }

  val dockerTest = project.extra["dockerTest"] as? Boolean ?: false
  if (dockerTest) {
    println("Using Docker container to run all tests ......................... [$testMode test]")
  } else {
    println("Run test cases without `gravitino-docker-test` tag .............. [$testMode test]")
  }
  println("-----------------------------------------------------------------")

  // Print help message if Docker server or mac-docker-connector is not running
  printDockerServerTip()
  printMacDockerTip()
}

fun printDockerServerTip() {
  val dockerRunning = project.extra["dockerRunning"] as? Boolean ?: false
  if (!dockerRunning) {
    val redColor = "\u001B[31m"
    val resetColor = "\u001B[0m"
    println("Tip: Please make sure to start the ${redColor}Docker server$resetColor before running the integration tests.")
  }
}

fun printMacDockerTip() {
  val macDockerConnector = project.extra["macDockerConnector"] as? Boolean ?: false
  val isOrbStack = project.extra["isOrbStack"] as? Boolean ?: false
  if (OperatingSystem.current().isMacOsX() && !macDockerConnector && !isOrbStack) {
    val redColor = "\u001B[31m"
    val resetColor = "\u001B[0m"
    println(
      "Tip: Please make sure to use ${redColor}OrbStack$resetColor or execute the " +
        "$redColor`dev/docker/tools/mac-docker-connector.sh`$resetColor script before running" +
        " the integration test on macOS."
    )
  }
}

fun checkMacDockerConnector() {
  if (!OperatingSystem.current().isMacOsX()) {
    // Only MacOs requires the use of `docker-connector`
    return
  }

  try {
    val processName = "docker-connector"
    val command = "pgrep -x -q $processName"

    val execResult = project.exec {
      commandLine("bash", "-c", command)
    }
    if (execResult.exitValue == 0) {
      project.extra["macDockerConnector"] = true
    }
  } catch (e: Exception) {
    println("checkContainerRunning command execution failed: ${e.message}")
  }
}

fun checkDockerStatus() {
  try {
    val process = ProcessBuilder("docker", "info").start()
    val exitCode = process.waitFor()

    if (exitCode == 0) {
      project.extra["dockerRunning"] = true
    } else {
      println("checkDockerStatus command execution failed with exit code $exitCode")
    }
  } catch (e: IOException) {
    println("checkDockerStatus command execution failed: ${e.message}")
  }
}

fun checkOrbStackStatus() {
  if (!OperatingSystem.current().isMacOsX()) {
    return
  }

  try {
    val process = ProcessBuilder("docker", "context", "show").start()
    val exitCode = process.waitFor()
    if (exitCode == 0) {
      val currentContext = process.inputStream.bufferedReader().readText()
      println("Current docker context is: $currentContext")
      project.extra["isOrbStack"] = currentContext.lowercase(Locale.getDefault()).contains("orbstack")
    } else {
      println("checkOrbStackStatus Command execution failed with exit code $exitCode")
    }
  } catch (e: IOException) {
    println("checkOrbStackStatus command execution failed: ${e.message}")
  }
}

printDockerCheckInfo()
