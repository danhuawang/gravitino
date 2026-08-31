/*
 * Copyright 2026 Datastrato Inc.
 */
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  `maven-publish`
  id("java")
  alias(libs.plugins.shadow)
}

dependencies {
  implementation(project(":api")) {
    exclude(group = "*")
  }
  implementation(project(":common")) {
    exclude(group = "*")
  }
  implementation(project(":bundles:vault-compatible:transit")) {
    isTransitive = false
  }
  implementation(libs.httpclient5)
  implementation(libs.jackson.databind)

  testImplementation(testFixtures(project(":common")))
  testImplementation(testFixtures(project(":bundles:vault-compatible:transit")))
  testImplementation(libs.junit.jupiter.api)
  testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
  if (project.hasProperty("skipITs")) {
    exclude("**/integration/test/**")
  } else {
    environment("GRAVITINO_TRANSIT_IT_TOKEN", "gravitino-kms-test-root-token")
    environment("GRAVITINO_TRANSIT_IT_INVALID_TOKEN", "invalid-transit-test-token")
  }
}

tasks.withType(ShadowJar::class.java) {
  isZip64 = true
  configurations = listOf(project.configurations.runtimeClasspath.get())
  archiveClassifier.set("")

  dependencies {
    exclude(dependency("org.slf4j:slf4j-api"))
    exclude(project(":api"))
    exclude(project(":common"))
  }

  exclude("module-info.class")
  exclude("META-INF/versions/**/module-info.class")

  val shadeRoot = "com.datastrato.gravitino.transit.vault.shaded"
  relocate(
    "com.datastrato.gravitino.transit.common",
    "$shadeRoot.com.datastrato.gravitino.transit.common"
  )
  relocate(
    "com.datastrato.gravitino.transit.kms",
    "$shadeRoot.com.datastrato.gravitino.transit.kms"
  )
  relocate("com.fasterxml.jackson", "$shadeRoot.com.fasterxml.jackson")
  relocate("org.apache.commons.codec", "$shadeRoot.org.apache.commons.codec")
  relocate("org.apache.hc", "$shadeRoot.org.apache.hc")
  relocate("org.publicsuffix", "$shadeRoot.org.publicsuffix")

  mergeServiceFiles()
}

tasks.jar {
  dependsOn(tasks.named("shadowJar"))
  archiveClassifier.set("empty")
}

tasks.javadoc {
  options.memberLevel = JavadocMemberLevel.PUBLIC
}
