/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
description = "catalog-jdbc-oracle"

plugins {
  `maven-publish`
  id("java")
  id("idea")
}

dependencies {
  compileOnly(project(":api"))
  compileOnly(project(":common"))
  compileOnly(project(":core"))

  implementation(project(":catalogs:catalog-jdbc-common")) {
    exclude(group = "*")
  }

  implementation(libs.bundles.log4j)
  implementation(libs.commons.collections4)
  implementation(libs.commons.lang3)
  implementation(libs.guava)
  runtimeOnly(libs.oracle.driver)

  testImplementation(project(":api"))
  testImplementation(project(":catalogs:catalog-jdbc-common", "testArtifacts"))
  testImplementation(project(":clients:client-java"))
  testImplementation(project(":common"))
  testImplementation(project(":core"))
  testImplementation(project(":integration-test-common", "testArtifacts"))
  testImplementation(project(":server"))
  testImplementation(project(":server-common"))

  testImplementation(libs.awaitility)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testImplementation(libs.mockito.core)
  testImplementation(libs.mysql.driver)
  testImplementation(libs.oracle.driver)
  testImplementation(libs.postgresql.driver)
  testImplementation(libs.testcontainers)

  testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks {
  register("runtimeJars", Copy::class) {
    doFirst {
      delete(fileTree("build/libs") { include("ojdbc*.jar") })
    }
    from(configurations.runtimeClasspath)
    into("build/libs")
  }

  val copyCatalogLibs by registering(Copy::class) {
    dependsOn("jar", "runtimeJars")
    doFirst {
      delete(
        fileTree("$rootDir/distribution/package/catalogs/jdbc-oracle/libs") {
          include("ojdbc*.jar")
        }
      )
    }
    from("build/libs") {
      exclude("guava-*.jar")
      exclude("log4j-*.jar")
      exclude("slf4j-*.jar")
      exclude("error_prone_annotations-*.jar")
    }
    into("$rootDir/distribution/package/catalogs/jdbc-oracle/libs")
  }

  val copyCatalogConfig by registering(Copy::class) {
    from("src/main/resources")
    into("$rootDir/distribution/package/catalogs/jdbc-oracle/conf")

    include("jdbc-oracle.conf")

    exclude { details -> details.file.isDirectory() }

    fileMode = 0b111101101
  }

  register("copyLibAndConfig", Copy::class) {
    dependsOn(copyCatalogLibs, copyCatalogConfig)
  }
}

tasks.test {
  val skipITs = project.hasProperty("skipITs")
  if (skipITs) {
    // Exclude integration tests
    exclude("**/integration/test/**")
  } else {
    dependsOn(tasks.jar)
  }
}

tasks.getByName("generateMetadataFileForMavenJavaPublication") {
  dependsOn("runtimeJars")
}
