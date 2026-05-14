/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
description = "catalog-jdbc-sqlserver"

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
  // MIT-licensed, ASF Category A — bundled with module
  implementation("com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre11")

  implementation(libs.bundles.log4j)
  implementation(libs.commons.collections4)
  implementation(libs.commons.lang3)
  implementation(libs.guava)

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
  // MySQL driver is needed because BaseIT uses MySQL as Gravitino's metadata backend
  // in deploy-mysql CI mode, and DriverManager.getConnection requires it on the classpath.
  testImplementation(libs.mysql.driver)
  testImplementation(libs.testcontainers)

  testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks {
  register("runtimeJars", Copy::class) {
    from(configurations.runtimeClasspath)
    into("build/libs")
  }

  val copyCatalogLibs by registering(Copy::class) {
    dependsOn("jar", "runtimeJars")
    from("build/libs") {
      exclude("guava-*.jar")
      exclude("log4j-*.jar")
      exclude("slf4j-*.jar")
      exclude("error_prone_annotations-*.jar")
    }
    into("$rootDir/distribution/package/catalogs/jdbc-sqlserver/libs")
  }

  val copyCatalogConfig by registering(Copy::class) {
    from("src/main/resources")
    into("$rootDir/distribution/package/catalogs/jdbc-sqlserver/conf")

    include("jdbc-sqlserver.conf")

    exclude { details ->
      details.file.isDirectory()
    }

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
