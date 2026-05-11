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
  implementation(project(":api"))
  implementation(project(":core"))
  implementation(project(":common"))
  implementation(project(":server"))
  implementation(project(":server-common"))
  implementation(project(":licensing:license-client"))

  implementation(project(":common-extension"))
  implementation(project(":core-extension"))
  implementation(project(":licensing:license-client"))
  implementation(project(":metrics"))
  implementation(project(":search")) {
    exclude(group = "org.antlr", module = "antlr4")
  }

  implementation(libs.guava)
  implementation(libs.bundles.jersey)
  implementation(libs.bundles.log4j)

  annotationProcessor(libs.lombok)

  compileOnly(libs.lombok)

  testImplementation(project(":server-common"))
  testImplementation(libs.commons.lang3)
  testImplementation(libs.jersey.test.framework.core) {
    exclude(group = "org.junit.jupiter")
  }
  testImplementation(libs.jersey.test.framework.provider.jetty) {
    exclude(group = "org.junit.jupiter")
  }
  testImplementation(libs.mockito.core)
  testImplementation(libs.junit.jupiter.api)

  testRuntimeOnly(libs.junit.jupiter.engine)
}
