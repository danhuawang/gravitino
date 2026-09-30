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

group = "com.datastrato"

// Trino version bands. Each band has an open source connector module
// (:trino-connector:trino-connector-<band>) whose `copyLibs` task stages the full
// plugin directory (connector jar + dependency jars + LICENSE + NOTICE + README) under
// distribution/gravitino-trino-connector-<band>/. The enterprise plugin zip for a band is
// that directory plus the enterprise ext jar and LICENSE-ENTERPRISE.
val trinoVersionBands = listOf(
  "435-439",
  "440-445",
  "446-451",
  "452-468",
  "469-472",
  "473-478"
)

// The per-band zips below reference each band module's `jar` task and `runtimeClasspath`.
// Declare the evaluation dependency explicitly so those references are a stated requirement
// rather than relying on Gradle's project evaluation order (band names happen to sort before
// this module today, but a rename or a new band could break that silently).
trinoVersionBands.forEach { band ->
  evaluationDependsOn(":trino-connector:trino-connector-$band")
}

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

  // One installable plugin zip per version band, mirroring how Trino distributes its own
  // plugins (an operator drops the unpacked directory under Trino's plugin/). Each zip is the
  // open source connector band's full plugin directory (connector jar + its dependency jars +
  // LICENSE + NOTICE + README) with the enterprise ext jar and LICENSE-ENTERPRISE added, so it
  // loads on its own in Trino's per-plugin classloader. This is the same layout the enterprise
  // connector Docker image assembles (dev/docker/trino-connectors-enterprise).
  trinoVersionBands.forEach { band ->
    val ossBandProject = project(":trino-connector:trino-connector-$band")
    // Assemble the zip from the band's jar and its resolved runtimeClasspath directly, rather
    // than from the band's build/libs or distribution directory. Those directories are staged
    // with a plain Copy and are mounted as-is by the integration tests, so on an unclean tree
    // they can still hold jars from an earlier build (an old connector or dependency version).
    // Sourcing the exact jar task output plus the freshly resolved runtimeClasspath keeps a
    // stale artifact out of the published, immutable Central zip.
    val ossJar = ossBandProject.tasks.named("jar")
    val ossRuntimeClasspath = ossBandProject.configurations.named("runtimeClasspath")
    // Non-jar plugin files the band's copyLibs also stages, mirrored here for parity with the
    // Docker image and the downloads-page tarball layout.
    val bandLicenses = rootProject.layout.projectDirectory.dir("licenses")
    val bandLicenseFile = rootProject.file("LICENSE.trino")
    val bandNoticeFile = rootProject.file("NOTICE.trino")
    val bandReadmeFile = rootProject.file("README.md")

    register("zipTrinoConnectorExt-$band", Zip::class) {
      group = "gravitino distribution"
      dependsOn(named("jar"))
      val archiveBase = "$artifactName-$band-$version"
      into(archiveBase) {
        // Open source connector jar and its runtime dependency jars.
        from(ossJar)
        from(ossRuntimeClasspath.map { cfg -> cfg.filter(File::isFile) })
        // License files, matching the band's distribution layout.
        from(bandLicenses) { into("licenses") }
        from(bandLicenseFile) { rename { it.replace(".trino", "") } }
        from(bandNoticeFile) { rename { it.replace(".trino", "") } }
        from(bandReadmeFile)
        // Enterprise adapters (Oracle / SQL Server); discovered from the same plugin dir.
        from(named("jar"))
        // Enterprise proprietary license addendum.
        from(rootProject.file("LICENSE-ENTERPRISE"))
      }
      archiveFileName.set("$archiveBase.zip")
      destinationDirectory.set(rootProject.layout.projectDirectory.dir("distribution"))
    }
  }

  // Convenience aggregate that builds every band zip.
  register("zipTrinoConnectorExtAll") {
    group = "gravitino distribution"
    dependsOn(trinoVersionBands.map { named("zipTrinoConnectorExt-$it") })
  }
}

// Gradle Module Metadata describes the jar's variants/dependencies, which do not
// match the zip assemblies we publish. Disable it so the zip artifacts and the
// hand-written POMs are the source of truth.
tasks.withType<GenerateModuleMetadata> {
  enabled = false
}

publishing {
  publications {
    // The root convention (see build.gradle.kts) creates a default "MavenJava" publication
    // from the java component. The ext jar is not a standalone library and is not published on
    // its own; the installable plugin ships as the per-band zips below. Empty that publication
    // and skip its publish/sign tasks so no bare artifact reaches the repository.
    withType<MavenPublication>().matching { it.name == "MavenJava" }.configureEach {
      setArtifacts(emptyList<Any>())
      artifactId = artifactName
    }

    // Publish the Trino plugin assembly as one zip per version band (packaging=zip), the same
    // artifact shape Trino uses for its own plugins. Coordinates:
    //   com.datastrato:datastrato-trino-connector-ext-<band>:<version>:zip
    trinoVersionBands.forEach { band ->
      create<MavenPublication>("trinoConnectorExt${band.replace("-", "_")}") {
        groupId = "com.datastrato"
        artifactId = "$artifactName-$band"
        artifact(tasks.named("zipTrinoConnectorExt-$band")) {
          extension = "zip"
        }
        artifact(tasks.named("sourcesJar"))
        artifact(tasks.named("javadocJar"))
        pom {
          packaging = "zip"
          name.set("Datastrato Enterprise Trino Connector Plugin ($band)")
          description.set(
            "Installable Trino plugin for the Datastrato Enterprise Trino connector, " +
              "Trino/Starburst version band $band. Unpacks to a single plugin directory " +
              "containing the connector jar, its dependencies, the enterprise adapters " +
              "(Oracle / SQL Server), and license files."
          )
          url.set("https://datastrato.ai/")
          licenses {
            license {
              name.set("Datastrato Enterprise - Proprietary Components")
              url.set("https://github.com/datastrato/gravitino-enterprise/blob/main/LICENSE-ENTERPRISE")
            }
          }
          developers {
            developer {
              id.set("Datastrato")
              name.set("Datastrato Inc.")
              email.set("contact@datastrato.com")
            }
          }
          scm {
            url.set("https://github.com/datastrato/gravitino-enterprise")
            connection.set("scm:git:git://github.com/datastrato/gravitino-enterprise.git")
          }
          // The zip is a self-contained plugin directory, not a library: it has no Maven
          // dependencies. Strip any that the java component contributed to the POM.
          withXml {
            val root = asNode()
            (root.get("dependencies") as groovy.util.NodeList).forEach { root.remove(it as groovy.util.Node) }
          }
        }
      }
    }
  }
}

// Do not publish the empty default "MavenJava" publication; the installable plugin is shipped
// as the six per-band zip publications above. Disabling its publish/sign tasks keeps a bare
// marker POM out of the repository.
tasks.matching {
  it.name.startsWith("publishMavenJavaPublication") || it.name == "signMavenJavaPublication"
}.configureEach {
  enabled = false
}
