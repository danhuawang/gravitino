/*
 * Copyright 2026 Datastrato Inc.
 */

import org.gradle.internal.hash.ChecksumService
import org.gradle.kotlin.dsl.support.serviceOf

plugins {
  `maven-publish`
  id("java")
  id("idea")
}

repositories {
  mavenCentral()
}

val minSupportedTrinoVersionProperty = providers.gradleProperty("minSupportedTrinoVersion")
val trinoVersionProperty = providers.gradleProperty("trinoVersion").orElse(minSupportedTrinoVersionProperty)
val trinoVersion = trinoVersionProperty.map { it.trim().toInt() }.get()
val artifactName = "datastrato-trino-connector-ext"

group = "com.datastrato.gravitino"

// The unit tests reuse the open source connector's test classpath.
val connectorTestClasspath = project(":trino-connector:trino-connector").the<SourceSetContainer>()["test"].runtimeClasspath

dependencies {
  compileOnly(project(":trino-connector:trino-connector"))
  compileOnly(project(":catalogs:catalog-common"))
  compileOnly(project(":clients:client-java-runtime", configuration = "shadow"))
  compileOnly(libs.airlift.log)
  compileOnly("io.trino:trino-spi:$trinoVersion") {
    exclude("org.apache.logging.log4j")
  }

  testImplementation(connectorTestClasspath)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testRuntimeOnly(libs.junit.jupiter.engine)
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

// The ext jar is distributed on its own; users drop it next to the open source connector jars in
// the Trino plugin directory. It works with every supported Trino version range.
tasks {
  val distributionDir = rootProject.layout.projectDirectory.dir("distribution/$artifactName")

  // Sync keeps the distribution directory free of jars from earlier builds.
  val copyLibs by registering(Sync::class) {
    from(named("jar"))
    from(rootProject.file("LICENSE-ENTERPRISE"))
    into(distributionDir)
  }

  val assembleTrinoConnectorExt by registering(Tar::class) {
    dependsOn(copyLibs)
    group = "gravitino distribution"
    finalizedBy("checksumTrinoConnectorExt")
    val archiveBase = "$artifactName-$version"
    into(archiveBase)
    from(distributionDir)
    compression = Compression.GZIP
    archiveFileName.set("$archiveBase.tar.gz")
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("distribution"))
  }

  val checksumTrinoConnectorExt by registering {
    group = "gravitino distribution"
    dependsOn(assembleTrinoConnectorExt)
    val archiveFile = assembleTrinoConnectorExt.flatMap { it.archiveFile }
    val checksumFile = archiveFile.map { archive ->
      archive.asFile.let { it.resolveSibling("${it.name}.sha256") }
    }
    inputs.file(archiveFile)
    outputs.file(checksumFile)
    doLast {
      checksumFile.get().writeText(
        serviceOf<ChecksumService>().sha256(archiveFile.get().asFile).toString()
      )
    }
  }
}
