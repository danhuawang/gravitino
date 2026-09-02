/*
 * Copyright 2026 Datastrato Inc.
 */

plugins {
  `maven-publish`
  id("java")
  id("idea")
}

// HTTP-layer deps compile against Jetty 11 / Jersey 3 / SCIMple, but must not land on the main
// Gravitino (Jetty 9 / Jersey 2) runtime classpath. Production and ITs load them only through
// ScimAuxClassLoaders from distribution/package/scim-server/libs.
val scimServerLib by configurations.creating {
  description = "SCIMple stack for scim-server/libs; loaded at runtime by ScimAuxClassLoaders"
  isCanBeConsumed = false
  isCanBeResolved = true
  isTransitive = true
}

dependencies {
  annotationProcessor(libs.lombok)
  compileOnly(libs.lombok)
  compileOnly(libs.slf4j.api)

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
  implementation(libs.bundles.metrics)

  // Compile + package the SCIM HTTP stack once; runtime only via scim-server/libs.
  val scimHttpCompileDeps =
    listOf(
      libs.scim.core,
      libs.scim.server,
      libs.scim.spec.schema,
      libs.scim.spec.protocol,
      libs.jetty11.server,
      libs.jetty11.servlet,
      libs.jakarta.servlet6.api,
      libs.jersey3.server,
      libs.jersey3.container.servlet,
      libs.jersey3.hk2,
      libs.jakarta.ws.rs3.api,
      // Needed to compile subclasses of SCIMple *ResourceImpl (@ApplicationScoped on superclass).
      libs.jakarta.cdi.api,
      libs.jackson.jakarta.rs.json.provider,
      libs.metrics.jersey2
    )
  scimHttpCompileDeps.forEach { compileOnly(it) }

  val scimHttpRuntimeLibs =
    listOf(
      libs.scim.core,
      libs.scim.server,
      libs.scim.spec.schema,
      libs.scim.spec.protocol,
      libs.jersey3.server,
      libs.jersey3.common,
      libs.jersey3.client,
      libs.jersey3.container.servlet,
      libs.jersey3.hk2,
      libs.jakarta.ws.rs3.api,
      libs.jakarta.inject.api,
      libs.jakarta.servlet6.api,
      libs.jetty11.server,
      libs.jetty11.servlet,
      libs.jackson.jakarta.rs.json.provider
    )
  scimHttpRuntimeLibs.forEach { scimServerLib(it) }

  testImplementation(project(":integration-test-common", "testArtifacts"))
  testImplementation(libs.h2db)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testImplementation(libs.mockito.inline)
  testImplementation(libs.mysql.driver)
  testImplementation(libs.postgresql.driver)
  testImplementation(libs.testcontainers)
  testImplementation(libs.testcontainers.mysql)
  testImplementation(libs.testcontainers.postgresql)
  // Adapter / filter / servlet unit tests need SCIMple + Jakarta at compile+runtime.
  listOf(
    libs.scim.core,
    libs.scim.server,
    libs.scim.spec.schema,
    libs.scim.spec.protocol,
    libs.jakarta.servlet6.api,
    libs.jakarta.ws.rs3.api,
    libs.jakarta.cdi.api,
    // RuntimeDelegate for jakarta.ws.rs.core.Response in unit tests.
    libs.jersey3.common,
    libs.jersey3.server
  )
    .forEach { testImplementation(it) }

  testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks {
  jar {
    archiveBaseName.set("gravitino-scim-service")
  }

  val cleanScimServerLibs by registering(Delete::class) {
    delete(
      rootProject.fileTree("distribution/package/scim-server/libs") {
        include("gravitino-scim-service-*.jar")
      }
    )
  }

  val copyLibs by registering(Copy::class) {
    dependsOn(jar, cleanScimServerLibs)
    from(jar)
    from(scimServerLib) {
      // Shared with the main server; ScimAuxClassLoaders delegates these to the Gravitino bridge.
      exclude("slf4j-api-*.jar")
      exclude("commons-lang3-*.jar")
      exclude("jackson-core-*.jar")
      exclude("jackson-databind-*.jar")
      exclude("jackson-annotations-*.jar")
      exclude("javax.servlet-api-*.jar")
    }
    into("$rootDir/distribution/package/scim-server/libs")
    // Overwrite same-named jars so self-hosted CI workspaces never keep a stale service jar.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
  }

  register("copyLibAndConfigs", Copy::class) {
    group = "gravitino distribution"
    description = "Copy scim-server isolated libs into distribution package"
    dependsOn(copyLibs)
  }

  test {
    dependsOn("copyLibAndConfigs")
  }
}
