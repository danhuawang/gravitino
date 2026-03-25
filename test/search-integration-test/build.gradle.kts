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
  testImplementation(project(":search"))
  testImplementation(project(":test:test-common"))

  testImplementation(project(":integration-test-common", "testArtifacts"))

  testImplementation(project(":lance:lance-common")) {
    exclude(group = "*")
  }

  testImplementation(libs.awaitility)
  testImplementation(libs.guava)
  testImplementation(libs.httpclient5)
  testImplementation(libs.jackson.databind)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.testcontainers)

  testRuntimeOnly(libs.junit.jupiter.engine)
}
