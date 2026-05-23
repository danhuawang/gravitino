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

  testImplementation(project(":qa:e2e-common"))
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testImplementation(libs.testcontainers)
  testImplementation(libs.testcontainers.mysql)
  testImplementation(libs.testcontainers.postgresql)

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

  // Jersey is excluded because it conflicts with the Gravitino server's jersey
  // already on this module's classpath.
  testImplementation("org.apache.spark:spark-hive_$scalaVersion:$sparkVersion") {
    exclude("org.glassfish.jersey.core")
    exclude("org.glassfish.jersey.containers")
    exclude("org.glassfish.jersey.inject")
  }

  // Add Spark runtime dependencies needed for Hive catalog support
  testImplementation("org.apache.kyuubi:kyuubi-spark-connector-hive_$scalaVersion:${libs.versions.kyuubi4spark.get()}")

  testImplementation("org.apache.iceberg:iceberg-spark-runtime-3.5_$scalaVersion:${libs.versions.iceberg.get()}")

  testImplementation(project(":clients:client-java-runtime", configuration = "shadow"))
}

configurations.testRuntimeClasspath {
  resolutionStrategy {
    force("org.apache.logging.log4j:log4j-api:2.17.2")
    force("org.apache.logging.log4j:log4j-core:2.17.2")
    force("org.apache.logging.log4j:log4j-slf4j-impl:2.17.2")
    force("org.apache.logging.log4j:log4j-1.2-api:2.17.2")
  }
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

  forkEvery = 1

  testLogging {
    events("passed", "skipped", "failed", "started")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    showExceptions = true
    showCauses = true
    showStackTraces = true
    showStandardStreams = true

    displayGranularity = 2

    info {
      events("passed", "skipped", "failed", "started")
    }
  }

  beforeTest(
    closureOf<TestDescriptor> {
      logger.lifecycle("Running test: ${this.className} > ${this.name}")
    }
  )

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

  systemProperty("gravitino.uri", System.getenv("GRAVITINO_E2E_URI") ?: "http://localhost:30090")
  systemProperty("gravitino.irc.uri", System.getenv("GRAVITINO_E2E_IRC_URI") ?: "http://localhost:30001/iceberg/")
  systemProperty("gravitino.metalake", System.getenv("GRAVITINO_E2E_METALAKE") ?: "test")
  systemProperty("gravitino.irc.catalog", System.getenv("GRAVITINO_E2E_IRC_CATALOG") ?: "catalog_1")
  systemProperty("hive.metastore.uri", System.getenv("GRAVITINO_E2E_HIVE_URI") ?: "thrift://localhost:30083")

  // Disable JVM system-proxy auto-detection. On macOS the JVM otherwise picks up the
  // system-level SOCKS / HTTP proxy from System Preferences and routes outbound HTTP
  // through it, which breaks the Iceberg REST client (it sees a "Malformed reply from
  // SOCKS server" when the proxy is misconfigured / off). Tests talk to localhost or
  // private kind-cluster IPs and never need a proxy.
  systemProperty("java.net.useSystemProxies", "false")
  systemProperty("http.nonProxyHosts", "*")
  systemProperty("socksNonProxyHosts", "*")
}
