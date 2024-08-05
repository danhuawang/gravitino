/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

plugins {
  `maven-publish`
  id("java")
  id("idea")
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(libs.bundles.log4j)
  implementation(libs.commons.lang3)
  implementation(libs.guava)

  implementation("org.apache.gravitino:trino-connector")
  implementation("org.apache.gravitino:client-java-runtime")
  implementation("org.apache.gravitino:catalog-common")

  compileOnly(libs.trino.spi) {
    exclude("org.apache.logging.log4j")
  }
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.engine)
  testImplementation(libs.mockito.core)
  testImplementation(libs.trino.spi) {
    exclude("org.apache.logging.log4j")
  }
  testImplementation("org.apache.gravitino:api")
}
