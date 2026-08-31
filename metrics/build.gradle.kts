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
  implementation(project(":core"))
  implementation(project(":common"))
  implementation(project(":server-common"))

  implementation(libs.bundles.jersey)
  implementation(libs.bundles.log4j)
  implementation(libs.commons.lang3)
  implementation(libs.guava)
  implementation(libs.jackson.annotations)
  implementation(libs.lombok)
  implementation(libs.mybatis)
  implementation(libs.jcasbin) {
    exclude(group = "com.fasterxml.jackson.core", module = "jackson-databind")
    exclude(group = "org.slf4j", module = "slf4j-api")
    exclude(group = "com.google.errorprone", module = "error_prone_annotations")
  }

  annotationProcessor(libs.lombok)

  testImplementation(libs.commons.io)
  testImplementation(libs.jersey.test.framework.core) {
    exclude(group = "org.junit.jupiter")
  }
  testImplementation(libs.jersey.test.framework.provider.jetty) {
    exclude(group = "org.junit.jupiter")
  }
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.mockito.core)
  testImplementation(libs.mockito.inline)

  testRuntimeOnly(libs.junit.jupiter.engine)
}
