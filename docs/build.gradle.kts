/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

import com.github.gradle.node.NodeExtension
import com.github.gradle.node.npm.task.NpxTask

plugins {
  alias(libs.plugins.node)
}

configure<NodeExtension> {
  version = "21.6.1"
  npmVersion = "10.2.4"
  download = true
  nodeProjectDir.set(file("$rootDir/.node"))
}

tasks {
  val lintOpenAPI by registering(NpxTask::class) {
    command.set("@redocly/cli@1.23.1")
    args.set(listOf("lint", "--extends=recommended-strict", "${project.projectDir}/open-api/openapi.yaml"))
  }

  build {
    dependsOn(lintOpenAPI)
  }
}
