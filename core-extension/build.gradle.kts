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

  implementation(libs.bundles.log4j)
  implementation(libs.guava)

  testImplementation("org.apache.gravitino:common")
  testImplementation("org.apache.gravitino:core")
  testImplementation(libs.commons.lang3)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.mockito.core)
  testAnnotationProcessor(libs.lombok)
  testCompileOnly(libs.lombok)

  testRuntimeOnly(libs.junit.jupiter.engine)
}
