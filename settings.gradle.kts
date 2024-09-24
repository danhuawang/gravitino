/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version("0.7.0")
}

rootProject.name = "datastrato-gravitino"

includeBuild("gravitino-oss") {
  dependencySubstitution {
    substitute(module("org.apache.gravitino:api"))
      .using(project(":api"))

    substitute(module("org.apache.gravitino:catalog-common"))
      .using(project(":catalogs:catalog-common"))

    substitute(module("org.apache.gravitino:client-java-runtime"))
      .using(project(":clients:client-java-runtime"))

    substitute(module("org.apache.gravitino:common"))
      .using(project(":common"))

    substitute(module("org.apache.gravitino:core"))
      .using(project(":core"))

    substitute(module("org.apache.gravitino:server"))
      .using(project(":server"))

    substitute(module("org.apache.gravitino:server-common"))
      .using(project(":server-common"))

    substitute(module("org.apache.gravitino:trino-connector:trino-connector"))
      .using(project(":trino-connector:trino-connector"))
  }
}

include("common-extension")
include("core-extension")
include("datastrato-server")
include("trino-connector-extension")
