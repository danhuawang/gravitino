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
  implementation(project(":common-extension"))

  implementation(libs.bundles.log4j)
  implementation(libs.commons.codec)
  implementation(libs.commons.dbcp2)
  implementation(libs.commons.lang3)
  implementation(libs.guava)
  implementation(libs.jackson.annotations)
  implementation(libs.mybatis)
  implementation(libs.trino.jdbc)

  annotationProcessor(libs.lombok)
  compileOnly(libs.lombok)
  compileOnly(project(":plugins:idp-basic"))
  compileOnly(project(":plugins:scim"))

  testImplementation(project(":plugins:idp-basic"))
  testImplementation(project(":plugins:scim"))
  testImplementation(libs.commons.io)
  testImplementation(libs.commons.lang3)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.mockito.core)
  testAnnotationProcessor(libs.lombok)
  testCompileOnly(libs.lombok)

  testRuntimeOnly(libs.junit.jupiter.engine)
}

val testJar by tasks.registering(Jar::class) {
  archiveClassifier.set("tests")
  from(sourceSets["test"].output)
}

configurations {
  create("testArtifacts")
}

artifacts {
  add("testArtifacts", testJar)
}
