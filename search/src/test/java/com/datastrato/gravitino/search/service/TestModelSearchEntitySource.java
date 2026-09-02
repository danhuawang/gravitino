/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.datastrato.gravitino.search.dto.SearchModelEntityDTO;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.po.SearchModelEntityPO;
import com.datastrato.gravitino.search.utils.SearchEntityCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.EntityCombinedModel;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.ModelEntity;
import org.apache.gravitino.model.Model;
import org.apache.gravitino.model.ModelVersion;
import org.apache.gravitino.tag.Tag;
import org.apache.gravitino.tag.TagDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class TestModelSearchEntitySource {

  private static final Namespace MODEL_NAMESPACE = Namespace.of("test", "c1", "s1");
  private static final NameIdentifier MODEL_IDENT = NameIdentifier.of(MODEL_NAMESPACE, "model1");

  private Object originalModelDispatcher;
  private Object originalTagDispatcher;

  @BeforeEach
  void setUp() throws IllegalAccessException {
    GravitinoEnv env = GravitinoEnv.getInstance();
    originalModelDispatcher = FieldUtils.readField(env, "modelDispatcher", true);
    originalTagDispatcher = FieldUtils.readField(env, "tagDispatcher", true);

    TagDispatcher tagDispatcher = Mockito.mock(TagDispatcher.class);
    Mockito.when(
            tagDispatcher.listTagsInfoForMetadataObject(
                ArgumentMatchers.anyString(), ArgumentMatchers.any()))
        .thenReturn(new Tag[0]);
    FieldUtils.writeField(env, "tagDispatcher", tagDispatcher, true);
  }

  @AfterEach
  void tearDown() throws IllegalAccessException {
    GravitinoEnv env = GravitinoEnv.getInstance();
    FieldUtils.writeField(env, "modelDispatcher", originalModelDispatcher, true);
    FieldUtils.writeField(env, "tagDispatcher", originalTagDispatcher, true);
  }

  @Test
  void testNamedModelVersionUrisAreConverted() throws IllegalAccessException {
    EntityCombinedModel model = newModel();
    ModelVersion modelVersion = newModelVersion();
    ModelDispatcher dispatcher = Mockito.mock(ModelDispatcher.class);
    Mockito.when(dispatcher.getModel(MODEL_IDENT)).thenReturn(model);
    Mockito.when(dispatcher.listModelVersionInfos(MODEL_IDENT))
        .thenReturn(new ModelVersion[] {modelVersion});
    FieldUtils.writeField(GravitinoEnv.getInstance(), "modelDispatcher", dispatcher, true);

    ModelSearchEntitySource source =
        new ModelSearchEntitySource(
            ImmutableList.of(SearchEntityIdentifier.of(MODEL_IDENT, Entity.EntityType.MODEL)));

    List<SearchEntityPO> batch = source.nextBatch(1);

    assertEquals(1, batch.size());
    JsonNode versions =
        SearchEntityCodec.INSTANCE.objectMapper().valueToTree(batch.get(0)).get("model_versions");
    assertEquals(1, versions.size());
    JsonNode version = versions.get(0);
    assertEquals(7, version.get("version").asInt());
    assertEquals("s3://models/tokenizer", version.get("uri").get(0).asText());
    assertEquals("s3://models/weights", version.get("uri").get(1).asText());
    assertEquals("production", version.get("aliases").get(0).asText());
    assertTrue(source.getProcessFailedEntities().isEmpty());

    verify(dispatcher).listModelVersionInfos(MODEL_IDENT);
    verify(dispatcher, never()).listModelVersions(ArgumentMatchers.any());
    verify(dispatcher, never()).getModelVersion(ArgumentMatchers.any(), ArgumentMatchers.anyInt());
  }

  @Test
  void testModelVersionWithoutAliasesCanBeConverted() throws IllegalAccessException {
    EntityCombinedModel model = newModel();
    ModelVersion modelVersion = newModelVersion();
    Mockito.when(modelVersion.aliases()).thenReturn(new String[0]);
    ModelDispatcher dispatcher = Mockito.mock(ModelDispatcher.class);
    Mockito.when(dispatcher.getModel(MODEL_IDENT)).thenReturn(model);
    Mockito.when(dispatcher.listModelVersionInfos(MODEL_IDENT))
        .thenReturn(new ModelVersion[] {modelVersion});
    FieldUtils.writeField(GravitinoEnv.getInstance(), "modelDispatcher", dispatcher, true);

    ModelSearchEntitySource source =
        new ModelSearchEntitySource(
            ImmutableList.of(SearchEntityIdentifier.of(MODEL_IDENT, Entity.EntityType.MODEL)));

    List<SearchEntityPO> batch = source.nextBatch(1);

    assertEquals(1, batch.size());
    String json = SearchEntityCodec.INSTANCE.serialize(batch.get(0));
    JsonNode version =
        SearchEntityCodec.INSTANCE
            .objectMapper()
            .valueToTree(batch.get(0))
            .get("model_versions")
            .get(0);
    assertFalse(version.has("aliases"));
    SearchModelEntityPO storedModel =
        SearchEntityCodec.INSTANCE.deserialize(json, SearchModelEntityPO.class);
    assertDoesNotThrow(
        () -> SearchEntityCodec.INSTANCE.convert(storedModel, SearchModelEntityDTO.class));
    assertTrue(source.getProcessFailedEntities().isEmpty());
  }

  private EntityCombinedModel newModel() {
    Model model = Mockito.mock(Model.class);
    Mockito.when(model.name()).thenReturn(MODEL_IDENT.name());
    Mockito.when(model.comment()).thenReturn("test model");
    Mockito.when(model.properties()).thenReturn(ImmutableMap.of());
    Mockito.when(model.latestVersion()).thenReturn(7);
    Mockito.when(model.auditInfo()).thenReturn(AuditInfo.EMPTY);

    ModelEntity modelEntity =
        ModelEntity.builder()
            .withId(1000L)
            .withName(MODEL_IDENT.name())
            .withNamespace(MODEL_NAMESPACE)
            .withComment("test model")
            .withLatestVersion(7)
            .withProperties(ImmutableMap.of())
            .withAuditInfo(AuditInfo.EMPTY)
            .build();
    return EntityCombinedModel.of(model, modelEntity);
  }

  private ModelVersion newModelVersion() {
    ModelVersion modelVersion = Mockito.mock(ModelVersion.class);
    Mockito.when(modelVersion.version()).thenReturn(7);
    Mockito.when(modelVersion.aliases()).thenReturn(new String[] {"production"});
    Mockito.when(modelVersion.uris())
        .thenReturn(
            ImmutableMap.of(
                "weights", "s3://models/weights", "tokenizer", "s3://models/tokenizer"));
    return modelVersion;
  }
}
