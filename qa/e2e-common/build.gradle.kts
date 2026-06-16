/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
description = "qa-e2e-common"

plugins {
  `maven-publish`
  id("java")
}

dependencies {
  implementation(project(":clients:client-java"))
  implementation(libs.jackson.databind)
  implementation(libs.slf4j.api)
  implementation(libs.keycloak.admin.client)
}
