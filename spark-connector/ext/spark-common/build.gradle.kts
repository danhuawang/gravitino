/*
 * Copyright 2026 Datastrato Inc.
 */

evaluationDependsOn(":spark-connector:spark-common")

plugins {
  `maven-publish`
  id("java")
  id("idea")
}

repositories {
  mavenCentral()
}

val scalaVersion: String = project.properties["scalaVersion"] as? String ?: extra["defaultScalaVersion"].toString()
val sparkVersion: String = libs.versions.spark33.get()
val artifactName = "datastrato-spark-connector-ext-common_$scalaVersion"

group = "com.datastrato"

// The unit and integration tests reuse the open source connector's test classpath.
val connectorTestClasspath = project(":spark-connector:spark-common").the<SourceSetContainer>()["test"].runtimeClasspath

dependencies {
  compileOnly(project(":spark-connector:spark-common"))
  compileOnly(project(":clients:client-java-runtime", configuration = "shadow"))
  compileOnly("org.apache.spark:spark-catalyst_$scalaVersion:$sparkVersion")
  compileOnly("org.apache.spark:spark-core_$scalaVersion:$sparkVersion")
  compileOnly("org.apache.spark:spark-sql_$scalaVersion:$sparkVersion")

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
