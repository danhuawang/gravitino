/*
 * Copyright 2024 Datastrato Inc.
 */
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  id("java")
  alias(libs.plugins.shadow)
}

dependencies {
  implementation(project(":licensing:license-client")) {
    exclude(group = "*")
  }
  implementation(libs.commons.cli.new)
  implementation(libs.commons.lang3)
  implementation(libs.google.cloud.kms)

  testImplementation(libs.junit.jupiter.api)
  testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.withType<ShadowJar>(ShadowJar::class.java) {
  isZip64 = true
  configurations = listOf(project.configurations.runtimeClasspath.get())
  archiveClassifier.set("")
  manifest {
    attributes["Main-Class"] = "com.datastrato.gravitino.license.tools.LicenseToolsCli"
  }
  mergeServiceFiles()

  // Exclude any remaining server/runtime artifacts that should not be in the CLI fat jar
  exclude("org/apache/kerby/**")
  exclude("org/apache/arrow/**")
  exclude("org/casbin/**")
  exclude("io/netty/**")
  exclude("com/google/flatbuffers/**")
  exclude("ognl/**")
  exclude("org/jline/**")
}

tasks.jar {
  dependsOn(tasks.named("shadowJar"))
  archiveClassifier.set("empty")
}
