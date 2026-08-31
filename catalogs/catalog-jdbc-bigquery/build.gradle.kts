/*
 * Copyright 2026 Datastrato Inc.
 */

description = "catalog-jdbc-bigquery"

plugins {
  `maven-publish`
  id("java")
  id("idea")
  id("de.undercouch.download") version "5.4.0"
}

val simbaDriverVersion = libs.versions.simba.bigquery.jdbc.get()
val simbaDriverUrl = "https://storage.googleapis.com/simba-bq-release/jdbc/SimbaJDBCDriverforGoogleBigQuery42_$simbaDriverVersion.zip"

val simbaDownloadDir = layout.buildDirectory.dir("downloads").get()
val simbaExtractDir = layout.buildDirectory.dir("extracted/simba-driver").get()

val simbaZipFile = simbaDownloadDir.file("simba-driver-$simbaDriverVersion.zip").asFile

val downloadSimbaDriver by tasks.registering(de.undercouch.gradle.tasks.download.Download::class) {
  src(simbaDriverUrl)
  dest(simbaZipFile)
  overwrite(false)
  onlyIfModified(true)

  doFirst {
    logger.lifecycle("Downloading Simba BigQuery JDBC driver: v$simbaDriverVersion...")
  }

  doLast {
    val fileSize = simbaZipFile.length() / 1024 / 1024
    logger.lifecycle("Download completed. File size: ${fileSize}MB")
  }
}

val extractSimbaDriver by tasks.registering(Copy::class) {
  dependsOn(downloadSimbaDriver)

  from(zipTree(simbaZipFile))
  into(simbaExtractDir)
  include("**/*.jar")
  exclude("**/jackson-*.jar")
  exclude("**/src/", "**/doc/", "**/samples/", "**/legal/")

  rename("GoogleBigQueryJDBC42.jar", "GoogleBigQueryJDBC42-simba.jar")

  doFirst {
    logger.lifecycle("Decompressing Simba JDBC driver...")
  }

  doLast {
    val jarFiles = fileTree(simbaExtractDir).matching { include("*.jar") }.files
    logger.lifecycle("Found ${jarFiles.size} JAR files:")
    jarFiles.forEach { jar ->
      logger.lifecycle("  - ${jar.name} (${jar.length() / 1024}KB)")
    }
  }
}

val cleanSimbaDriver by tasks.registering(Delete::class) {
  delete(simbaDownloadDir, simbaExtractDir)
}

tasks.clean {
  dependsOn(cleanSimbaDriver)
}

tasks.withType<JavaCompile> {
  dependsOn(extractSimbaDriver)
}

tasks.test {
  dependsOn(extractSimbaDriver)
}

dependencies {
  implementation(project(":api")) {
    exclude(group = "*")
  }
  implementation(project(":catalogs:catalog-common")) {
    exclude(group = "*")
  }
  implementation(project(":catalogs:catalog-jdbc-common")) {
    exclude(group = "*")
  }
  implementation(project(":common")) {
    exclude(group = "*")
  }
  implementation(project(":core")) {
    exclude(group = "*")
  }

  implementation(libs.bundles.log4j)
  implementation(libs.commons.collections4)
  implementation(libs.commons.lang3)
  implementation(libs.guava)
  implementation(libs.commons.compress)
  implementation(libs.jackson.databind)

  testImplementation(project(":catalogs:catalog-jdbc-common", "testArtifacts"))
  testImplementation(project(":clients:client-java"))
  testImplementation(project(":integration-test-common", "testArtifacts"))
  testImplementation(project(":server"))
  testImplementation(project(":server-common"))

  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testImplementation(libs.testcontainers)
  testImplementation(libs.mockito.core)
  testImplementation(libs.awaitility)

  // Simba JDBC driver for compile and test only
  // Users must download and install the driver manually in production
  val simbaJdbcDriver = files(
    simbaExtractDir.asFileTree.matching {
      include("*.jar")
    }
  )
  compileOnly(simbaJdbcDriver)
  testImplementation(simbaJdbcDriver)

  testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks {
  val runtimeJars by registering(Copy::class) {
    dependsOn("jar")
    from(configurations.runtimeClasspath)
    into("build/libs")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  }

  val copyCatalogLibs by registering(Copy::class) {
    dependsOn("jar", "runtimeJars")
    from("build/libs") {
      exclude("guava-*.jar")
      exclude("log4j-*.jar")
      exclude("slf4j-*.jar")
    }
    into("$rootDir/distribution/package/catalogs/jdbc-bigquery/libs")

    doLast {
      logger.lifecycle("The BigQuery JDBC Catalog library file has been copied to the distribution directory.")
    }
  }

  val copyCatalogConfig by registering(Copy::class) {
    from("src/main/resources")
    into("$rootDir/distribution/package/catalogs/jdbc-bigquery/conf")

    include("jdbc-bigquery.conf")

    exclude { details ->
      details.file.isDirectory()
    }

    fileMode = 0b111101101

    doLast {
      logger.lifecycle("BigQuery JDBC configuration file copied.")
    }
  }

  register("copyLibAndConfig", Copy::class) {
    dependsOn(copyCatalogLibs, copyCatalogConfig)
  }
}

tasks.test {
  val skipITs = project.hasProperty("skipITs")
  if (skipITs) {
    // Exclude integration tests
    exclude("**/integration/**")
  } else {
    dependsOn(tasks.jar)
  }
  dependsOn(extractSimbaDriver)
  environment("SIMBA_JDBC_DRIVER_PATH", simbaExtractDir.asFile.absolutePath)
}

tasks.getByName("generateMetadataFileForMavenJavaPublication") {
  dependsOn("runtimeJars")
}
