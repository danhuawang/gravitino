/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino;

import com.datastrato.gravitino.catalog.DatastratoFilesetDispatcher;
import com.datastrato.gravitino.catalog.DatastratoFilesetHookDispatcher;
import com.datastrato.gravitino.catalog.DatastratoFilesetNormalizeDispatcher;
import com.datastrato.gravitino.catalog.DatastratoFilesetOperationDispatcher;
import com.datastrato.gravitino.catalog.DatastratoModelDispatcher;
import com.datastrato.gravitino.catalog.DatastratoModelHookDispatcher;
import com.datastrato.gravitino.catalog.DatastratoModelNormalizeDispatcher;
import com.datastrato.gravitino.catalog.DatastratoModelOperationDispatcher;
import com.datastrato.gravitino.catalog.DatastratoSchemaDispatcher;
import com.datastrato.gravitino.catalog.DatastratoSchemaHookDispatcher;
import com.datastrato.gravitino.catalog.DatastratoSchemaNormalizeDispatcher;
import com.datastrato.gravitino.catalog.DatastratoSchemaOperationDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTableDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTableHookDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTableNormalizeDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTableOperationDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTopicDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTopicHookDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTopicNormalizeDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTopicOperationDispatcher;
import com.datastrato.gravitino.listener.DatastratoFilesetEventDispatcher;
import com.datastrato.gravitino.listener.DatastratoModelEventDispatcher;
import com.datastrato.gravitino.listener.DatastratoSchemaEventDispatcher;
import com.datastrato.gravitino.listener.DatastratoTableEventDispatcher;
import com.datastrato.gravitino.listener.DatastratoTopicEventDispatcher;
import com.datastrato.gravitino.preview.TrinoJdbcDataPreviewOperator;
import com.datastrato.gravitino.storage.relational.service.MetricDataService;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.FutureGrantManager;
import org.apache.gravitino.authorization.OwnerManager;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.PartitionDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.credential.CredentialOperationDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.tag.TagDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatastratoGravitinoEnv extends GravitinoEnv {
  private static final Logger LOG = LoggerFactory.getLogger(DatastratoGravitinoEnv.class);
  private static final DatastratoGravitinoEnv INSTANCE = new DatastratoGravitinoEnv();

  private DatastratoSchemaDispatcher datastratoSchemaDispatcher;
  private DatastratoTableDispatcher datastratoTableDispatcher;
  private DatastratoFilesetDispatcher datastratoFilesetDispatcher;
  private DatastratoTopicDispatcher datastratoTopicDispatcher;
  private DatastratoModelDispatcher datastratoModelDispatcher;

  private MetricDataService metricDataService;

  public static DatastratoGravitinoEnv getInstance() {
    return INSTANCE;
  }

  @Override
  public void initializeFullComponents(Config config) {
    // Avoid calling super.initializeFullComponents() to prevent double initialization.
    GravitinoEnv.getInstance().initializeFullComponents(config);

    LOG.info("Initializing Datastrato Gravitino Environment...");

    // initialize schema dispatcher
    DatastratoSchemaDispatcher schemaOperationDispatcher =
        new DatastratoSchemaOperationDispatcher(catalogManager(), entityStore(), idGenerator());
    DatastratoSchemaHookDispatcher schemaHookDispatcher =
        new DatastratoSchemaHookDispatcher(schemaOperationDispatcher);
    DatastratoSchemaDispatcher schemaNormalizeDispatcher =
        new DatastratoSchemaNormalizeDispatcher(schemaHookDispatcher, catalogManager());
    this.datastratoSchemaDispatcher =
        new DatastratoSchemaEventDispatcher(eventBus(), schemaNormalizeDispatcher);

    // initialize table dispatcher
    DatastratoTableDispatcher tableOperationDispatcher =
        new DatastratoTableOperationDispatcher(
            catalogManager(),
            entityStore(),
            idGenerator(),
            new TrinoJdbcDataPreviewOperator(config, tagDispatcher()));
    DatastratoTableHookDispatcher tableHookDispatcher =
        new DatastratoTableHookDispatcher(tableOperationDispatcher);
    DatastratoTableDispatcher tableNormalizeDispatcher =
        new DatastratoTableNormalizeDispatcher(tableHookDispatcher, catalogManager());
    this.datastratoTableDispatcher =
        new DatastratoTableEventDispatcher(eventBus(), tableNormalizeDispatcher);

    // initialize fileset dispatcher
    DatastratoFilesetDispatcher filesetOperationDispatcher =
        new DatastratoFilesetOperationDispatcher(catalogManager(), entityStore(), idGenerator());
    DatastratoFilesetDispatcher filesetHookDispatcher =
        new DatastratoFilesetHookDispatcher(filesetOperationDispatcher);
    DatastratoFilesetDispatcher filesetNormalizeDispatcher =
        new DatastratoFilesetNormalizeDispatcher(filesetHookDispatcher, catalogManager());
    this.datastratoFilesetDispatcher =
        new DatastratoFilesetEventDispatcher(eventBus(), filesetNormalizeDispatcher);

    // initialize topic dispatcher
    DatastratoTopicDispatcher topicOperationDispatcher =
        new DatastratoTopicOperationDispatcher(catalogManager(), entityStore(), idGenerator());
    DatastratoTopicHookDispatcher topicHookDispatcher =
        new DatastratoTopicHookDispatcher(topicOperationDispatcher);
    DatastratoTopicDispatcher topicNormalizeDispatcher =
        new DatastratoTopicNormalizeDispatcher(topicHookDispatcher, catalogManager());
    this.datastratoTopicDispatcher =
        new DatastratoTopicEventDispatcher(eventBus(), topicNormalizeDispatcher);

    // initialize model dispatcher
    DatastratoModelDispatcher modelOperationDispatcher =
        new DatastratoModelOperationDispatcher(catalogManager(), entityStore(), idGenerator());
    DatastratoModelHookDispatcher modelHookDispatcher =
        new DatastratoModelHookDispatcher(modelOperationDispatcher);
    DatastratoModelNormalizeDispatcher datastratoModelNormalizeDispatcher =
        new DatastratoModelNormalizeDispatcher(modelHookDispatcher, catalogManager());
    datastratoModelDispatcher =
        new DatastratoModelEventDispatcher(eventBus(), datastratoModelNormalizeDispatcher);

    // initialize metric data service
    boolean enableAuthorization = config.get(Configs.ENABLE_AUTHORIZATION);
    this.metricDataService = new MetricDataService(enableAuthorization);

    LOG.info("Datastrato Gravitino Environment initialized.");
  }

  @Override
  public CatalogDispatcher catalogDispatcher() {
    return GravitinoEnv.getInstance().catalogDispatcher();
  }

  @Override
  public DatastratoSchemaDispatcher schemaDispatcher() {
    return datastratoSchemaDispatcher;
  }

  @Override
  public TableDispatcher tableDispatcher() {
    return datastratoTableDispatcher;
  }

  @Override
  public FilesetDispatcher filesetDispatcher() {
    return datastratoFilesetDispatcher;
  }

  @Override
  public TopicDispatcher topicDispatcher() {
    return datastratoTopicDispatcher;
  }

  @Override
  public MetalakeDispatcher metalakeDispatcher() {
    return GravitinoEnv.getInstance().metalakeDispatcher();
  }

  @Override
  public PartitionDispatcher partitionDispatcher() {
    return GravitinoEnv.getInstance().partitionDispatcher();
  }

  public MetricDataService metricDataService() {
    return metricDataService;
  }

  @Override
  public Config config() {
    return GravitinoEnv.getInstance().config();
  }

  @Override
  public EntityStore entityStore() {
    return GravitinoEnv.getInstance().entityStore();
  }

  @Override
  public IdGenerator idGenerator() {
    return GravitinoEnv.getInstance().idGenerator();
  }

  @Override
  public CatalogManager catalogManager() {
    return GravitinoEnv.getInstance().catalogManager();
  }

  @Override
  public EventBus eventBus() {
    return GravitinoEnv.getInstance().eventBus();
  }

  @Override
  public MetricsSystem metricsSystem() {
    return GravitinoEnv.getInstance().metricsSystem();
  }

  @Override
  public LockManager lockManager() {
    return GravitinoEnv.getInstance().lockManager();
  }

  @Override
  public AccessControlDispatcher accessControlDispatcher() {
    return GravitinoEnv.getInstance().accessControlDispatcher();
  }

  @Override
  public TagDispatcher tagDispatcher() {
    return GravitinoEnv.getInstance().tagDispatcher();
  }

  @Override
  public CredentialOperationDispatcher credentialOperationDispatcher() {
    return GravitinoEnv.getInstance().credentialOperationDispatcher();
  }

  @Override
  public OwnerManager ownerManager() {
    return GravitinoEnv.getInstance().ownerManager();
  }

  @Override
  public FutureGrantManager futureGrantManager() {
    return GravitinoEnv.getInstance().futureGrantManager();
  }

  @Override
  public ModelDispatcher modelDispatcher() {
    return datastratoModelDispatcher;
  }

  @Override
  public void start() {
    GravitinoEnv.getInstance().start();
  }

  @Override
  public void shutdown() {
    GravitinoEnv.getInstance().shutdown();
  }
}
