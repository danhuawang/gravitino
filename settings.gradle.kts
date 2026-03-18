/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version("0.7.0")
}

rootProject.name = "datastrato-gravitino"

includeBuild("gravitino-internal") {
  dependencySubstitution {
    substitute(module("org.apache.gravitino:api"))
      .using(project(":api"))

    substitute(module("org.apache.gravitino:catalog-common"))
      .using(project(":catalogs:catalog-common"))

    substitute(module("org.apache.gravitino:client-java-runtime"))
      .using(project(":clients:client-java-runtime"))

    substitute(module("org.apache.gravitino:client-java"))
      .using(project(":clients:client-java"))

    substitute(module("org.apache.gravitino:common"))
      .using(project(":common"))

    substitute(module("org.apache.gravitino:core"))
      .using(project(":core"))

    substitute(module("org.apache.gravitino:lineage"))
      .using(project(":lineage"))

    substitute(module("org.apache.gravitino:server"))
      .using(project(":server"))

    substitute(module("org.apache.gravitino:server-common"))
      .using(project(":server-common"))

    substitute(module("org.apache.gravitino:docs"))
      .using(project(":docs"))

    substitute(module("org.apache.gravitino:authorizations:authorization-common"))
      .using(project(":authorizations:authorization-common"))

    substitute(module("org.apache.gravitino:integration-test-common"))
      .using(project(":integration-test-common"))

    substitute(module("org.apache.gravitino:lance-common"))
      .using(project(":lance:lance-common"))
  }
}

include("common-extension")
include("core-extension")
include("datastrato-server")
include("docs")
include("authorization-jdbc-enterprise")
include("search")
include("lineage-extension")
include("metrics")
include("test:search-integration-test", "test:test-common")
