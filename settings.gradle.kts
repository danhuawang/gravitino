/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version("0.7.0")
}

rootProject.name = "datastrato-gravitino"

includeBuild("gravitino-oss")

include("common-extension")
include("core-extension")
include("datastrato-server")
include("trino-connector-extension")
