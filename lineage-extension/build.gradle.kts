/*
 * Copyright 2024 Datastrato Inc.
 */

plugins {
  `maven-publish`
  id("java")
  id("idea")
}

dependencies {
  implementation(project(":core"))
  implementation(project(":lineage")) {
    exclude(group = "com.fasterxml.jackson.core")
    exclude(group = "com.fasterxml.jackson.dataformat")
    exclude(group = "com.fasterxml.jackson.datatype")
  }
  implementation(project(":server-common"))
  implementation(project(":common"))

  implementation(libs.bundles.log4j)
  implementation(libs.commons.lang3)
  implementation(libs.guava)
  implementation(libs.jackson.core)
  implementation(libs.jackson.databind)
  implementation(libs.jakarta.rs.api)
  implementation(libs.openlineage.java) {
    exclude(group = "com.fasterxml.jackson.core")
    exclude(group = "com.fasterxml.jackson.dataformat")
    exclude(group = "com.fasterxml.jackson.datatype")

    exclude("commons-logging")
    exclude("org.slf4j")

    exclude("org.hdrhistogram")
    exclude("io.micrometer")
    exclude("org.apache.commons", "commons-lang3")
    exclude("org.yaml", "snakeyaml")
    exclude(group = "commons-codec", module = "commons-codec")
  }

  annotationProcessor(libs.lombok)
  compileOnly(libs.lombok)
  testAnnotationProcessor(libs.lombok)
  testCompileOnly(libs.lombok)

  testImplementation(libs.junit.jupiter.api)

  testRuntimeOnly(libs.junit.jupiter.engine)
}
