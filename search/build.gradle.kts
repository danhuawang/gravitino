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
  implementation(project(":api"))
  implementation(project(":core"))
  implementation(project(":common"))
  implementation(project(":server-common"))
  implementation(project(":licensing:license-client"))
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
    exclude(group = "com.fasterxml.jackson.datatype", module = "jackson-datatype-jdk8")
    exclude(group = "com.fasterxml.jackson.datatype", module = "jackson-datatype-jsr310")
    exclude(group = "com.fasterxml.jackson.core", module = "jackson-annotations")
    exclude(group = "commons-logging", module = "commons-logging")
    exclude(group = "commons-codec", module = "commons-codec")
    exclude(group = "org.apache.httpcomponents.client5", module = "httpclient5")
    exclude(group = "org.apache.httpcomponents.core5", module = "httpcore5")
    exclude(group = "org.apache.httpcomponents.core5", module = "httpcore5-h2")
  }
  implementation(libs.open.search.rest)

  annotationProcessor(libs.lombok)

  testImplementation(project(":core-extension", "testArtifacts"))
  testImplementation(project(":integration-test-common", "testArtifacts"))
  testImplementation(project(":test:test-common"))

  testImplementation(project(":iceberg:iceberg-rest-server"))
  testImplementation(libs.iceberg.core)
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

  testRuntimeOnly(project(":plugins:idp-basic"))
  testRuntimeOnly(project(":plugins:scim"))
  testRuntimeOnly(libs.junit.jupiter.engine)
}

configurations.named("runtimeClasspath") {
  exclude(group = "org.antlr", module = "antlr4")
}

val antlrSourcePath = "build/generated/java/com/datastrato/gravitino/search/antlr"

sourceSets {
  main {
    java {
      srcDir("build/generated/java")
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

tasks.sourcesJar {
  dependsOn(tasks.generateGrammarSource)
}

tasks.javadoc {
  isFailOnError = false
}

// The Iceberg REST server test dependency is produced by copyDepends.
// Make the execution order explicit to satisfy Gradle task validation.
tasks.named<Test>("test") {
  dependsOn(":iceberg:iceberg-rest-server:copyDepends")
}

// compileTestJava also consumes the copied Iceberg REST server artifact.
tasks.named<JavaCompile>("compileTestJava") {
  dependsOn(":iceberg:iceberg-rest-server:copyDepends")
}
