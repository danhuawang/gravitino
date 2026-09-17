/*
 * Copyright 2026 Datastrato Inc.
 */

evaluationDependsOn(":flink-connector:flink-common")

plugins {
  `maven-publish`
  id("java")
  id("idea")
}

repositories {
  mavenCentral()
}

val flinkVersion: String = libs.versions.flink18.get()
val artifactName = "datastrato-flink-connector-ext-common"

group = "com.datastrato.gravitino"

// The unit and integration tests reuse the open source connector's test classpath.
val connectorTestClasspath = project(":flink-connector:flink-common").the<SourceSetContainer>()["test"].runtimeClasspath

dependencies {
  compileOnly(project(":flink-connector:flink-common"))
  compileOnly(project(":clients:client-java-runtime", configuration = "shadow"))
  compileOnly("org.apache.flink:flink-table-common:$flinkVersion")
  compileOnly("org.apache.flink:flink-table-api-java:$flinkVersion")
  compileOnly(libs.flinkjdbc18)

  testImplementation(connectorTestClasspath)
  testImplementation(libs.oracle.driver)
  testImplementation(libs.mssql.jdbc)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
  if (project.hasProperty("skipITs")) {
    exclude("**/integration/test/**")
  }
}

tasks.withType<Jar> {
  archiveBaseName.set(artifactName)
}

publishing {
  publications {
    withType<MavenPublication>().configureEach {
      artifactId = artifactName
    }
  }
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
