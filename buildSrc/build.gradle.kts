/*
 * Copyright 2026 Datastrato Inc.
 *
 * Testable build logic for the enterprise license checks. Compiled before the
 * root build; classes are available to build scripts (including the
 * applied enterprise-licenses.gradle.kts).
 */

plugins {
  `java`
}

repositories {
  mavenCentral()
}

dependencies {
  compileOnly("com.google.code.findbugs:jsr305:3.0.2")
  testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
  testImplementation("org.junit.jupiter:junit-jupiter-params:5.8.1")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
}

tasks.test {
  useJUnitPlatform()
}
