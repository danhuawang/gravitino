/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.store.SearchDataSource;
import com.datastrato.gravitino.search.utils.EntityConverterUtils;
import com.datastrato.gravitino.search.utils.SearchEntityCodec;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.gravitino.utils.PrincipalUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Regression tests using the real authorization expression evaluator. */
class TestSearchResultFilter {

  private static final int UNLIMITED_SCAN = Integer.MAX_VALUE;

  @Test
  void testEveryIndexedTypeUsesItsVisibilityRules() throws Exception {
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    when(authorizer.authorize(any(), eq("lake"), any(), any(), any()))
        .thenAnswer(call -> ((MetadataObject) call.getArgument(2)).name().startsWith("visible"));
    when(authorizer.isOwner(any(), eq("lake"), any(), any()))
        .thenAnswer(call -> ((MetadataObject) call.getArgument(2)).name().startsWith("visible"));
    when(authorizer.isSelf(any(), any(), any()))
        .thenAnswer(call -> ((NameIdentifier) call.getArgument(1)).name().startsWith("visible"));
    List<SearchDataSource.Result> batches = new ArrayList<>();
    for (EntityType type :
        List.of(
            EntityType.CATALOG,
            EntityType.SCHEMA,
            EntityType.TABLE,
            EntityType.VIEW,
            EntityType.FILESET,
            EntityType.MODEL,
            EntityType.TOPIC,
            EntityType.FUNCTION,
            EntityType.TAG,
            EntityType.POLICY,
            EntityType.ROLE,
            EntityType.USER,
            EntityType.GROUP)) {
      batches.add(
          new SearchDataSource.Result(
              List.of(entity(type, "visible"), entity(type, "hidden")), type));
    }
    List<SearchEntitiesDTO> result = filter(source(batches), 0, 10, authorizer);
    assertEquals(13, result.size());
    for (SearchEntitiesDTO group : result) {
      assertEquals(1, group.getTotalSize());
      assertEquals("visible", group.getEntities().get(0).getEntityName());
    }
  }

  @ParameterizedTest
  @CsvSource({
    "CATALOG, USE_CATALOG", "SCHEMA, USE_SCHEMA", "TABLE, SELECT_TABLE",
    "TABLE, MODIFY_TABLE", "VIEW, SELECT_VIEW", "FILESET, READ_FILESET",
    "FILESET, WRITE_FILESET", "MODEL, USE_MODEL", "TOPIC, CONSUME_TOPIC",
    "TOPIC, PRODUCE_TOPIC", "FUNCTION, EXECUTE_FUNCTION", "FUNCTION, MODIFY_FUNCTION",
    "TAG, APPLY_TAG", "POLICY, APPLY_POLICY", "ROLE, MANAGE_GRANTS",
    "USER, MANAGE_USERS", "GROUP, MANAGE_GROUPS"
  })
  void testSpecificPrivilegeWithoutOwnership(EntityType type, Privilege.Name privilege)
      throws Exception {
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    List<SearchDataSource.Result> batches =
        List.of(new SearchDataSource.Result(List.of(entity(type, "target")), type));
    when(authorizer.authorize(any(), eq("lake"), any(), eq(privilege), any()))
        .thenAnswer(
            call -> ((MetadataObject) call.getArgument(2)).type() == MetadataObject.Type.METALAKE);
    List<SearchEntitiesDTO> result = filter(source(batches), 0, 10, authorizer);
    assertEquals(1, result.size());
    assertEquals(1, result.get(0).getTotalSize());
    assertEquals("target", result.get(0).getEntities().get(0).getEntityName());

    // Revoking the grant must take effect on the next request, including its total count.
    when(authorizer.authorize(any(), eq("lake"), any(), eq(privilege), any())).thenReturn(false);
    assertTrue(filter(source(batches), 0, 10, authorizer).isEmpty());
  }

  @ParameterizedTest
  @CsvSource({"USER", "GROUP", "ROLE"})
  void testSelfVisibilityDoesNotExposeOtherIdentities(EntityType type) throws Exception {
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    when(authorizer.isSelf(eq(type), any(), any()))
        .thenAnswer(call -> ((NameIdentifier) call.getArgument(1)).name().equals("reader"));
    List<SearchEntitiesDTO> result =
        filter(
            source(
                List.of(
                    new SearchDataSource.Result(
                        List.of(entity(type, "reader"), entity(type, "other")), type))),
            0,
            10,
            authorizer);
    assertEquals(1, result.size());
    assertEquals(1, result.get(0).getTotalSize());
    assertEquals("reader", result.get(0).getEntities().get(0).getEntityName());
  }

  @Test
  void testIndexedRoleWithoutFullQualifiedNameUsesRoleName() throws Exception {
    // Role names must not contain '.' (#12977 reserves it as the qualified-name separator).
    RoleEntity role =
        RoleEntity.builder()
            .withId(1L)
            .withName("reader_role")
            .withNamespace(NamespaceUtil.ofRole("lake"))
            .withProperties(Map.of())
            .withSecurableObjects(List.of())
            .withAuditInfo(AuditInfo.EMPTY)
            .build();
    SearchEntityDTO indexedRole =
        SearchEntityCodec.INSTANCE.convert(
            EntityConverterUtils.toRoleSearchEntityPO(role, "lake"), SearchEntityDTO.class);
    assertNull(indexedRole.getFullQualifiedName());
    List<SearchDataSource.Result> batches =
        List.of(new SearchDataSource.Result(List.of(indexedRole), EntityType.ROLE));
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    assertTrue(filter(source(batches), 0, 10, authorizer).isEmpty());
    when(authorizer.isOwner(any(), eq("lake"), any(), any()))
        .thenAnswer(
            call -> {
              MetadataObject object = call.getArgument(2);
              return object.type() == MetadataObject.Type.ROLE
                  && object.name().equals("reader_role");
            });
    List<SearchEntitiesDTO> result = filter(source(batches), 0, 10, authorizer);
    assertEquals(1, result.size());
    assertEquals(1, result.get(0).getTotalSize());
    assertEquals("reader_role", result.get(0).getEntities().get(0).getEntityName());
  }

  @Test
  void testLargePageOffsetDoesNotWrapAround() throws Exception {
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    when(authorizer.isOwner(any(), any(), any(), any())).thenReturn(true);
    List<SearchEntitiesDTO> result =
        filter(
            source(List.of(batch(entity(EntityType.TABLE, "table")))),
            Integer.MAX_VALUE,
            Integer.MAX_VALUE,
            authorizer);
    assertEquals(1, result.get(0).getTotalSize());
    assertTrue(result.get(0).getEntities().isEmpty());
  }

  @Test
  void testPaginationAndCountsAcrossBatches() throws Exception {
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    when(authorizer.isOwner(any(), eq("lake"), any(), any()))
        .thenAnswer(call -> ((MetadataObject) call.getArgument(2)).name().startsWith("visible"));
    SearchDataSource source =
        source(
            List.of(
                batch(entity(EntityType.TABLE, "hidden"), entity(EntityType.TABLE, "visible1")),
                batch(entity(EntityType.TABLE, "visible2"), entity(EntityType.TABLE, "visible3")),
                new SearchDataSource.Result(
                    List.of(entity(EntityType.CATALOG, "visibleCatalog")), EntityType.CATALOG)));
    List<SearchEntitiesDTO> result = filter(source, 1, 1, authorizer);
    SearchEntitiesDTO tables =
        result.stream().filter(g -> g.getType() == EntityType.TABLE).findFirst().orElseThrow();
    assertEquals(3, tables.getTotalSize());
    assertEquals(
        List.of("visible2"),
        tables.getEntities().stream().map(SearchEntityDTO::getEntityName).toList());
    SearchEntitiesDTO catalogs =
        result.stream().filter(g -> g.getType() == EntityType.CATALOG).findFirst().orElseThrow();
    assertEquals(1, catalogs.getTotalSize());
    assertTrue(catalogs.getEntities().isEmpty());
  }

  @Test
  void testInheritedGrantStillHonorsExplicitDeny() throws Exception {
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    when(authorizer.authorize(any(), eq("lake"), any(), eq(Privilege.Name.SELECT_TABLE), any()))
        .thenReturn(true);
    when(authorizer.deny(any(), eq("lake"), any(), eq(Privilege.Name.SELECT_TABLE), any()))
        .thenAnswer(call -> ((MetadataObject) call.getArgument(2)).name().equals("hidden"));
    List<SearchEntitiesDTO> result =
        filter(
            source(
                List.of(
                    batch(
                        entity(EntityType.TABLE, "hidden"), entity(EntityType.TABLE, "visible")))),
            0,
            10,
            authorizer);
    assertEquals(1, result.get(0).getTotalSize());
    assertEquals("visible", result.get(0).getEntities().get(0).getEntityName());
  }

  @Test
  void testSchemaGrantDoesNotLeakAcrossSchemasOrCatalogs() throws Exception {
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    when(authorizer.authorize(any(), eq("lake"), any(), eq(Privilege.Name.SELECT_TABLE), any()))
        .thenAnswer(
            call -> {
              MetadataObject object = call.getArgument(2);
              return object.type() == MetadataObject.Type.SCHEMA
                  && object.fullName().equals("catalog.allowed");
            });
    List<SearchEntityDTO> entities = new ArrayList<>();
    for (String fullName :
        List.of(
            "catalog.allowed.first",
            "catalog.hidden.second",
            "other.allowed.third",
            "catalog.allowed.fourth")) {
      entities.add(
          SearchEntityDTO.SearchEntityDTOBuilder.builder()
              .withEntityId(1)
              .withEntityType(EntityType.TABLE)
              .withMetalake("lake")
              .withCatalogName(fullName.substring(0, fullName.indexOf('.')))
              .withEntityName(fullName.substring(fullName.lastIndexOf('.') + 1))
              .withFullQualifiedName(fullName)
              .build());
    }
    List<SearchEntitiesDTO> result =
        filter(
            source(
                List.of(
                    batch(entities.get(0), entities.get(1)),
                    batch(entities.get(2), entities.get(3)))),
            0,
            10,
            authorizer);
    assertEquals(2, result.get(0).getTotalSize());
    assertEquals(
        List.of("first", "fourth"),
        result.get(0).getEntities().stream().map(SearchEntityDTO::getEntityName).toList());
  }

  @Test
  void testCallerIdentityIsNotCachedAcrossRequests() throws Exception {
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    when(authorizer.isOwner(any(), eq("lake"), any(), any()))
        .thenAnswer(call -> ((Principal) call.getArgument(0)).getName().equals("reader"));
    List<SearchDataSource.Result> batches = List.of(batch(entity(EntityType.TABLE, "table")));
    assertEquals(1, filter(source(batches), 0, 10, authorizer).size());
    assertTrue(
        PrincipalUtils.doAs(
                new UserPrincipal("outsider"),
                () ->
                    SearchResultFilter.filter(
                        source(batches), "lake", 0, 10, UNLIMITED_SCAN, authorizer))
            .isEmpty());
  }

  @Test
  void testUnknownMalformedAndCrossMetalakeHitsAreHidden() throws Exception {
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    when(authorizer.isOwner(any(), any(), any(), any())).thenReturn(true);
    SearchEntityDTO foreign = mock(SearchEntityDTO.class);
    when(foreign.getEntityType()).thenReturn(EntityType.TABLE);
    when(foreign.getMetalake()).thenReturn("other");
    SearchEntityDTO malformed = mock(SearchEntityDTO.class);
    when(malformed.getEntityType()).thenReturn(EntityType.TABLE);
    when(malformed.getMetalake()).thenReturn("lake");
    when(malformed.getFullQualifiedName()).thenReturn("bad");
    assertTrue(
        filter(
                source(
                    List.of(
                        new SearchDataSource.Result(
                            Arrays.asList(
                                null, foreign, malformed, entity(EntityType.CATALOG, "cat")),
                            EntityType.TABLE),
                        new SearchDataSource.Result(
                            List.of(entity(EntityType.CATALOG, "cat")), EntityType.METALAKE))),
                0,
                10,
                authorizer)
            .isEmpty());
    verifyNoInteractions(authorizer);
  }

  @Test
  void testCurrentCallerIsPropagatedToAuthorization() throws Exception {
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    when(authorizer.authorize(any(), any(), any(), eq(Privilege.Name.SELECT_TABLE), any()))
        .thenAnswer(call -> "reader".equals(((Principal) call.getArgument(0)).getName()));
    List<SearchDataSource.Result> batches = List.of(batch(entity(EntityType.TABLE, "table")));
    assertEquals(1, filter(source(batches), 0, 10, authorizer).size());
    assertTrue(
        PrincipalUtils.doAs(
                new UserPrincipal("other"),
                () ->
                    SearchResultFilter.filter(
                        source(batches), "lake", 0, 10, UNLIMITED_SCAN, authorizer))
            .isEmpty());
  }

  @Test
  void testNoGrantsReturnNoGroupsOrCounts() throws Exception {
    assertTrue(
        filter(
                source(List.of(batch(entity(EntityType.TABLE, "secret")))),
                0,
                10,
                mock(GravitinoAuthorizer.class))
            .isEmpty());
  }

  @Test
  void testAuthorizationErrorPropagatesInsteadOfReturningPartialData() {
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    when(authorizer.isOwner(any(), any(), any(), any()))
        .thenReturn(true)
        .thenThrow(new IllegalStateException("unavailable"));
    assertThrows(
        Exception.class,
        () ->
            filter(
                source(
                    List.of(
                        batch(entity(EntityType.TABLE, "visible")),
                        batch(entity(EntityType.TABLE, "secret")))),
                0,
                10,
                authorizer));
  }

  @Test
  void testScanLimitStopsAuthorizingAndMarksTheTotalAsTruncated() throws Exception {
    GravitinoAuthorizer authorizer = visibleToEveryone();
    List<SearchDataSource.Result> batches =
        List.of(
            new SearchDataSource.Result(
                List.of(entity(EntityType.TABLE, "first"), entity(EntityType.TABLE, "second")),
                EntityType.TABLE),
            new SearchDataSource.Result(
                List.of(entity(EntityType.TABLE, "third")), EntityType.TABLE));

    List<SearchEntitiesDTO> result = filter(source(batches), 0, 10, 2, authorizer);

    assertEquals(1, result.size());
    // The third match is beyond the scan limit, so it is neither counted nor returned.
    assertEquals(2, result.get(0).getTotalSize());
    assertTrue(result.get(0).isTotalSizeTruncated());
    assertEquals(
        List.of("first", "second"),
        result.get(0).getEntities().stream()
            .map(SearchEntityDTO::getEntityName)
            .collect(Collectors.toList()));
  }

  @Test
  void testTotalIsNotTruncatedWhenTheMatchesEndAtTheScanLimit() throws Exception {
    GravitinoAuthorizer authorizer = visibleToEveryone();
    List<SearchDataSource.Result> batches =
        List.of(
            new SearchDataSource.Result(
                List.of(entity(EntityType.TABLE, "first"), entity(EntityType.TABLE, "second")),
                EntityType.TABLE));

    List<SearchEntitiesDTO> result = filter(source(batches), 0, 10, 2, authorizer);

    assertEquals(2, result.get(0).getTotalSize());
    assertFalse(result.get(0).isTotalSizeTruncated());
  }

  @Test
  void testScanLimitAppliesToEachEntityTypeIndependently() throws Exception {
    GravitinoAuthorizer authorizer = visibleToEveryone();
    List<SearchDataSource.Result> batches =
        List.of(
            new SearchDataSource.Result(
                List.of(entity(EntityType.TABLE, "first"), entity(EntityType.TABLE, "second")),
                EntityType.TABLE),
            new SearchDataSource.Result(List.of(entity(EntityType.TAG, "tag")), EntityType.TAG));

    List<SearchEntitiesDTO> result = filter(source(batches), 0, 10, 1, authorizer);

    SearchEntitiesDTO tables =
        SearchEntitiesDTO.Builder.getSearchEntitiesDTOByType(result, EntityType.TABLE);
    SearchEntitiesDTO tags =
        SearchEntitiesDTO.Builder.getSearchEntitiesDTOByType(result, EntityType.TAG);
    assertEquals(1, tables.getTotalSize());
    assertTrue(tables.isTotalSizeTruncated());
    // The tag budget is untouched by the exhausted table budget.
    assertEquals(1, tags.getTotalSize());
    assertFalse(tags.isTotalSizeTruncated());
  }

  @Test
  void testTruncatedTypeIsReportedEvenWhenNothingOfItIsVisible() throws Exception {
    // Nothing is authorized, and the scan stops before the type is exhausted.
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    List<SearchDataSource.Result> batches =
        List.of(
            new SearchDataSource.Result(
                List.of(entity(EntityType.TABLE, "first")), EntityType.TABLE),
            new SearchDataSource.Result(
                List.of(entity(EntityType.TABLE, "second")), EntityType.TABLE));

    List<SearchEntitiesDTO> result = filter(source(batches), 0, 10, 1, authorizer);

    // Without the group the caller could not tell a truncated scan from "nothing matched".
    assertEquals(1, result.size());
    assertEquals(0, result.get(0).getTotalSize());
    assertTrue(result.get(0).isTotalSizeTruncated());
    assertTrue(result.get(0).getEntities().isEmpty());
  }

  @Test
  void testSpendingTheScanBudgetSkipsTheRestOfTheTypeInTheBackend() throws Exception {
    GravitinoAuthorizer authorizer = visibleToEveryone();
    SearchDataSource source = mock(SearchDataSource.class);
    when(source.nextBatch())
        .thenReturn(
            new SearchDataSource.Result(
                List.of(entity(EntityType.TABLE, "first"), entity(EntityType.TABLE, "second")),
                EntityType.TABLE))
        .thenReturn(SearchDataSource.Result.empty());

    List<SearchEntitiesDTO> result = filter(source, 0, 10, 1, authorizer);

    assertTrue(result.get(0).isTotalSizeTruncated());
    // The remaining matches are abandoned in the backend rather than fetched and discarded.
    verify(source).skipCurrentEntityType();
  }

  @Test
  void testNonPositiveScanLimitIsRejected() {
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    SearchDataSource source =
        source(
            List.of(
                new SearchDataSource.Result(
                    List.of(entity(EntityType.TABLE, "table")), EntityType.TABLE)));
    assertThrows(
        IllegalArgumentException.class,
        () -> SearchResultFilter.filter(source, "lake", 0, 10, 0, authorizer));
  }

  private static GravitinoAuthorizer visibleToEveryone() {
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    when(authorizer.isOwner(any(), eq("lake"), any(), any())).thenReturn(true);
    return authorizer;
  }

  private static List<SearchEntitiesDTO> filter(
      SearchDataSource source, int page, int size, GravitinoAuthorizer authorizer)
      throws Exception {
    return filter(source, page, size, UNLIMITED_SCAN, authorizer);
  }

  private static List<SearchEntitiesDTO> filter(
      SearchDataSource source, int page, int size, int maxScanSize, GravitinoAuthorizer authorizer)
      throws Exception {
    return PrincipalUtils.doAs(
        new UserPrincipal("reader"),
        () -> SearchResultFilter.filter(source, "lake", page, size, maxScanSize, authorizer));
  }

  private static SearchDataSource source(List<SearchDataSource.Result> batches) {
    var iterator = batches.iterator();
    return () -> iterator.hasNext() ? iterator.next() : SearchDataSource.Result.empty();
  }

  private static SearchDataSource.Result batch(SearchEntityDTO... entities) {
    return new SearchDataSource.Result(List.of(entities), EntityType.TABLE);
  }

  private static SearchEntityDTO entity(EntityType type, String name) {
    String fullName;
    switch (type) {
      case CATALOG:
      case USER:
      case GROUP:
      case ROLE:
      case TAG:
      case POLICY:
        fullName = name;
        break;
      case SCHEMA:
        fullName = "catalog." + name;
        break;
      default:
        fullName = "catalog.schema." + name;
    }
    return SearchEntityDTO.SearchEntityDTOBuilder.builder()
        .withEntityId(1)
        .withEntityType(type)
        .withMetalake("lake")
        .withEntityName(name)
        .withCatalogName("catalog")
        .withFullQualifiedName(
            type == EntityType.ROLE || type == EntityType.USER || type == EntityType.GROUP
                ? null
                : fullName)
        .build();
  }
}
