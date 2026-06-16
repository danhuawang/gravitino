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

// Flink connector E2E test dependencies (Paimon view support, PR #11349).
val flinkVersion: String = libs.versions.flink120.get()
val flinkMajorVersion: String = flinkVersion.substringBeforeLast(".")
val paimonFlinkVersion: String = libs.versions.paimon4flink120.get()

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
  testImplementation("org.apache.spark:spark-hive_$scalaVersion:$sparkVersion") {
    exclude("org.glassfish.jersey.core")
    exclude("org.glassfish.jersey.containers")
    exclude("org.glassfish.jersey.inject")
  }
  testImplementation("org.apache.kyuubi:kyuubi-spark-connector-hive_$scalaVersion:${libs.versions.kyuubi4spark.get()}")
  testImplementation("org.apache.iceberg:iceberg-spark-runtime-3.5_$scalaVersion:${libs.versions.iceberg.get()}")
  testImplementation("org.apache.iceberg:iceberg-aws:${libs.versions.iceberg.get()}") {
    // Exclude iceberg-core to avoid classpath conflicts with the shaded iceberg-flink-runtime
    // fat jar. The fat jar bundles its own shaded iceberg-core (with Jackson relocated to
    // org.apache.iceberg.shaded.*); if the unshaded iceberg-core is also present, the classloader
    // may pick up the wrong JsonUtil causing NoSuchMethodError in HMSTablePropertyHelper.
    exclude("org.apache.iceberg", "iceberg-core")
  }
  testImplementation(libs.aws.s3)
  testImplementation(libs.aws.sts)
  testImplementation(libs.aws.kms)
  testImplementation(libs.trino.jdbc)
  testImplementation(libs.hadoop3.aws)
  testImplementation(libs.hadoop3.common)
  testImplementation(libs.hadoop3.client.api)
  testImplementation(project(":clients:client-java-runtime", configuration = "shadow"))

  // Flink connector + Paimon (PR #11349 view support E2E). The Gravitino Flink runtime shadow
  // jar provides the gravitino-paimon catalog factory and catalog store; the Flink table/planner
  // and Paimon Flink bundle provide the SQL execution engine used to drive DDL such as DROP VIEW.
  testImplementation(project(":flink-connector:flink-runtime-1.20", configuration = "shadow"))
  testImplementation("org.apache.flink:flink-table-common:$flinkVersion")
  testImplementation("org.apache.flink:flink-table-api-java:$flinkVersion")
  testImplementation("org.apache.flink:flink-table-api-bridge-base:$flinkVersion") {
    exclude("commons-cli", "commons-cli")
    exclude("commons-io", "commons-io")
    exclude("com.google.code.findbugs", "jsr305")
  }
  testImplementation("org.apache.flink:flink-table-planner_$scalaVersion:$flinkVersion")
  testImplementation("org.apache.flink:flink-test-utils:$flinkVersion")
  testImplementation("org.apache.paimon:paimon-flink-$flinkMajorVersion:$paimonFlinkVersion")
  testImplementation("org.apache.paimon:paimon-s3:$paimonFlinkVersion")
  testImplementation("org.apache.flink:flink-connector-base:$flinkVersion")
  testImplementation("org.apache.flink:flink-connector-files:$flinkVersion")

  // PostgreSQL JDBC driver for Paimon JDBC backend E2E tests.
  testImplementation(libs.postgresql.driver)

  // Awaitility for async condition polling in Iceberg async purge tests.
  testImplementation(libs.awaitility)

  // Iceberg Flink runtime — provides FlinkCatalogFactory needed by GravitinoIcebergCatalog.
  testImplementation("org.apache.iceberg:iceberg-flink-runtime-$flinkMajorVersion:${libs.versions.iceberg4flink120.get()}")
}

configurations.testRuntimeClasspath {
  resolutionStrategy {
    force("org.apache.logging.log4j:log4j-api:2.17.2")
    force("org.apache.logging.log4j:log4j-core:2.17.2")
    force("org.apache.logging.log4j:log4j-slf4j-impl:2.17.2")
    force("org.apache.logging.log4j:log4j-1.2-api:2.17.2")
    force("org.apache.httpcomponents.core5:httpcore5:${libs.versions.httpcore5.get()}")
    force("org.apache.httpcomponents.client5:httpclient5:${libs.versions.httpclient5.get()}")
    force("org.apache.hadoop:hadoop-client-api:3.3.6")
    force("org.apache.hadoop:hadoop-client-runtime:3.3.6")
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
    force("org.apache.httpcomponents.core5:httpcore5:${libs.versions.httpcore5.get()}")
    force("org.apache.httpcomponents.client5:httpclient5:${libs.versions.httpclient5.get()}")
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
  systemProperty("gravitino.irc.catalog", System.getenv("GRAVITINO_E2E_IRC_CATALOG") ?: "catalog_iceberg_s3_3")
  systemProperty("gravitino.trino.uri", System.getenv("GRAVITINO_E2E_TRINO_URI") ?: "http://localhost:30880")
  systemProperty("hive.metastore.uri", System.getenv("GRAVITINO_E2E_HIVE_URI") ?: "thrift://localhost:30083")

  // Warehouse location for the Paimon catalog used by the Flink view E2E tests. Uses the same
  // S3 bucket as the Glue tests with a separate prefix; override with GRAVITINO_E2E_PAIMON_WAREHOUSE.
  systemProperty("paimon.warehouse", System.getenv("GRAVITINO_E2E_PAIMON_WAREHOUSE") ?: "s3a://gravitino-glue-test/paimon/warehouse")
  systemProperty("s3.access.key", System.getenv("GLUE_AWS_ACCESS_KEY_ID") ?: "minioadmin")
  systemProperty("s3.secret.key", System.getenv("GLUE_AWS_SECRET_ACCESS_KEY") ?: "minioadmin")
  systemProperty("s3.endpoint", System.getenv("S3_ENDPOINT") ?: "http://s3.us-east-1.amazonaws.com")

  // AWS Glue catalog properties (optional properties are only set when the env var exists)
  systemProperty("glue.aws.region", System.getenv("GLUE_AWS_REGION") ?: "us-east-1")
  systemProperty("glue.aws.catalog.id", System.getenv("GLUE_AWS_CATALOG_ID") ?: "730335553010")
  systemProperty("glue.aws.warehouse", System.getenv("GLUE_AWS_WAREHOUSE") ?: "s3://gravitino-glue-test/warehouse")
  System.getenv("GLUE_AWS_ACCESS_KEY_ID")?.let { systemProperty("glue.aws.access.key.id", it) }
  System.getenv("GLUE_AWS_SECRET_ACCESS_KEY")?.let { systemProperty("glue.aws.secret.access.key", it) }
  System.getenv("GLUE_AWS_ENDPOINT")?.let { systemProperty("glue.aws.endpoint", it) }

  // Disable JVM system-proxy auto-detection. On macOS the JVM otherwise picks up the
  // system-level SOCKS / HTTP proxy from System Preferences and routes outbound HTTP
  // through it, which breaks the Iceberg REST client (it sees a "Malformed reply from
  // SOCKS server" when the proxy is misconfigured / off). Tests talk to localhost or
  // private kind-cluster IPs and never need a proxy.
  systemProperty("java.net.useSystemProxies", "false")
  systemProperty("http.nonProxyHosts", "*")
  systemProperty("socksNonProxyHosts", "*")

  // Disable Gravitino client-server version check. The Flink connector's CatalogStore builds its
  // own GravitinoAdminClient without exposing a "disable version check" option; this env var is
  // the only way to allow a SNAPSHOT client to talk to an older released server.
  environment("GRAVITINO_VERSION_CHECK_DISABLED", "true")
}
