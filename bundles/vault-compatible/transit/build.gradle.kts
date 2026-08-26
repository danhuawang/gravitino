/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

plugins {
  `maven-publish`
  id("java")
  id("java-test-fixtures")
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

  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testRuntimeOnly(libs.junit.jupiter.engine)

  testFixturesApi(project(":api"))
  testFixturesApi(project(":common"))
  testFixturesApi(libs.junit.jupiter.api)
  testFixturesImplementation(libs.testcontainers)
}

tasks.javadoc {
  options.memberLevel = JavadocMemberLevel.PUBLIC
}
