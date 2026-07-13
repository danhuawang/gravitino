/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

plugins {
  `maven-publish`
  id("java")
  id("idea")
}

// HTTP-layer deps are declared here so this PR stays scoped to the SCIM module only.
val scimpleVersion = libs.versions.scimple.get()
val jetty11Version = "11.0.24"
val jersey3Version = "3.1.5"
val jakartaServletVersion = "6.0.0"
val jakartaWsRsVersion = "3.1.0"
val jacksonJakartaRsVersion = "2.16.1"

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
  implementation(project(":server-common")) {
    exclude(group = "org.glassfish.jersey")
  }

  implementation(libs.commons.lang3)
  implementation(libs.guava)
  implementation(libs.jackson.databind)
  implementation(libs.scim.core)
  implementation("org.apache.directory.scimple:scim-server:$scimpleVersion")
  implementation(libs.scim.spec.schema)
  implementation("org.apache.directory.scimple:scim-spec-protocol:$scimpleVersion")
  implementation("org.eclipse.jetty:jetty-server:$jetty11Version")
  implementation("org.eclipse.jetty:jetty-servlet:$jetty11Version")
  implementation("jakarta.servlet:jakarta.servlet-api:$jakartaServletVersion")
  implementation("org.glassfish.jersey.core:jersey-server:$jersey3Version")
  implementation("org.glassfish.jersey.containers:jersey-container-servlet:$jersey3Version")
  implementation("org.glassfish.jersey.inject:jersey-hk2:$jersey3Version")
  implementation("jakarta.ws.rs:jakarta.ws.rs-api:$jakartaWsRsVersion")
  implementation(libs.bundles.metrics)
  implementation(libs.metrics.jersey2)

  scimServerLib("org.apache.directory.scimple:scim-server:$scimpleVersion")
  scimServerLib(libs.scim.core)
  scimServerLib(libs.scim.spec.schema)
  scimServerLib("org.apache.directory.scimple:scim-spec-protocol:$scimpleVersion")
  scimServerLib("org.glassfish.jersey.core:jersey-server:$jersey3Version")
  scimServerLib("org.glassfish.jersey.core:jersey-common:$jersey3Version")
  scimServerLib("org.glassfish.jersey.core:jersey-client:$jersey3Version")
  scimServerLib("org.glassfish.jersey.containers:jersey-container-servlet:$jersey3Version")
  scimServerLib("org.glassfish.jersey.inject:jersey-hk2:$jersey3Version")
  scimServerLib("jakarta.ws.rs:jakarta.ws.rs-api:$jakartaWsRsVersion")
  scimServerLib("jakarta.inject:jakarta.inject-api:2.0.1")
  scimServerLib("jakarta.servlet:jakarta.servlet-api:$jakartaServletVersion")
  scimServerLib("org.eclipse.jetty:jetty-server:$jetty11Version")
  scimServerLib("org.eclipse.jetty:jetty-servlet:$jetty11Version")
  scimServerLib(
    "com.fasterxml.jackson.jakarta.rs:jackson-jakarta-rs-json-provider:$jacksonJakartaRsVersion"
  )

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
      exclude("javax.servlet-api-*.jar")
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
