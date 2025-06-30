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
  implementation("org.apache.gravitino:server")
  implementation("org.apache.gravitino:server-common")

  implementation(project(":common-extension"))
  implementation(project(":core-extension"))
  implementation(project(":search"))

  implementation(libs.guava)
  implementation(libs.bundles.jersey)
  implementation(libs.bundles.log4j)

  annotationProcessor(libs.lombok)

  compileOnly(libs.lombok)

  testImplementation("org.apache.gravitino:server-common")
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
