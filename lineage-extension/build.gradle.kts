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
  implementation("org.apache.gravitino:core")
  implementation("org.apache.gravitino:lineage")
  implementation("org.apache.gravitino:server-common")
  implementation("org.apache.gravitino:common")

  implementation(libs.bundles.log4j)
  implementation(libs.commons.lang3)
  implementation(libs.guava)
  implementation(libs.jackson.core)
  implementation(libs.jackson.databind)
  implementation(libs.jakarta.rs.api)
  implementation(libs.openlineage.java) {
    exclude("commons-logging")
    exclude("org.slf4j")
  }

  annotationProcessor(libs.lombok)
  compileOnly(libs.lombok)
  testAnnotationProcessor(libs.lombok)
  testCompileOnly(libs.lombok)

  testImplementation(libs.junit.jupiter.api)

  testRuntimeOnly(libs.junit.jupiter.engine)
}
