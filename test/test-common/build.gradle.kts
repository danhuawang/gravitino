/*
 * Copyright 2024 Datastrato Inc.
 */

plugins {
  `maven-publish`
  id("java")
  id("idea")
}

dependencies {
  implementation(libs.awaitility)
  implementation(libs.bundles.log4j)
  implementation(libs.guava)
  implementation(libs.testcontainers) {
    exclude(group = "com.fasterxml.jackson.core")
  }
}
