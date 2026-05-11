/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
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
//     https://raw.githubusercontent.com/datastrato/gravitino-license-keys/main/gravitino-master.pub
//   GITHUB_TOKEN           — PAT or GitHub App token with read access to the key repo
//                            (auto-injected in GitHub Actions)
// For local development, tests use in-memory key pairs (TestKeyPairUtil) and do not need this key.
tasks.register("downloadPublicKey") {
  doLast {
    val keyUrl = System.getenv("LICENSE_PUBLIC_KEY_URL")
    val githubToken = System.getenv("GITHUB_TOKEN")
    if (keyUrl == null || githubToken == null) {
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
