/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

plugins {
  `maven-publish`
  id("java")
  alias(libs.plugins.shadow)
}

val scalaVersion: String = project.properties["scalaVersion"] as? String ?: extra["defaultScalaVersion"].toString()
val sparkVersion: String = libs.versions.spark35.get()

dependencies {
  implementation(project(":clients:client-java"))
  implementation(project(":common"))
  implementation(project(":api"))
  implementation(project(":core"))
  implementation(project(":integration-test-common"))

  implementation(libs.guava)
  implementation(libs.slf4j.api)
  implementation(libs.commons.lang3)

  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testImplementation(libs.testcontainers)
  testImplementation(libs.testcontainers.mysql)
  testImplementation(libs.testcontainers.postgresql)

  // Spark dependencies for Spark function privilege tests
  // Exclude log4j from all Gravitino dependencies to avoid conflicts with Spark's log4j version
  testImplementation(project(":spark-connector:spark-common")) {
    exclude("org.apache.logging.log4j")
  }
  testImplementation(project(":spark-connector:spark-runtime-3.5")) {
    exclude("org.apache.logging.log4j")
  }
  testImplementation(project(":clients:client-java")) {
    exclude("org.apache.logging.log4j")
  }
  testImplementation(project(":core")) {
    exclude("org.apache.logging.log4j")
  }
  testImplementation(project(":common")) {
    exclude("org.apache.logging.log4j")
  }
  testImplementation(project(":api")) {
    exclude("org.apache.logging.log4j")
  }
  testImplementation(project(":integration-test-common")) {
    exclude("org.apache.logging.log4j")
  }
  testImplementation("org.apache.spark:spark-sql_$scalaVersion:$sparkVersion") {
    exclude("org.glassfish.jersey.core")
    exclude("org.glassfish.jersey.containers")
    exclude("org.glassfish.jersey.inject")
  }
  testImplementation("org.apache.spark:spark-catalyst_$scalaVersion:$sparkVersion")
  testImplementation("org.apache.spark:spark-core_$scalaVersion:$sparkVersion")

  // spark-hive provides org.apache.spark.sql.hive.HiveSessionStateBuilder which
  // SparkSession.Builder.enableHiveSupport() reflects on. Without this artifact
  // the Spark privilege tests fail with "Hive classes are not found".
  // Jersey is excluded because it conflicts with the Gravitino server's jersey
  // already on this module's classpath.
  testImplementation("org.apache.spark:spark-hive_$scalaVersion:$sparkVersion") {
    exclude("org.glassfish.jersey.core")
    exclude("org.glassfish.jersey.containers")
    exclude("org.glassfish.jersey.inject")
  }

  // Add Spark runtime dependencies needed for Hive catalog support
  testImplementation("org.apache.kyuubi:kyuubi-spark-connector-hive_$scalaVersion:${libs.versions.kyuubi4spark.get()}")
  testImplementation(project(":clients:client-java-runtime", configuration = "shadow"))
}

// Force Spark's log4j version for all test configurations to avoid conflicts
configurations.testRuntimeClasspath {
  resolutionStrategy {
    force("org.apache.logging.log4j:log4j-api:2.17.2")
    force("org.apache.logging.log4j:log4j-core:2.17.2")
    force("org.apache.logging.log4j:log4j-slf4j-impl:2.17.2")
    force("org.apache.logging.log4j:log4j-1.2-api:2.17.2")
  }
  // Exclude the new log4j2 binding that conflicts with Spark's log4j binding
  exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j2-impl")
  exclude(group = "org.apache.logging.log4j", module = "log4j-layout-template-json")
}

configurations.testCompileClasspath {
  resolutionStrategy {
    force("org.apache.logging.log4j:log4j-api:2.17.2")
    force("org.apache.logging.log4j:log4j-core:2.17.2")
    force("org.apache.logging.log4j:log4j-slf4j-impl:2.17.2")
    force("org.apache.logging.log4j:log4j-1.2-api:2.17.2")
  }
  exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j2-impl")
  exclude(group = "org.apache.logging.log4j", module = "log4j-layout-template-json")
}

tasks.test {
  val skipDockerTests = project.findProperty("skipDockerTests")?.toString()?.toBoolean() ?: true
  if (skipDockerTests) {
    exclude("**/*")
  }

  useJUnitPlatform()

  // Fork a fresh JVM per test class. Spark plugins (org.apache.spark.api.plugin.SparkPlugin)
  // only initialize once per SparkContext, and SparkSession.close() + getOrCreate() does not
  // reliably tear down and rebuild the context in local[*] mode. Without this, the first test
  // that creates a SparkSession as a low-privilege user causes the GravitinoDriverPlugin to
  // register zero catalogs, and that empty catalog state leaks into every subsequent test in
  // the same JVM, manifesting as "Catalog hive_catalog does not support functions".
  forkEvery = 1

  testLogging {
    events("passed", "skipped", "failed", "started")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    showExceptions = true
    showCauses = true
    showStackTraces = true
    showStandardStreams = true

    // Display test method names and descriptions
    displayGranularity = 2

    // Show detailed info for each test
    info {
      events("passed", "skipped", "failed", "started")
    }
  }

  // Print test description before each test
  beforeTest(
    closureOf<TestDescriptor> {
      logger.lifecycle("Running test: ${this.className} > ${this.name}")
    }
  )

  // Print test result after each test
  afterTest(
    KotlinClosure2<TestDescriptor, TestResult, Unit>({ descriptor, result ->
      val status = when (result.resultType) {
        TestResult.ResultType.SUCCESS -> "✓ PASSED"
        TestResult.ResultType.FAILURE -> "✗ FAILED"
        TestResult.ResultType.SKIPPED -> "⊘ SKIPPED"
        else -> "UNKNOWN"
      }
      logger.lifecycle("$status: ${descriptor.className} > ${descriptor.name} (${result.endTime - result.startTime}ms)")
    })
  )

  // Pass environment variables to tests
  systemProperty("gravitino.uri", System.getenv("GRAVITINO_E2E_URI") ?: "http://localhost:30090")
  systemProperty("gravitino.metalake", System.getenv("GRAVITINO_E2E_METALAKE") ?: "test")
  systemProperty("hive.metastore.uri", System.getenv("GRAVITINO_E2E_HIVE_URI") ?: "thrift://localhost:30083")
}
