/*
 * Copyright 2026 Datastrato Inc.
 *
 * The open source connector jars carry no Enterprise code or license, and the
 * Enterprise ext jars carry no ASF code.
 *
 * Who: the published jars of the open source connector runtimes, the Java
 * client, and the Enterprise ext modules (picked by project name below).
 * Pass: an open source jar has no META-INF/LICENSE-ENTERPRISE and no
 * com/datastrato/ entry; an ext jar has META-INF/LICENSE-ENTERPRISE, at least
 * one com/datastrato/ class, a META-INF/services/ registration and no
 * org/apache/gravitino/ class.
 * Fail: any entry above is present or missing, or a listed project produced
 * no jar to check.
 */

import java.util.zip.ZipFile

// Projects are picked by the naming convention in settings.gradle.kts, so a new connector
// version or ext module is scanned without touching this list. Projects excluded by a build
// property (skipTrinoConnector, scalaVersion) are absent and simply skipped.
val openSourceJarProjectNames =
  listOf(
    Regex("client-java(-runtime)?"),
    Regex("(spark|flink)-runtime-\\d.*"),
    Regex("trino-connector-\\d+-\\d+")
  )
val enterpriseExtJarProjectNames = listOf(Regex("(spark|flink)-ext-\\d.*"), Regex("trino-connector-ext"))

val enterpriseOnlyEntries = listOf("META-INF/LICENSE-ENTERPRISE", "com/datastrato/")

fun projectsNamed(patterns: List<Regex>): List<Project> =
  subprojects.filter { p -> patterns.any { it.matches(p.name) } }.sortedBy { it.path }

fun jarTasks(project: Project) = project.tasks.matching { it.name == "jar" || it.name == "shadowJar" }

// The jars a project publishes: the outputs of its jar/shadowJar tasks minus classifier jars.
fun publishedJars(project: Project): List<File> =
  jarTasks(project).flatMap { it.outputs.files.files }
    .filter { it.name.endsWith(".jar") }
    .filterNot { jar ->
      listOf("-sources", "-javadoc", "-tests", "-empty", "-thin").any { jar.name.endsWith("$it.jar") }
    }

tasks.register("checkConnectorJars") {
  group = "verification"
  description = "Checks that open source connector jars carry no Enterprise code and ext jars carry no ASF code."
  val openSourceProjects = projectsNamed(openSourceJarProjectNames)
  val extProjects = projectsNamed(enterpriseExtJarProjectNames)
  (openSourceProjects + extProjects).forEach { jarProject ->
    dependsOn(jarTasks(jarProject))
    inputs.files(jarTasks(jarProject))
  }
  val report = layout.buildDirectory.file("reports/connector-jars.txt")
  outputs.file(report)
  doLast {
    val violations = mutableListOf<String>()
    val checkedJars = mutableListOf<String>()
    fun jarsOf(jarProject: Project): List<File> {
      val jars = publishedJars(jarProject).filter { it.isFile }
      if (jars.isEmpty()) {
        violations += "${jarProject.path}: no jar found to check"
      }
      checkedJars += jars.map { it.name }
      return jars
    }
    openSourceProjects.flatMap(::jarsOf).forEach { jar ->
      ZipFile(jar).use { zip ->
        zip.entries().asSequence().map { it.name }.forEach { name ->
          if (enterpriseOnlyEntries.any { name.contains(it) }) {
            violations += "${jar.name}: open source jar contains $name"
          }
        }
      }
    }
    extProjects.flatMap(::jarsOf).forEach { jar ->
      ZipFile(jar).use { zip ->
        val names = zip.entries().asSequence().map { it.name }.toList()
        if ("META-INF/LICENSE-ENTERPRISE" !in names) {
          violations += "${jar.name}: ext jar lacks META-INF/LICENSE-ENTERPRISE"
        }
        if (names.none { it.startsWith("com/datastrato/") && it.endsWith(".class") }) {
          violations += "${jar.name}: ext jar contains no com/datastrato/ class"
        }
        if (names.none { it.startsWith("META-INF/services/") && !it.endsWith("/") }) {
          violations += "${jar.name}: ext jar registers no META-INF/services/ provider"
        }
        names.filter { it.startsWith("org/apache/gravitino/") && it.endsWith(".class") }.forEach { name ->
          violations += "${jar.name}: ext jar contains $name"
        }
      }
    }
    if (violations.isNotEmpty()) {
      throw GradleException("Connector jar check failed:\n" + violations.joinToString("\n"))
    }
    report.get().asFile.writeText(checkedJars.sorted().joinToString("\n", postfix = "\n"))
    logger.lifecycle("Checked connector jars: ${checkedJars.sorted().joinToString(", ")}")
  }
}
