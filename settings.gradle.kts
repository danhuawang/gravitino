/*
 * Copyright 2023 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version("0.7.0")
}

rootProject.name = "gravitino-enterprise"

includeBuild("gravitino-oss")

include("common-extension")
include("core-extension")
include("server-extension")
include("trino-connector-extension")
