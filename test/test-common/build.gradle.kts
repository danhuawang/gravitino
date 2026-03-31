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
  implementation(libs.awaitility)
  implementation(libs.bundles.log4j)
  implementation(libs.guava)
  implementation(libs.testcontainers) {
    exclude(group = "com.fasterxml.jackson.core")
  }
}
