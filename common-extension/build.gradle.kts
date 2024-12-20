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
  implementation("org.apache.gravitino:common")

  implementation(libs.commons.lang3)
  implementation(libs.guava)
  implementation(libs.jackson.annotations)

  annotationProcessor(libs.lombok)
  compileOnly(libs.lombok)

  testImplementation("org.apache.gravitino:api")
  testImplementation(libs.jackson.databind)
  testImplementation(libs.junit.jupiter.api)

  testRuntimeOnly(libs.junit.jupiter.engine)
}
