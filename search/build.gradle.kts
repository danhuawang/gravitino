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
  implementation("org.apache.gravitino:core")
  implementation("org.apache.gravitino:common")
  implementation("org.apache.gravitino:server-common")

  implementation(project(":common-extension"))
  implementation(project(":core-extension"))

  implementation(libs.bundles.jersey)
  implementation(libs.bundles.log4j)
  implementation(libs.commons.lang3)
  implementation(libs.commons.io)
  implementation(libs.guava)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.datatype.jdk8)
  implementation(libs.jackson.datatype.jsr310)
  implementation(libs.jackson.annotations)
  implementation(libs.lombok)
  implementation(libs.open.search.java)
  implementation(libs.open.search.rest)

  annotationProcessor(libs.lombok)

  testImplementation(project(":core-extension", "testArtifacts"))

  testImplementation(libs.commons.lang3)
  testImplementation(libs.jersey.test.framework.core) {
    exclude(group = "org.junit.jupiter")
  }
  testImplementation(libs.jersey.test.framework.provider.jetty) {
    exclude(group = "org.junit.jupiter")
  }
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.mockito.core)

  testRuntimeOnly(libs.junit.jupiter.engine)
}
