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
  testImplementation(project(":api"))
  testImplementation(project(":clients:client-java"))
  testImplementation(project(":core"))
  testImplementation(project(":common"))
  testImplementation(project(":server"))
  testImplementation(project(":search"))
  testImplementation(project(":test:test-common"))

  testImplementation(project(":integration-test-common", "testArtifacts"))

  // Required so MiniGravitino can load the Iceberg REST auxiliary service (ignoreIcebergAuxRestService=false).
  // The AuxiliaryServiceManager resolves the service from the test classpath when the configured
  // classpath dirs (iceberg-rest-server/libs) are not present in the test working directory.
  testImplementation(project(":iceberg:iceberg-rest-server"))

  testImplementation(project(":lance:lance-common")) {
    exclude(group = "*")
  }

  testImplementation(libs.awaitility)
  testImplementation(libs.guava)
  testImplementation(libs.httpclient5)
  testImplementation(libs.jackson.databind)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.mysql.driver)
  testImplementation(libs.testcontainers)

  testRuntimeOnly(libs.junit.jupiter.engine)
}

// The Iceberg REST server jar used on test classpath is assembled by copyDepends.
// Make the dependency explicit to satisfy Gradle task validation.
tasks.named<JavaCompile>("compileTestJava") {
  dependsOn(":iceberg:iceberg-rest-server:copyDepends")
}
