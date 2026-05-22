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
  // Provides OAuth2TokenProvider that PasswordGrantTokenProvider extends.
  implementation(project(":clients:client-java"))

  implementation(libs.jackson.databind)
  implementation(libs.slf4j.api)

  // Keycloak Admin REST client used by KeycloakAdminHelper to manage users
  // and groups in E2E tests. Matches the Keycloak server v26.0.x deployed in
  // qa/k8s/keycloak-deployment.yaml.
  implementation(libs.keycloak.admin.client)
}
