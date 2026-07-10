/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

plugins {
  `maven-publish`
  id("java")
  id("idea")
}

dependencies {
  annotationProcessor(libs.lombok)

  implementation(project(":api"))
  implementation(project(":server-common"))
  implementation(project(":common"))
  implementation(project(":core"))

  implementation(libs.commons.lang3)
  implementation(libs.guava)
  implementation(libs.mybatis)

  compileOnly(libs.lombok)
  compileOnly(libs.slf4j.api)

  testImplementation(project(":integration-test-common", "testArtifacts"))

  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testImplementation(libs.mysql.driver)
  testImplementation(libs.postgresql.driver)
  testImplementation(libs.testcontainers)
  testImplementation(libs.testcontainers.mysql)
  testImplementation(libs.testcontainers.postgresql)

  testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks {
  jar {
    archiveBaseName.set("gravitino-scim-plugin")
  }

  val copyLibs by registering(Copy::class) {
    dependsOn(jar)
    from(layout.buildDirectory.dir("libs")) {
      include("gravitino-scim-plugin-*.jar")
      exclude("*-javadoc.jar", "*-sources.jar")
    }
    into("$rootDir/distribution/package/libs")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  }

  register("copyLibAndConfigs", Copy::class) {
    group = "gravitino distribution"
    description = "Copy scim plugin jar into distribution package libs"
    dependsOn(copyLibs)
  }

  test {
    environment("GRAVITINO_HOME", rootDir.path)

    val skipITs = project.hasProperty("skipITs")
    if (skipITs) {
      exclude("**/integration/test/**")
    }
  }
}
