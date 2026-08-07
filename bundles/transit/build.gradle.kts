/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

plugins {
  `maven-publish`
  id("java")
}

dependencies {
  implementation(project(":api")) {
    exclude(group = "*")
  }
  implementation(project(":common")) {
    exclude(group = "*")
  }
  implementation(libs.commons.lang3)
  implementation(libs.guava)
  implementation(libs.httpclient5)
  implementation(libs.jackson.databind)

  testImplementation(testFixtures(project(":common")))
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.javadoc {
  options.memberLevel = JavadocMemberLevel.PUBLIC
}
