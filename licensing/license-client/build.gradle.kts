/*
 * Copyright 2024 Datastrato Inc.
 */

import java.net.HttpURLConnection
import java.net.URL

plugins {
  `maven-publish`
  id("java")
  id("idea")
}

dependencies {
  implementation(project(":api"))
  implementation(project(":core"))
  implementation(project(":common"))
  implementation(project(":server-common"))

  implementation(libs.bundles.jersey)
  implementation(libs.bundles.log4j)
  implementation(libs.commons.lang3)
  implementation(libs.guava)
  implementation(libs.jackson.annotations)
  implementation(libs.jackson.databind)
  implementation(libs.lombok)
  implementation(libs.mybatis)

  annotationProcessor(libs.lombok)

  testImplementation(libs.jersey.test.framework.core) {
    exclude(group = "org.junit.jupiter")
  }
  testImplementation(libs.jersey.test.framework.provider.jetty) {
    exclude(group = "org.junit.jupiter")
  }
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.mockito.core)
  testImplementation(libs.mockito.inline)
  testImplementation(libs.commons.io)

  testRuntimeOnly(libs.junit.jupiter.engine)
}

// Downloads the ECDSA public key from a private GitHub repo at build time and embeds it in the jar.
// The public key is NOT committed to this repo — it is fetched from a dedicated private GitHub repo.
// Required env vars (set in CI):
//   LICENSE_PUBLIC_KEY_URL — raw GitHub URL, e.g.
//     https://raw.githubusercontent.com/datastrato/enterprise-license-keys/main/test/gravitino-master.pub
//   GITHUB_TOKEN           — PAT or GitHub App token with read access to the key repo
//                            (auto-injected in GitHub Actions)
// For local development, leave these unset (tests use in-memory keys via TestKeyPairUtil).
// In GitHub Actions, empty values fail fast with guidance to open the PR from this repo
// (fork pull_request workflows receive secrets as empty strings, not unset).
tasks.register("downloadPublicKey") {
  doLast {
    val keyUrl = System.getenv("LICENSE_PUBLIC_KEY_URL")
    val githubToken = System.getenv("GITHUB_TOKEN")
    if (keyUrl.isNullOrEmpty() || githubToken.isNullOrEmpty()) {
      // GitHub Actions resolves unavailable secrets to "" (not unset). That happens for
      // pull_request workflows from forks, which never receive repository secrets.
      if (System.getenv("GITHUB_ACTIONS") == "true") {
        throw GradleException(
          """
          License public key is missing: LICENSE_PUBLIC_KEY_URL and/or GITHUB_TOKEN is empty or unset.

          Primary recommendation: open this pull request from a branch in
          datastrato/gravitino-enterprise (not from a fork). GitHub Actions does not
          expose repository secrets to pull_request workflows from forks, so
          ENTERPRISE_TEST_PUBLIC_KEY_URI resolves to an empty string and the build
          cannot download the enterprise license public key.

          For local development, leave these variables unset to skip this task.
          """.trimIndent()
        )
      }
      logger.lifecycle(
        "LICENSE_PUBLIC_KEY_URL or GITHUB_TOKEN not set — skipping public key download (local dev)"
      )
      return@doLast
    }
    logger.lifecycle("Downloading public key from: $keyUrl")
    val conn = URL(keyUrl).openConnection() as HttpURLConnection
    conn.setRequestProperty("Authorization", "Bearer $githubToken")
    conn.setRequestProperty("Accept", "application/vnd.github.raw+json")
    if (conn.responseCode != 200) {
      throw GradleException(
        "Failed to download public key: HTTP ${conn.responseCode} from $keyUrl"
      )
    }
    val pem = conn.inputStream.bufferedReader().readText()
    file("src/main/resources").mkdirs()
    file("src/main/resources/gravitino-master.pub").writeText(pem)
    logger.lifecycle("Public key written to src/main/resources/gravitino-master.pub")
  }
}

tasks.named("processResources") { dependsOn("downloadPublicKey") }
