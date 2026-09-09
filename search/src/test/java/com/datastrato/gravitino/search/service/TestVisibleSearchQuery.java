/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import static com.datastrato.gravitino.search.config.SearchConfig.DEFAULT_MAX_AUTHORIZATION_SCAN_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.parser.Condition;
import com.datastrato.gravitino.search.store.SearchDataSource;
import com.datastrato.gravitino.search.store.SearchStorage;
import java.util.List;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.utils.PrincipalUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Verifies filtered queries preserve parsed search terms and close their backend cursors. */
class TestVisibleSearchQuery {
  private GravitinoAuthorizer previous;
  private GravitinoAuthorizer authorizer;
  private SearchService service;
  private SearchStorage storage;
  private SearchDataSource source;
  private Object previousMetalakeDispatcher;
  private MetalakeDispatcher metalakeDispatcher;

  @BeforeEach
  void setUp() throws Exception {
    previous = GravitinoEnv.getInstance().gravitinoAuthorizer();
    previousMetalakeDispatcher =
        FieldUtils.readField(GravitinoEnv.getInstance(), "metalakeDispatcher", true);
    metalakeDispatcher = mock(MetalakeDispatcher.class);
    when(metalakeDispatcher.metalakeExists(NameIdentifier.of("lake"))).thenReturn(true);
    FieldUtils.writeField(
        GravitinoEnv.getInstance(), "metalakeDispatcher", metalakeDispatcher, true);
    authorizer = mock(GravitinoAuthorizer.class);
    GravitinoEnv.getInstance().setGravitinoAuthorizer(authorizer);
    service = mock(SearchService.class, CALLS_REAL_METHODS);
    storage = mock(SearchStorage.class);
    source = mock(SearchDataSource.class);
    FieldUtils.writeField(service, "storage", storage, true);
    // The mocked service skips the constructor, so seed the configured authorization scan limit.
    FieldUtils.writeField(
        service, "maxAuthorizationScanSize", DEFAULT_MAX_AUTHORIZATION_SCAN_SIZE, true);
    when(storage.search(eq("lake"), any(), any(), eq(List.of()))).thenReturn(source);
    when(source.nextBatch()).thenReturn(SearchDataSource.Result.empty());
  }

  @AfterEach
  void tearDown() throws Exception {
    FieldUtils.writeField(
        GravitinoEnv.getInstance(), "metalakeDispatcher", previousMetalakeDispatcher, true);
    GravitinoEnv.getInstance().setGravitinoAuthorizer(previous);
  }

  @Test
  void testKeywordAndFilterArePassedToCursorAndCursorIsClosed() throws Exception {
    assertEquals(
        List.of(),
        PrincipalUtils.doAs(
            new UserPrincipal("reader"),
            () -> service.queryVisible("lake", "needle entity_type:TABLE", 0, 10)));
    ArgumentCaptor<Condition> condition = ArgumentCaptor.forClass(Condition.class);
    verify(storage).search(eq("lake"), isNull(), condition.capture(), eq(List.of()));
    Condition.AndCondition conjunction =
        assertInstanceOf(Condition.AndCondition.class, condition.getValue());
    assertEquals(2, conjunction.getConditions().size());
    Condition.KeywordCondition keyword =
        assertInstanceOf(Condition.KeywordCondition.class, conjunction.getConditions().get(0));
    assertEquals("needle", keyword.getKeyword());
    Condition.TermCondition term =
        assertInstanceOf(Condition.TermCondition.class, conjunction.getConditions().get(1));
    assertEquals("entity_type", term.getField());
    assertEquals("TABLE", term.getValue());
    verify(source).close();
  }

  @Test
  void testKeywordOnlyQueryRetainsSearchText() {
    service.queryVisible("lake", "needle", 0, 10);
    ArgumentCaptor<Condition> condition = ArgumentCaptor.forClass(Condition.class);
    verify(storage).search(eq("lake"), isNull(), condition.capture(), eq(List.of()));
    assertEquals(
        "needle",
        assertInstanceOf(Condition.KeywordCondition.class, condition.getValue()).getKeyword());
    verify(source).close();
  }

  @Test
  void testUnfilteredQueryRetainsKeywordsAndFilter() {
    service.query("lake", "needle entity_type:TABLE", 2, 10);
    ArgumentCaptor<Condition> condition = ArgumentCaptor.forClass(Condition.class);
    verify(storage).search(eq("lake"), isNull(), condition.capture(), eq(List.of()), eq(10), eq(2));
    Condition.AndCondition conjunction =
        assertInstanceOf(Condition.AndCondition.class, condition.getValue());
    assertEquals(2, conjunction.getConditions().size());
    Condition.KeywordCondition keyword =
        assertInstanceOf(Condition.KeywordCondition.class, conjunction.getConditions().get(0));
    assertEquals("needle", keyword.getKeyword());
    Condition.TermCondition term =
        assertInstanceOf(Condition.TermCondition.class, conjunction.getConditions().get(1));
    assertEquals("entity_type", term.getField());
    assertEquals("TABLE", term.getValue());
  }

  @Test
  void testBooleanKeywordsReachBothSearchPaths() {
    service.queryVisible("lake", "first OR second", 0, 10);
    service.query("lake", "first OR second", 0, 10);
    ArgumentCaptor<Condition> visible = ArgumentCaptor.forClass(Condition.class);
    ArgumentCaptor<Condition> ordinary = ArgumentCaptor.forClass(Condition.class);
    verify(storage).search(eq("lake"), isNull(), visible.capture(), eq(List.of()));
    verify(storage).search(eq("lake"), isNull(), ordinary.capture(), eq(List.of()), eq(10), eq(0));
    for (Condition condition : List.of(visible.getValue(), ordinary.getValue())) {
      Condition.OrCondition disjunction = assertInstanceOf(Condition.OrCondition.class, condition);
      assertEquals(2, disjunction.getConditions().size());
      assertEquals(
          "first",
          assertInstanceOf(Condition.KeywordCondition.class, disjunction.getConditions().get(0))
              .getKeyword());
      assertEquals(
          "second",
          assertInstanceOf(Condition.KeywordCondition.class, disjunction.getConditions().get(1))
              .getKeyword());
    }
    verify(source).close();
  }

  @Test
  void testKeywordlessQueryScansAllMatches() throws Exception {
    PrincipalUtils.doAs(
        new UserPrincipal("reader"), () -> service.queryVisible("lake", null, 0, 10));
    verify(storage).search("lake", null, null, List.of());
    verify(source).close();
  }

  @Test
  void testCursorIsClosedWhenAuthorizationFails() {
    SearchEntityDTO entity =
        SearchEntityDTO.SearchEntityDTOBuilder.builder()
            .withEntityId(1)
            .withEntityType(EntityType.TABLE)
            .withMetalake("lake")
            .withCatalogName("catalog")
            .withEntityName("table")
            .withFullQualifiedName("catalog.schema.table")
            .build();
    when(source.nextBatch())
        .thenReturn(new SearchDataSource.Result(List.of(entity), EntityType.TABLE));
    when(authorizer.isOwner(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("unavailable"));
    assertThrows(
        Exception.class,
        () ->
            PrincipalUtils.doAs(
                new UserPrincipal("reader"), () -> service.queryVisible("lake", null, 0, 10)));
    verify(source).close();
  }

  @Test
  void testCursorIsClosedWhenReadingNextBatchFails() {
    when(source.nextBatch()).thenThrow(new IllegalStateException("backend unavailable"));
    assertThrows(
        Exception.class,
        () ->
            PrincipalUtils.doAs(
                new UserPrincipal("reader"), () -> service.queryVisible("lake", null, 0, 10)));
    verify(source).close();
  }

  @Test
  void testMissingMetalakeDoesNotOpenCursor() {
    when(metalakeDispatcher.metalakeExists(NameIdentifier.of("lake"))).thenReturn(false);
    assertThrows(NoSuchMetalakeException.class, () -> service.queryVisible("lake", null, 0, 10));
    verifyNoInteractions(storage);
  }

  @Test
  void testInvalidPageIsRejectedBeforeOpeningCursor() {
    assertThrows(IllegalArgumentException.class, () -> service.queryVisible("lake", null, -1, 10));
    assertThrows(IllegalArgumentException.class, () -> service.queryVisible("lake", null, 0, 0));
    verifyNoInteractions(storage);
  }
}
