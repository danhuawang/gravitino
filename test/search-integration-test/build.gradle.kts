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
  testImplementation("org.apache.gravitino:api")
  testImplementation("org.apache.gravitino:client-java")
  testImplementation("org.apache.gravitino:core")
  testImplementation("org.apache.gravitino:common")
  testImplementation(project(":search"))
  testImplementation(project(":test:test-common"))

  testImplementation("org.apache.gravitino:integration-test-common") {
    targetConfiguration = "testArtifacts"
  }

  testImplementation("org.apache.gravitino:lance-common") {
    exclude(group = "*")
  }

  testRuntimeOnly(libs.awaitility)

  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.testcontainers)
  testImplementation(libs.httpclient5)
  testImplementation(libs.jackson.databind)

  testRuntimeOnly(libs.junit.jupiter.engine)
}
