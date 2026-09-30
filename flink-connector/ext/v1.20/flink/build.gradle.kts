/*
 * Copyright 2026 Datastrato Inc.
 */

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

evaluationDependsOn(":flink-connector:flink-1.20")

plugins {
  `maven-publish`
  id("java")
  id("idea")
  alias(libs.plugins.shadow)
}

repositories {
  mavenCentral()
}

val flinkVersion: String = libs.versions.flink120.get()
val flinkMajorVersion: String = flinkVersion.substringBeforeLast(".")
val scalaVersion: String = "2.12"
val artifactName by extra("datastrato-flink-connector-ext-${flinkMajorVersion}_$scalaVersion")

group = "com.datastrato"

configurations.all {
  resolutionStrategy.eachDependency {
    if (requested.group == "org.apache.logging.log4j") {
      throw GradleException("Dependency 'org.apache.logging.log4j' is not allowed.")
    }
  }
}

// The integration tests reuse the open source connector's test classpath.
val connectorTestClasspath = project(":flink-connector:flink-1.20").the<SourceSetContainer>()["test"].runtimeClasspath

dependencies {
  implementation(project(":flink-connector:flink-ext-common"))
  compileOnly(project(":flink-connector:flink-1.20"))
  compileOnly(project(":flink-connector:flink-common"))
  compileOnly(project(":clients:client-java-runtime", configuration = "shadow"))
  compileOnly("org.apache.flink:flink-table-common:$flinkVersion")
  compileOnly("org.apache.flink:flink-table-api-java:$flinkVersion")
  compileOnly(libs.flinkjdbc120)

  testImplementation(connectorTestClasspath)
  testImplementation(project(":flink-connector:flink-ext-common", "testArtifacts"))
  testImplementation(libs.oracle.driver)
  testImplementation(libs.mssql.jdbc)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
  if (project.hasProperty("skipITs")) {
    exclude("**/integration/test/**")
  } else {
    dependsOn(tasks.jar)
    dependsOn(":catalogs:catalog-hive:jar")
    dependsOn(":catalogs:catalog-hive:runtimeJars")
    dependsOn(":catalogs:catalog-lakehouse-iceberg:jar")
    dependsOn(":catalogs:catalog-lakehouse-iceberg:runtimeJars")
    dependsOn(":iceberg:iceberg-rest-server:jar")
    dependsOn(":catalogs:catalog-lakehouse-paimon:jar")
    dependsOn(":catalogs:catalog-lakehouse-paimon:runtimeJars")
    dependsOn(":catalogs:catalog-jdbc-mysql:jar")
    dependsOn(":catalogs:catalog-jdbc-mysql:runtimeJars")
    dependsOn(":catalogs:catalog-jdbc-postgresql:jar")
    dependsOn(":catalogs:catalog-jdbc-postgresql:runtimeJars")
    dependsOn(":catalogs:catalog-jdbc-oracle:jar")
    dependsOn(":catalogs:catalog-jdbc-oracle:runtimeJars")
    dependsOn(":catalogs:catalog-jdbc-sqlserver:jar")
    dependsOn(":catalogs:catalog-jdbc-sqlserver:runtimeJars")
  }
}

tasks.withType<Jar> {
  archiveBaseName.set(artifactName)
}

// The published artifact bundles only the ext modules; the open source connector runtime jar
// stays a separate, unmodified dependency. Relocations mirror flink-runtime so classes here
// link against the same shaded packages at runtime.
tasks.withType<ShadowJar> {
  isZip64 = true
  configurations = listOf(project.configurations.runtimeClasspath.get())
  archiveFileName.set("$artifactName-$version.jar")
  archiveClassifier.set("")
  dependencies {
    include(project(":flink-connector:flink-ext-common"))
  }
  mergeServiceFiles()

  relocate("com.google", "org.apache.gravitino.shaded.com.google")
  relocate("google", "org.apache.gravitino.shaded.google")
  relocate("org.apache.hc", "org.apache.gravitino.shaded.org.apache.hc")
}

tasks.jar {
  dependsOn(tasks.named("shadowJar"))
  archiveClassifier.set("thin")
}
