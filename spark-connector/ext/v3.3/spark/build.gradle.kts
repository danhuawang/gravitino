/*
 * Copyright 2026 Datastrato Inc.
 */

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

evaluationDependsOn(":spark-connector:spark-3.3")

plugins {
  `maven-publish`
  `java-library`
  id("idea")
  alias(libs.plugins.shadow)
}

repositories {
  mavenCentral()
}

val scalaVersion: String = project.properties["scalaVersion"] as? String ?: extra["defaultScalaVersion"].toString()
val sparkVersion: String = libs.versions.spark33.get()
val sparkMajorVersion: String = sparkVersion.substringBeforeLast(".")
val artifactName by extra("datastrato-spark-connector-ext-${sparkMajorVersion}_$scalaVersion")

group = "com.datastrato"

// The unit and integration tests reuse the open source connector's test classpath.
val connectorTestClasspath = project(":spark-connector:spark-3.3").the<SourceSetContainer>()["test"].runtimeClasspath

dependencies {
  api(project(":spark-connector:spark-ext-common"))
  compileOnly(project(":spark-connector:spark-3.3"))
  compileOnly(project(":spark-connector:spark-common"))
  compileOnly(project(":clients:client-java-runtime", configuration = "shadow"))
  compileOnly("org.apache.spark:spark-catalyst_$scalaVersion:$sparkVersion")
  compileOnly("org.apache.spark:spark-core_$scalaVersion:$sparkVersion")
  compileOnly("org.apache.spark:spark-sql_$scalaVersion:$sparkVersion")

  testImplementation(connectorTestClasspath)
  testImplementation(project(":spark-connector:spark-ext-common", "testArtifacts"))
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
    dependsOn(":catalogs:catalog-lakehouse-iceberg:jar")
    dependsOn(":catalogs:catalog-hive:jar")
    dependsOn(":iceberg:iceberg-rest-server:jar")
    dependsOn(":lance:lance-rest-server:jar")
    dependsOn(":catalogs:catalog-lakehouse-paimon:jar")
    dependsOn(":catalogs:catalog-jdbc-mysql:jar")
    dependsOn(":catalogs:catalog-jdbc-oracle:jar")
    dependsOn(":catalogs:catalog-jdbc-postgresql:jar")
    dependsOn(":catalogs:catalog-jdbc-sqlserver:jar")
  }
}

tasks.withType<Jar> {
  archiveBaseName.set(artifactName)
}

// The published artifact bundles only the ext modules; the open source connector runtime jar
// stays a separate, unmodified dependency. Relocations mirror spark-runtime so classes here
// link against the same shaded packages at runtime.
tasks.withType<ShadowJar> {
  isZip64 = true
  configurations = listOf(project.configurations.runtimeClasspath.get())
  archiveFileName.set("$artifactName-$version.jar")
  archiveClassifier.set("")
  dependencies {
    include(project(":spark-connector:spark-ext-common"))
    include(project(":spark-connector:spark-ext-3.3"))
  }
  mergeServiceFiles()

  relocate("com.google", "org.apache.gravitino.shaded.com.google")
  relocate("google", "org.apache.gravitino.shaded.google")
  relocate("org.apache.hc", "org.apache.gravitino.shaded.org.apache.hc")
  relocate("com.github.benmanes.caffeine", "org.apache.gravitino.shaded.com.github.benmanes.caffeine")
}

tasks.jar {
  dependsOn(tasks.named("shadowJar"))
  archiveClassifier.set("thin")
}

tasks.clean {
  delete("derby.log")
  delete("metastore_db")
  delete("spark-warehouse")
}
