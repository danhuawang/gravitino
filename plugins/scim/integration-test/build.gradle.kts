/*
 * Copyright 2026 Datastrato Inc.
 */

plugins {
  `maven-publish`
  id("java")
  id("idea")
}

dependencies {
  testImplementation(project(":api"))
  testImplementation(project(":clients:client-java"))
  testImplementation(project(":common"))
  testImplementation(project(":core"))
  testImplementation(project(":plugins:scim"))
  testImplementation(project(":plugins:scim", "testArtifacts"))
  testImplementation(project(":plugins:scim:service"))
  testImplementation(project(":integration-test-common", "testArtifacts"))
  testImplementation(project(":server"))
  testImplementation(project(":server-common"))

  // MiniGravitino stays on Jersey 2 / Jetty 9; SCIM HTTP stack loads from scim-server/libs.
  testImplementation(libs.javax.ws.rs.api)
  testImplementation(libs.bundles.jersey)
  testImplementation(libs.servlet)
  testImplementation(libs.awaitility)
  testImplementation(libs.commons.io)
  testImplementation(libs.commons.lang3)
  testImplementation(libs.guava)
  testImplementation(libs.javax.jaxb.api)
  testImplementation(libs.mybatis)
  testImplementation(libs.h2db)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.mysql.driver)
  testImplementation(libs.postgresql.driver)
  testImplementation(libs.testcontainers)
  testImplementation(libs.testcontainers.mysql)
  testImplementation(libs.testcontainers.postgresql)

  testRuntimeOnly(libs.junit.jupiter.engine)
}

configurations.configureEach {
  if (name == "testRuntimeClasspath" || name == "testCompileClasspath") {
    val jerseyVersion = libs.versions.jersey.get()
    val jettyVersion = libs.versions.jetty.get()
    resolutionStrategy {
      force(libs.javax.ws.rs.api)
      force("org.glassfish.jersey.core:jersey-server:$jerseyVersion")
      force("org.glassfish.jersey.core:jersey-common:$jerseyVersion")
      force("org.glassfish.jersey.core:jersey-client:$jerseyVersion")
      force("org.glassfish.jersey.containers:jersey-container-servlet-core:$jerseyVersion")
      force("org.glassfish.jersey.containers:jersey-container-jetty-http:$jerseyVersion")
      force("org.glassfish.jersey.media:jersey-media-json-jackson:$jerseyVersion")
      force("org.glassfish.jersey.inject:jersey-hk2:$jerseyVersion")
      force("org.eclipse.jetty:jetty-server:$jettyVersion")
      force("org.eclipse.jetty:jetty-servlet:$jettyVersion")
      force("org.eclipse.jetty:jetty-servlets:$jettyVersion")
      force("org.eclipse.jetty:jetty-webapp:$jettyVersion")
    }
  }
}

tasks.test {
  description =
    "Run SCIM service REST integration tests against the Jersey 3 auxiliary listener"
  group = "verification"

  val skipITs = project.hasProperty("skipITs")
  if (skipITs) {
    exclude("*")
  } else {
    dependsOn(":plugins:scim:service:copyLibAndConfigs")
    dependsOn(":plugins:scim:copyLibAndConfigs")
  }

  useJUnitPlatform()
}
