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
package org.apache.gravitino.catalog.bigquery;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.bigquery.Bigquery;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.catalog.jdbc.config.JdbcConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BigQuery client pool for managing BigQuery API clients.
 *
 * <p>This class provides a centralized way to create and manage BigQuery API clients with proper
 * authentication using service account credentials. It uses the Google API Client Library for Java
 * (google-api-services-bigquery) which is already included in the Simba JDBC driver dependencies.
 */
public class BigQueryClientPool {

  private static final Logger LOG = LoggerFactory.getLogger(BigQueryClientPool.class);
  private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
  private static final String APPLICATION_NAME = "Apache-Gravitino-BigQuery-Catalog";

  private final String projectId;
  private final String keyFilePath;
  private volatile Bigquery bigQueryClient;

  /**
   * Creates a new BigQuery client pool.
   *
   * @param config catalog configuration containing project-id and jdbc-password (key file path)
   */
  public BigQueryClientPool(Map<String, String> config) {
    this.projectId = config.get(BigQueryCatalogPropertiesMetadata.PROJECT_ID);
    this.keyFilePath = config.get(JdbcConfig.PASSWORD.getKey()); // Key file path

    if (StringUtils.isBlank(projectId)) {
      throw new IllegalArgumentException("project-id is required for BigQuery catalog");
    }
    if (StringUtils.isBlank(keyFilePath)) {
      throw new IllegalArgumentException(
          "jdbc-password (key file path) is required for BigQuery catalog");
    }

    LOG.info("Initialized BigQuery client pool for project: {}", projectId);
  }

  /**
   * Gets or creates a BigQuery API client.
   *
   * <p>This method uses double-checked locking to ensure thread-safe lazy initialization of the
   * BigQuery client.
   *
   * @return BigQuery API client
   * @throws RuntimeException if client creation fails
   */
  public Bigquery getClient() {
    if (bigQueryClient == null) {
      synchronized (this) {
        if (bigQueryClient == null) {
          bigQueryClient = createClient();
        }
      }
    }
    return bigQueryClient;
  }

  /**
   * Creates a new BigQuery API client with service account authentication.
   *
   * @return BigQuery API client
   * @throws RuntimeException if client creation fails
   */
  private Bigquery createClient() {
    try {
      LOG.debug("Creating BigQuery API client for project: {}", projectId);

      // Load service account credentials from key file
      GoogleCredentials credentials;
      try (FileInputStream serviceAccountStream = new FileInputStream(keyFilePath)) {
        credentials = ServiceAccountCredentials.fromStream(serviceAccountStream);
      }

      // Create HTTP transport
      HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

      // Build BigQuery client with credentials
      Bigquery client =
          new Bigquery.Builder(httpTransport, JSON_FACTORY, new HttpCredentialsAdapter(credentials))
              .setApplicationName(APPLICATION_NAME)
              .build();

      LOG.info("Successfully created BigQuery API client for project: {}", projectId);
      return client;

    } catch (IOException e) {
      String errorMsg =
          String.format(
              "Failed to create BigQuery API client for project '%s'. "
                  + "Please verify the service account key file path: %s",
              projectId, keyFilePath);
      LOG.error(errorMsg, e);
      throw new RuntimeException(errorMsg, e);
    } catch (GeneralSecurityException e) {
      String errorMsg =
          String.format("Security error creating BigQuery API client for project '%s'", projectId);
      LOG.error(errorMsg, e);
      throw new RuntimeException(errorMsg, e);
    } catch (Exception e) {
      String errorMsg =
          String.format(
              "Unexpected error creating BigQuery API client for project '%s'", projectId);
      LOG.error(errorMsg, e);
      throw new RuntimeException(errorMsg, e);
    }
  }

  /**
   * Closes the BigQuery client and releases resources.
   *
   * <p>This method should be called when the catalog is being shut down.
   */
  public void close() {
    if (bigQueryClient != null) {
      try {
        // BigQuery client doesn't have an explicit close method
        // Resources are managed by the underlying HTTP transport
        bigQueryClient = null;
        LOG.info("Closed BigQuery API client for project: {}", projectId);
      } catch (Exception e) {
        LOG.warn("Error closing BigQuery API client", e);
      }
    }
  }

  /**
   * Gets the project ID.
   *
   * @return project ID
   */
  public String getProjectId() {
    return projectId;
  }
}
