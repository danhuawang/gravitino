/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.catalog.connection;

import com.datastrato.gravitino.catalog.connection.mapper.ConnectionTestResultMapper;
import com.datastrato.gravitino.catalog.connection.mapper.po.ConnectionTestResultPO;
import com.google.common.base.Preconditions;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.storage.relational.mapper.CatalogMetaMapper;
import org.apache.gravitino.storage.relational.po.CatalogPO;
import org.apache.gravitino.storage.relational.utils.POConverters;
import org.apache.gravitino.storage.relational.utils.SessionUtils;

/** Relational service for the latest result of each Catalog or credential connection test type. */
public final class CatalogConnectionTestMetaService implements ConnectionTestStore {

  private static final int MAX_ERROR_MESSAGE_LENGTH = 4096;
  private static final CatalogConnectionTestMetaService INSTANCE =
      new CatalogConnectionTestMetaService();

  private CatalogConnectionTestMetaService() {}

  /**
   * Returns the singleton relational connection test service.
   *
   * @return The singleton service.
   */
  public static CatalogConnectionTestMetaService getInstance() {
    return INSTANCE;
  }

  /** {@inheritDoc} */
  @Nullable
  @Override
  public CatalogConnectionSnapshot loadCatalogConnectionSnapshot(NameIdentifier identifier) {
    validateIdentifier(identifier);
    CatalogPO catalogPO =
        SessionUtils.getWithoutCommit(
            CatalogMetaMapper.class,
            mapper ->
                mapper.selectCatalogMetaByName(identifier.namespace().level(0), identifier.name()));
    return catalogPO == null ? null : toSnapshot(catalogPO);
  }

  /** {@inheritDoc} */
  @Override
  public boolean recordTestResult(
      CatalogConnectionSnapshot testedSnapshot,
      String testType,
      ConnectionTestResult.Status status,
      long lastTestedAt,
      @Nullable String errorMessage) {
    Preconditions.checkArgument(testedSnapshot != null, "Catalog snapshot cannot be null");
    ConnectionTestType.validate(testType);

    ConnectionTestResult result =
        new ConnectionTestResult(
            testedSnapshot.catalogId(),
            testType,
            testedSnapshot.catalogVersion(),
            status,
            lastTestedAt,
            truncateErrorMessage(errorMessage));
    return SessionUtils.doWithCommitAndFetchResult(
        ConnectionTestResultMapper.class,
        resultMapper -> {
          CatalogPO current = resultMapper.selectCatalogForUpdate(testedSnapshot.catalogId());
          if (!matches(current, testedSnapshot)) {
            return false;
          }

          ConnectionTestResultPO existing =
              resultMapper.select(testedSnapshot.catalogId(), testType);
          if (existing != null
              && Objects.equals(existing.getCatalogVersion(), testedSnapshot.catalogVersion())
              && existing.getLastTestedAt() > lastTestedAt) {
            return false;
          }

          ConnectionTestResultPO resultPO = toPO(result);
          if (existing == null) {
            resultMapper.insert(resultPO);
          } else {
            resultMapper.update(resultPO);
          }
          return true;
        });
  }

  /** {@inheritDoc} */
  @Override
  public Optional<ConnectionTestResult> getValidTestResult(
      NameIdentifier identifier, String testType) {
    validateIdentifier(identifier);
    ConnectionTestType.validate(testType);
    return SessionUtils.getWithoutCommit(
        CatalogMetaMapper.class,
        catalogMapper -> {
          CatalogPO catalogPO =
              catalogMapper.selectCatalogMetaByName(
                  identifier.namespace().level(0), identifier.name());
          if (catalogPO == null) {
            return Optional.empty();
          }
          ConnectionTestResultPO resultPO =
              SessionUtils.getWithoutCommit(
                  ConnectionTestResultMapper.class,
                  resultMapper -> resultMapper.select(catalogPO.getCatalogId(), testType));
          if (resultPO == null
              || !Objects.equals(resultPO.getCatalogVersion(), catalogPO.getCurrentVersion())) {
            return Optional.empty();
          }
          return Optional.of(fromPO(resultPO));
        });
  }

  /** {@inheritDoc} */
  @Override
  public void reconcileTestResultAfterCatalogChange(
      CatalogConnectionSnapshot before,
      CatalogConnectionSnapshot after,
      String testType,
      boolean preserve) {
    Preconditions.checkArgument(before != null, "Previous Catalog snapshot cannot be null");
    Preconditions.checkArgument(after != null, "Current Catalog snapshot cannot be null");
    Preconditions.checkArgument(
        before.catalogId() == after.catalogId(), "Catalog ID changed during metadata update");
    ConnectionTestType.validate(testType);
    if (before.catalogVersion() == after.catalogVersion()) {
      return;
    }

    SessionUtils.doWithCommit(
        ConnectionTestResultMapper.class,
        resultMapper -> {
          CatalogPO current = resultMapper.selectCatalogForUpdate(after.catalogId());
          if (!matches(current, after)) {
            return;
          }
          if (preserve) {
            resultMapper.updateVersion(
                after.catalogId(), testType, before.catalogVersion(), after.catalogVersion());
          } else {
            resultMapper.deleteByTypeAndVersion(
                after.catalogId(), testType, before.catalogVersion());
          }
        });
  }

  /** {@inheritDoc} */
  @Override
  public void reconcileCredentialTestResultsAfterCatalogChange(
      CatalogConnectionSnapshot before, CatalogConnectionSnapshot after, boolean preserve) {
    Preconditions.checkArgument(before != null, "Previous Catalog snapshot cannot be null");
    Preconditions.checkArgument(after != null, "Current Catalog snapshot cannot be null");
    Preconditions.checkArgument(
        before.catalogId() == after.catalogId(), "Catalog ID changed during metadata update");
    if (before.catalogVersion() == after.catalogVersion()) {
      return;
    }

    SessionUtils.doWithCommit(
        ConnectionTestResultMapper.class,
        resultMapper -> {
          CatalogPO current = resultMapper.selectCatalogForUpdate(after.catalogId());
          if (!matches(current, after)) {
            return;
          }
          resultMapper.list(after.catalogId()).stream()
              .filter(
                  result ->
                      ConnectionTestType.isCredential(result.getType())
                          && Objects.equals(result.getCatalogVersion(), before.catalogVersion()))
              .forEach(
                  result -> {
                    if (preserve) {
                      resultMapper.updateVersion(
                          after.catalogId(),
                          result.getType(),
                          before.catalogVersion(),
                          after.catalogVersion());
                    } else {
                      resultMapper.deleteByTypeAndVersion(
                          after.catalogId(), result.getType(), before.catalogVersion());
                    }
                  });
        });
  }

  /** {@inheritDoc} */
  @Override
  public int deleteOrphanedTestResults(int limit) {
    Preconditions.checkArgument(limit > 0, "Cleanup limit must be positive");
    return SessionUtils.doWithCommitAndFetchResult(
        ConnectionTestResultMapper.class, mapper -> mapper.deleteOrphaned(limit));
  }

  private static boolean matches(
      @Nullable CatalogPO catalogPO, CatalogConnectionSnapshot snapshot) {
    return catalogPO != null
        && Objects.equals(catalogPO.getCatalogId(), snapshot.catalogId())
        && Objects.equals(catalogPO.getCurrentVersion(), snapshot.catalogVersion())
        && Objects.equals(catalogPO.getCatalogName(), snapshot.catalogName());
  }

  private static CatalogConnectionSnapshot toSnapshot(CatalogPO catalogPO) {
    CatalogEntity catalogEntity = POConverters.fromCatalogPO(catalogPO, Namespace.empty());
    Map<String, String> properties = catalogEntity.getProperties();
    return new CatalogConnectionSnapshot(
        catalogPO.getCatalogId(),
        catalogPO.getCurrentVersion(),
        catalogPO.getCatalogName(),
        catalogPO.getProvider(),
        properties);
  }

  private static ConnectionTestResultPO toPO(ConnectionTestResult result) {
    return new ConnectionTestResultPO(
        result.catalogId(),
        result.testType(),
        result.catalogVersion(),
        result.status().name(),
        result.lastTestedAt(),
        result.errorMessage());
  }

  private static ConnectionTestResult fromPO(ConnectionTestResultPO resultPO) {
    return new ConnectionTestResult(
        resultPO.getCatalogId(),
        resultPO.getType(),
        resultPO.getCatalogVersion(),
        ConnectionTestResult.Status.valueOf(resultPO.getTestStatus()),
        resultPO.getLastTestedAt(),
        resultPO.getErrorMessage());
  }

  @Nullable
  private static String truncateErrorMessage(@Nullable String errorMessage) {
    if (errorMessage == null || errorMessage.length() <= MAX_ERROR_MESSAGE_LENGTH) {
      return errorMessage;
    }
    return errorMessage.substring(0, MAX_ERROR_MESSAGE_LENGTH);
  }

  private static void validateIdentifier(NameIdentifier identifier) {
    Preconditions.checkNotNull(identifier, "Catalog identifier cannot be null");
    Preconditions.checkArgument(
        identifier.namespace().length() == 1,
        "Catalog identifier must contain exactly one metalake level");
  }
}
