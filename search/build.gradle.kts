/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

plugins {
  `maven-publish`
  id("java")
  id("idea")
  antlr
}

dependencies {
  implementation("org.apache.gravitino:api")
  implementation("org.apache.gravitino:core")
  implementation("org.apache.gravitino:common")
  implementation("org.apache.gravitino:server-common")
  antlr(libs.antlr4)
  implementation(libs.antlr4.runtime)

  implementation(project(":common-extension"))
  implementation(project(":core-extension"))

  implementation(libs.bundles.jersey)
  implementation(libs.bundles.log4j)
  implementation(libs.commons.lang3)
  implementation(libs.commons.io)
  implementation(libs.guava)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.datatype.jdk8)
  implementation(libs.jackson.datatype.jsr310)
  implementation(libs.jackson.annotations)
  implementation(libs.lombok)
  implementation(libs.open.search.java) {
    exclude(group = "com.fasterxml.jackson.core", module = "jackson-core")
    exclude(group = "com.fasterxml.jackson.core", module = "jackson-databind")
  }
  implementation(libs.open.search.rest)

  annotationProcessor(libs.lombok)

  testImplementation(project(":core-extension", "testArtifacts"))
  testImplementation(project(":test:test-common"))

  testImplementation(libs.awaitility)
  testImplementation(libs.commons.lang3)
  testImplementation(libs.jersey.test.framework.core) {
    exclude(group = "org.junit.jupiter")
  }
  testImplementation(libs.jersey.test.framework.provider.jetty) {
    exclude(group = "org.junit.jupiter")
  }
  testImplementation(libs.testcontainers)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.mockito.core)

  testRuntimeOnly(libs.junit.jupiter.engine)
}

configurations.named("runtimeClasspath") {
  exclude(group = "org.antlr", module = "antlr4")
}

val antlrSourcePath = "build/generated/java/com/datastrato/gravitino/search/antlr"

sourceSets {
  main {
    java {
      srcDirs("src/main/java", "build/generated/java")
    }
  }
}

tasks.generateGrammarSource {
  maxHeapSize = "64m"
  arguments = arguments + listOf("-visitor", "-long-messages", "-package", "com.datastrato.gravitino.search.antlr")
  outputDirectory = file(antlrSourcePath)
  setSource("src/main/antlr")
}

tasks.spotlessJava {
  dependsOn(tasks.generateGrammarSource)
}
