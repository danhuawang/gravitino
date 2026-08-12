/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  `maven-publish`
  id("java")
  alias(libs.plugins.shadow)
}

dependencies {
  // used by Gravitino credential vending
  implementation(libs.aws.policy) {
    exclude("*")
  }
  implementation(libs.iceberg.aws.bundle)
  // Include Gravitino AWS credential providers (S3SecretKeyProvider, etc.) for credential vending
  implementation(project(":bundles:aws"))

  testImplementation(libs.junit.jupiter.api)
  testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.withType(ShadowJar::class.java) {
  isZip64 = true
  includeEmptyDirs = false
  configurations = listOf(project.configurations.runtimeClasspath.get())
  archiveClassifier.set("")

  dependencies {
    exclude(dependency("org.slf4j:slf4j-api"))
    // Exclude Gravitino modules to prevent class duplication with server classpath
    exclude(project(":api"))
    exclude(project(":common"))
    exclude(project(":catalogs:catalog-common"))
    exclude(project(":catalogs:hadoop-common"))
  }

  // Iceberg AWS bundle includes Log4j (before 1.10.1), so exclude to avoid conflicts
  // see https://github.com/apache/iceberg/pull/14225
  exclude("org/apache/log4j/**")
  exclude("org/apache/logging/log4j/**")
  exclude("log4j.properties")
  exclude("log4j2.xml")
  exclude("log4j2.component.properties")

  // Enterprise :bundles:aws pulls Jackson (OAuthClientCredentialsTokenSource). Relocate it so
  // the bundle remains self-contained without exposing com.fasterxml.jackson on the server
  // classpath (same approach as apache/gravitino#12358 for Aliyun).
  relocate(
    "com.fasterxml.jackson",
    "org.apache.gravitino.iceberg.aws.shaded.com.fasterxml.jackson"
  )

  // POM metadata is not relocated by shadow and would still advertise the original
  // Jackson coordinates.
  exclude("META-INF/maven/com.fasterxml.jackson.core/**")
  exclude("META-INF/maven/com.fasterxml.jackson.datatype/**")
  exclude("META-INF/maven/com.fasterxml.jackson.module/**")
  exclude("META-INF/maven/com.fasterxml.jackson/**")

  mergeServiceFiles()
}

tasks.jar {
  dependsOn(tasks.named("shadowJar"))
  archiveClassifier.set("empty")
}

tasks.test {
  val shadowJar = tasks.named<ShadowJar>("shadowJar")
  dependsOn(shadowJar)
  doFirst {
    systemProperty(
      "shadowJarPath",
      shadowJar.get().archiveFile.get().asFile.absolutePath
    )
  }
}
