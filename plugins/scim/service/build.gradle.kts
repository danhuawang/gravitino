/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

plugins {
  `maven-publish`
  id("java")
  id("idea")
}

val scimServerLib by configurations.creating {
  description =
    "SCIMple stack for scim-server/libs; transitives exclude jars already on the main server classpath"
  isCanBeConsumed = false
  isCanBeResolved = true
  isTransitive = true
}

dependencies {
  annotationProcessor(libs.lombok)

  implementation(project(":api"))
  implementation(project(":common"))
  implementation(project(":core"))
  implementation(project(":plugins:scim"))

  implementation(libs.commons.lang3)
  implementation(libs.guava)
  implementation(libs.jackson.databind)
  implementation(libs.scim.core)
  implementation(libs.scim.spec.schema)

  // SCIMple + its non-server transitives. Gravitino server jars remain in distribution/package/libs
  // and resolve through IsolatedClassLoader parent delegation (IsolatedClassLoader#isSharedClass).
  scimServerLib(libs.scim.core)

  compileOnly(libs.lombok)
  compileOnly(libs.slf4j.api)

  testImplementation(project(":integration-test-common", "testArtifacts"))

  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testImplementation(libs.mockito.inline)
  testImplementation(libs.mysql.driver)
  testImplementation(libs.postgresql.driver)
  testImplementation(libs.testcontainers)
  testImplementation(libs.testcontainers.mysql)
  testImplementation(libs.testcontainers.postgresql)

  testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks {
  jar {
    archiveBaseName.set("gravitino-scim-service")
  }

  register("copyLibs", Copy::class) {
    dependsOn(jar)
    from(jar)
    from(scimServerLib) {
      // Already on the main Gravitino server classpath; IsolatedClassLoader delegates shared classes.
      exclude("slf4j-api-*.jar")
      exclude("commons-lang3-*.jar")
      exclude("jackson-core-*.jar")
      exclude("jackson-databind-*.jar")
      exclude("jackson-annotations-*.jar")
    }
    into("$rootDir/distribution/package/scim-server/libs")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  }

  register("copyLibAndConfigs", Copy::class) {
    group = "gravitino distribution"
    description = "Copy scim-server isolated libs into distribution package"
    dependsOn("copyLibs")
  }

  test {
    dependsOn("copyLibAndConfigs")
    environment("GRAVITINO_HOME", rootDir.path)

    val skipITs = project.hasProperty("skipITs")
    if (skipITs) {
      exclude("**/integration/test/**")
    }
  }
}
