/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

plugins {
  `maven-publish`
  id("java")
  id("idea")
}

dependencies {
  implementation("org.apache.gravitino:api")
  implementation("org.apache.gravitino:authorizations:authorization-common")
  implementation("org.apache.gravitino:common")
  implementation("org.apache.gravitino:core")

  implementation(libs.bundles.log4j)
  implementation(libs.commons.lang3)
  implementation(libs.guava)
  implementation(libs.jackson.annotations)
  implementation(libs.hive2.jdbc)

  annotationProcessor(libs.lombok)
  compileOnly(libs.lombok)

  testImplementation("org.apache.gravitino:integration-test-common") {
    targetConfiguration = "testArtifacts"
  }
  testImplementation(libs.jackson.databind)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.testcontainers)

  testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks {
  val runtimeJars by registering(Copy::class) {
    from(configurations.runtimeClasspath)
    into("build/libs")
  }

  val copyAuthorizationLibs by registering(Copy::class) {
    dependsOn("jar", runtimeJars)
    from("build/libs") {
      exclude("guava-*.jar")
      exclude("log4j-*.jar")
      exclude("slf4j-*.jar")
    }
    into("$rootDir/distribution/package/authorizations/jdbc-enterprise/libs")
  }

  register("copyLibAndConfig", Copy::class) {
    dependsOn(copyAuthorizationLibs)
  }
}
