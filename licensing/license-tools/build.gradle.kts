/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  id("java")
  alias(libs.plugins.shadow)
}

dependencies {
  implementation(project(":licensing:license-client"))
  implementation(libs.bundles.log4j)
  implementation(libs.commons.cli.new)
  implementation(libs.google.cloud.kms)

  testImplementation(libs.junit.jupiter.api)
  testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.withType<ShadowJar>(ShadowJar::class.java) {
  isZip64 = true
  configurations = listOf(project.configurations.runtimeClasspath.get())
  archiveClassifier.set("")
  manifest {
    attributes["Main-Class"] = "com.datastrato.gravitino.tools.license.LicenseToolsCli"
  }
  mergeServiceFiles()
}

tasks.jar {
  dependsOn(tasks.named("shadowJar"))
  archiveClassifier.set("empty")
}
