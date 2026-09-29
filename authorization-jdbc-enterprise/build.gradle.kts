/*
 * Copyright 2024 Datastrato Inc.
 */

plugins {
  `maven-publish`
  id("java")
  id("idea")
}

dependencies {
  implementation(project(":api"))
  implementation(project(":authorizations:authorization-common"))
  implementation(project(":common"))
  implementation(project(":core"))

  implementation(libs.bundles.log4j)
  implementation(libs.commons.lang3)
  implementation(libs.guava)
  implementation(libs.jackson.annotations)
  implementation(libs.hive2.jdbc)

  annotationProcessor(libs.lombok)
  compileOnly(libs.lombok)

  testImplementation(project(":integration-test-common", "testArtifacts"))
  testImplementation(libs.jackson.databind)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.testcontainers)

  testRuntimeOnly(libs.junit.jupiter.engine)
}

// enterprise-deployment #302, item 4: ship only what the Hive JDBC client needs.
//
// This plugin reaches Hive purely over JDBC: JdbcAuthorizationPlugin
// (authorization-common) opens a DBCP2 pool against org.apache.hive.jdbc.HiveDriver.
// Its code imports nothing from Hadoop, Hive, HBase or Thrift, so the whole Hive/HBase
// server side that hive-jdbc 2.3.9 drags in transitively never runs. The plugin lives
// in an isolated classloader, so the driver's own runtime deps must stay in this dir.
//
// The exclusions below drop, from what gets copied into
// authorizations/jdbc-enterprise/libs/:
//   (a) GPL code, test jars and junit (jol-core, *-tests jars, junit + hamcrest), and
//   (b) the unused Hadoop/Hive/HBase cluster server components (YARN servers,
//       MapReduce, HBase, LLAP, Tez, Jetty, Jasper).
// Kept: the plugin's own jars, authorization-common, DBCP, the Hive JDBC driver, and
// the Hadoop client classes the driver needs (hadoop-common / -auth / -client / -hdfs
// supply Configuration and UserGroupInformation, which the non-standalone hive-jdbc
// jar lacks).
val libExclusions =
  listOf(
    // --- pre-existing excludes: provided by the isolated classloader parent ---
    "guava-*.jar",
    "log4j-*.jar",
    "slf4j-*.jar",
    // --- (a) GPL code, test jars and junit ---
    // jol-core 0.2 is GPL-2.0 whose Classpath Exception coverage for this jar is
    // not established; it is unused, so it must not ship.
    "jol-core-*.jar",
    // Test artifacts have no place in a production image and fail security reviews.
    "*-tests.jar",
    "junit-*.jar",
    "hamcrest-*.jar",
    // --- (b) unused Hadoop/Hive/HBase cluster server components ---
    // YARN servers + MapReduce (job execution; unused over JDBC).
    "hadoop-yarn-server-*.jar",
    "hadoop-yarn-client-*.jar",
    "hadoop-yarn-registry-*.jar",
    "hadoop-mapreduce-*.jar",
    // HBase (Hive's optional HBase integration; unused over JDBC).
    "hbase-*.jar",
    // Twill / Tephra (HBase transaction layer pulled in via HBase).
    "twill-*.jar",
    "tephra-*.jar",
    // Hive LLAP + HS2 server side (we are a JDBC client, not a server).
    // Keep hive-service-rpc: it carries the Thrift RPC classes hive-jdbc uses to
    // open a connection. Only the HS2 server jar (hive-service-2.3.9) is dropped.
    "hive-llap-*.jar",
    "hive-service-2.3.9.jar",
    "hive-shims-scheduler-*.jar",
    // Embedded servlet containers / JSP engines (server side only).
    // servlet-api is intentionally kept: hive-jdbc's HTTP transport mode can touch it,
    // and the jars are tiny, so keeping them avoids a runtime risk for no license cost.
    "jetty-*.jar",
    "jasper-*.jar",
    "jsp-*.jar",
    "jamon-runtime-*.jar",
    // Slider (YARN app packaging; unused).
    "slider-core-*.jar"
  )

tasks {
  val runtimeJars by registering(Copy::class) {
    from(configurations.runtimeClasspath)
    into("build/libs")
  }

  val copyAuthorizationLibs by registering(Copy::class) {
    dependsOn("jar", runtimeJars)
    from("build/libs") {
      libExclusions.forEach { exclude(it) }
    }
    into("$rootDir/distribution/package/authorizations/jdbc-enterprise/libs")
  }

  register("copyLibAndConfig", Copy::class) {
    dependsOn(copyAuthorizationLibs)
  }
}
