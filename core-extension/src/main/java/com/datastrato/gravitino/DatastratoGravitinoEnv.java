/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino;

import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
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
import com.datastrato.gravitino.catalog.DatastratoViewDispatcher;
import com.datastrato.gravitino.catalog.DatastratoViewNormalizeDispatcher;
import com.datastrato.gravitino.catalog.DatastratoViewOperationDispatcher;
import com.datastrato.gravitino.listener.DatastratoFilesetEventDispatcher;
import com.datastrato.gravitino.listener.DatastratoModelEventDispatcher;
import com.datastrato.gravitino.listener.DatastratoSchemaEventDispatcher;
import com.datastrato.gravitino.listener.DatastratoTableEventDispatcher;
import com.datastrato.gravitino.listener.DatastratoTopicEventDispatcher;
import com.datastrato.gravitino.listener.DatastratoViewEventDispatcher;
import com.datastrato.gravitino.preview.TrinoJdbcDataPreviewOperator;
import org.apache.gravitino.Config;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.authorization.FutureGrantManager;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.FunctionDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.PartitionDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.credential.CredentialOperationDispatcher;
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.EventListenerManager;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.stats.StatisticDispatcher;
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
  private DatastratoViewDispatcher datastratoViewDispatcher;
  private DatastratoAccessControlDispatcher accessControlDispatcher;

  private SchemaDispatcher internalDatastratoSchemaDispatcher;
  private TableDispatcher internalDatastratoTableDispatcher;
  private FilesetDispatcher internalDatastratoFilesetDispatcher;
  private DatastratoViewDispatcher internalDatastratoViewDispatcher;
  private DatastratoAccessControlDispatcher internalDatastratoAccessControlDispatcher;

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
    // internal: normalize only, no hooks or events — used by other dispatchers to import schemas
    this.internalDatastratoSchemaDispatcher =
        new DatastratoSchemaNormalizeDispatcher(schemaOperationDispatcher, catalogManager());

    // initialize table dispatcher
    DatastratoTableDispatcher tableOperationDispatcher =
        new DatastratoTableOperationDispatcher(
            catalogManager(),
            entityStore(),
            idGenerator(),
            new TrinoJdbcDataPreviewOperator(config, tagDispatcher()),
            // Capture 'this' so the supplier always resolves through the actual initialized
            // env instance (DatastratoGravitinoEnv or its subclass), not a static singleton.
            () -> datastratoSchemaDispatcher);
    DatastratoTableHookDispatcher tableHookDispatcher =
        new DatastratoTableHookDispatcher(
            tableOperationDispatcher, this::ownerDispatcher, catalogManager());
    DatastratoTableDispatcher tableNormalizeDispatcher =
        new DatastratoTableNormalizeDispatcher(tableHookDispatcher, catalogManager());
    this.datastratoTableDispatcher =
        new DatastratoTableEventDispatcher(eventBus(), tableNormalizeDispatcher);
    // internal: normalize only, no hooks or events — used by AuthorizationUtils for metadata
    // lookups
    DatastratoTableDispatcher internalTableOperationDispatcher =
        new DatastratoTableOperationDispatcher(
            catalogManager(),
            entityStore(),
            idGenerator(),
            new TrinoJdbcDataPreviewOperator(config, tagDispatcher()),
            () -> internalDatastratoSchemaDispatcher);
    this.internalDatastratoTableDispatcher =
        new DatastratoTableNormalizeDispatcher(internalTableOperationDispatcher, catalogManager());

    // initialize fileset dispatcher
    DatastratoFilesetDispatcher filesetOperationDispatcher =
        new DatastratoFilesetOperationDispatcher(catalogManager(), entityStore(), idGenerator());
    DatastratoFilesetDispatcher filesetHookDispatcher =
        new DatastratoFilesetHookDispatcher(filesetOperationDispatcher);
    DatastratoFilesetDispatcher filesetNormalizeDispatcher =
        new DatastratoFilesetNormalizeDispatcher(filesetHookDispatcher, catalogManager());
    this.datastratoFilesetDispatcher =
        new DatastratoFilesetEventDispatcher(eventBus(), filesetNormalizeDispatcher);
    // internal: normalize only, no hooks or events
    this.internalDatastratoFilesetDispatcher =
        new DatastratoFilesetNormalizeDispatcher(filesetOperationDispatcher, catalogManager());

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

    // initialize view dispatcher
    DatastratoViewDispatcher viewOperationDispatcher =
        new DatastratoViewOperationDispatcher(catalogManager(), entityStore(), idGenerator());
    DatastratoViewDispatcher viewNormalizeDispatcher =
        new DatastratoViewNormalizeDispatcher(viewOperationDispatcher, catalogManager());
    this.datastratoViewDispatcher =
        new DatastratoViewEventDispatcher(eventBus(), viewNormalizeDispatcher);

    // initialize internal view dispatcher
    DatastratoViewDispatcher internalViewOperationDispatcher =
        new DatastratoViewOperationDispatcher(
            catalogManager(),
            entityStore(),
            idGenerator(),
            () -> internalDatastratoSchemaDispatcher);
    this.internalDatastratoViewDispatcher =
        new DatastratoViewNormalizeDispatcher(internalViewOperationDispatcher, catalogManager());

    // initialize access control dispatcher
    accessControlDispatcher =
        new DatastratoAccessControlDispatcher(
            GravitinoEnv.getInstance().accessControlDispatcher(), entityStore());

    // initialize internal access control dispatcher
    this.internalDatastratoAccessControlDispatcher =
        new DatastratoAccessControlDispatcher(
            GravitinoEnv.getInstance().internalAccessControlDispatcher(), entityStore());

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
  public SchemaDispatcher internalSchemaDispatcher() {
    return internalDatastratoSchemaDispatcher;
  }

  @Override
  public TableDispatcher tableDispatcher() {
    return datastratoTableDispatcher;
  }

  @Override
  public TableDispatcher internalTableDispatcher() {
    return internalDatastratoTableDispatcher;
  }

  @Override
  public FilesetDispatcher filesetDispatcher() {
    return datastratoFilesetDispatcher;
  }

  @Override
  public FilesetDispatcher internalFilesetDispatcher() {
    return internalDatastratoFilesetDispatcher;
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

  @Override
  public ViewDispatcher viewDispatcher() {
    return datastratoViewDispatcher;
  }

  @Override
  public ViewDispatcher internalViewDispatcher() {
    return internalDatastratoViewDispatcher;
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
  public DatastratoAccessControlDispatcher accessControlDispatcher() {
    return accessControlDispatcher;
  }

  @Override
  public DatastratoAccessControlDispatcher internalAccessControlDispatcher() {
    return internalDatastratoAccessControlDispatcher;
  }

  @Override
  public TagDispatcher tagDispatcher() {
    return GravitinoEnv.getInstance().tagDispatcher();
  }

  @Override
  public FunctionDispatcher functionDispatcher() {
    return GravitinoEnv.getInstance().functionDispatcher();
  }

  @Override
  public PolicyDispatcher policyDispatcher() {
    return GravitinoEnv.getInstance().policyDispatcher();
  }

  @Override
  public JobOperationDispatcher jobOperationDispatcher() {
    return GravitinoEnv.getInstance().jobOperationDispatcher();
  }

  @Override
  public EventListenerManager eventListenerManager() {
    return GravitinoEnv.getInstance().eventListenerManager();
  }

  @Override
  public void setGravitinoAuthorizer(GravitinoAuthorizer gravitinoAuthorizer) {
    GravitinoEnv.getInstance().setGravitinoAuthorizer(gravitinoAuthorizer);
  }

  @Override
  public GravitinoAuthorizer gravitinoAuthorizer() {
    return GravitinoEnv.getInstance().gravitinoAuthorizer();
  }

  @Override
  public StatisticDispatcher statisticDispatcher() {
    return GravitinoEnv.getInstance().statisticDispatcher();
  }

  @Override
  public CredentialOperationDispatcher credentialOperationDispatcher() {
    return GravitinoEnv.getInstance().credentialOperationDispatcher();
  }

  @Override
  public OwnerDispatcher ownerDispatcher() {
    return GravitinoEnv.getInstance().ownerDispatcher();
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
