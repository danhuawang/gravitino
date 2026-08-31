/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package org.apache.gravitino.iceberg.service.dispatcher;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.utils.RequestContext;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.PlanTableScanRequest;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.ListTablesResponse;
import org.apache.iceberg.rest.responses.LoadCredentialsResponse;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.rest.responses.PlanTableScanResponse;

/**
 * Enforces Iceberg table-encryption invariants and publishes semantic audit facts for IRC commits.
 *
 * <p>This dispatcher does not evaluate enterprise policies. Governed table creation remains in the
 * Gravitino table dispatcher, while this IRC boundary prevents an engine commit from changing the
 * immutable table master-key property and observes changes to Iceberg's encryption-key metadata.
 */
public final class IcebergTableEncryptionDispatcher implements IcebergTableOperationDispatcher {

  private final IcebergTableOperationDispatcher dispatcher;

  /**
   * Creates an IRC encryption invariant and audit dispatcher.
   *
   * @param dispatcher underlying IRC table dispatcher
   */
  public IcebergTableEncryptionDispatcher(IcebergTableOperationDispatcher dispatcher) {
    Preconditions.checkArgument(dispatcher != null, "dispatcher cannot be null");
    this.dispatcher = dispatcher;
  }

  /** {@inheritDoc} */
  @Override
  public LoadTableResponse createTable(
      IcebergRequestContext context, Namespace namespace, CreateTableRequest createTableRequest) {
    return dispatcher.createTable(context, namespace, createTableRequest);
  }

  /** {@inheritDoc} */
  @Override
  public LoadTableResponse updateTable(
      IcebergRequestContext context,
      TableIdentifier tableIdentifier,
      UpdateTableRequest updateTableRequest) {
    Preconditions.checkArgument(context != null, "context cannot be null");
    Preconditions.checkArgument(tableIdentifier != null, "tableIdentifier cannot be null");
    Preconditions.checkArgument(updateTableRequest != null, "updateTableRequest cannot be null");

    EncryptionKeyUpdates encryptionKeyUpdates = encryptionKeyUpdates(updateTableRequest);
    TableChange[] prohibitedChanges = prohibitedEncryptionPropertyChanges(updateTableRequest);
    if (prohibitedChanges.length > 0) {
      String correlationId = UUID.randomUUID().toString();
      IllegalArgumentException failure =
          new IllegalArgumentException(
              String.format(
                  "Iceberg table property '%s' is immutable and cannot be changed or removed: "
                      + "table=%s, correlationId=%s",
                  TableProperties.ENCRYPTION_TABLE_KEY, tableIdentifier, correlationId));
      stashAuditExtras(
          auditExtras(
              encryptionKeyUpdates,
              IcebergEncryptionAuditInfos.Reason.ENCRYPTION_KEY_CHANGE_DENIED,
              null,
              null));
      throw failure;
    }

    if (encryptionKeyUpdates.isEmpty()) {
      return dispatcher.updateTable(context, tableIdentifier, updateTableRequest);
    }

    try {
      LoadTableResponse response =
          dispatcher.updateTable(context, tableIdentifier, updateTableRequest);
      stashAuditExtras(
          commitExtras(
              encryptionKeyUpdates,
              IcebergEncryptionAuditInfos.Reason.ENCRYPTION_KEY_UPDATE_OBSERVED,
              updateTableRequest,
              response,
              null));
      return response;
    } catch (RuntimeException e) {
      stashAuditExtras(
          commitExtras(
              encryptionKeyUpdates,
              IcebergEncryptionAuditInfos.Reason.OPERATION_FAILED,
              updateTableRequest,
              null,
              e));
      throw e;
    }
  }

  /** {@inheritDoc} */
  @Override
  public void dropTable(
      IcebergRequestContext context, TableIdentifier tableIdentifier, boolean purgeRequested) {
    dispatcher.dropTable(context, tableIdentifier, purgeRequested);
  }

  /** {@inheritDoc} */
  @Override
  public LoadTableResponse loadTable(
      IcebergRequestContext context, TableIdentifier tableIdentifier) {
    return dispatcher.loadTable(context, tableIdentifier);
  }

  /** {@inheritDoc} */
  @Override
  public ListTablesResponse listTable(IcebergRequestContext context, Namespace namespace) {
    return dispatcher.listTable(context, namespace);
  }

  /** {@inheritDoc} */
  @Override
  public boolean tableExists(IcebergRequestContext context, TableIdentifier tableIdentifier) {
    return dispatcher.tableExists(context, tableIdentifier);
  }

  /** {@inheritDoc} */
  @Override
  public void renameTable(IcebergRequestContext context, RenameTableRequest renameTableRequest) {
    dispatcher.renameTable(context, renameTableRequest);
  }

  /** {@inheritDoc} */
  @Override
  public LoadCredentialsResponse getTableCredentials(
      IcebergRequestContext context, TableIdentifier tableIdentifier) {
    return dispatcher.getTableCredentials(context, tableIdentifier);
  }

  /** {@inheritDoc} */
  @Override
  public PlanTableScanResponse planTableScan(
      IcebergRequestContext context,
      TableIdentifier tableIdentifier,
      PlanTableScanRequest scanRequest) {
    return dispatcher.planTableScan(context, tableIdentifier, scanRequest);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<String> getTableMetadataLocation(
      IcebergRequestContext context, TableIdentifier tableIdentifier) {
    return dispatcher.getTableMetadataLocation(context, tableIdentifier);
  }

  private static void stashAuditExtras(Map<String, String> extras) {
    RequestContext.setAuditExtras(extras);
  }

  private static TableChange[] prohibitedEncryptionPropertyChanges(UpdateTableRequest request) {
    List<TableChange> changes = new ArrayList<>();
    for (MetadataUpdate update : request.updates()) {
      if (update instanceof MetadataUpdate.SetProperties) {
        MetadataUpdate.SetProperties setProperties = (MetadataUpdate.SetProperties) update;
        if (setProperties.updated().containsKey(TableProperties.ENCRYPTION_TABLE_KEY)) {
          changes.add(
              TableChange.setProperty(
                  TableProperties.ENCRYPTION_TABLE_KEY,
                  setProperties.updated().get(TableProperties.ENCRYPTION_TABLE_KEY)));
        }
      } else if (update instanceof MetadataUpdate.RemoveProperties) {
        MetadataUpdate.RemoveProperties removeProperties = (MetadataUpdate.RemoveProperties) update;
        if (removeProperties.removed().contains(TableProperties.ENCRYPTION_TABLE_KEY)) {
          changes.add(TableChange.removeProperty(TableProperties.ENCRYPTION_TABLE_KEY));
        }
      }
    }
    return changes.toArray(new TableChange[0]);
  }

  private static EncryptionKeyUpdates encryptionKeyUpdates(UpdateTableRequest request) {
    List<String> addedKeyIds = new ArrayList<>();
    List<String> removedKeyIds = new ArrayList<>();
    for (MetadataUpdate update : request.updates()) {
      if (update instanceof MetadataUpdate.AddEncryptionKey) {
        addedKeyIds.add(
            requireEncryptionKeyId(((MetadataUpdate.AddEncryptionKey) update).key().keyId()));
      } else if (update instanceof MetadataUpdate.RemoveEncryptionKey) {
        removedKeyIds.add(
            requireEncryptionKeyId(((MetadataUpdate.RemoveEncryptionKey) update).keyId()));
      }
    }
    return new EncryptionKeyUpdates(
        addedKeyIds.toArray(new String[0]), removedKeyIds.toArray(new String[0]));
  }

  private static String requireEncryptionKeyId(@Nullable String keyId) {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(keyId), "Iceberg encryption key ID cannot be blank");
    return keyId;
  }

  private static Map<String, String> commitExtras(
      EncryptionKeyUpdates keyUpdates,
      IcebergEncryptionAuditInfos.Reason reason,
      UpdateTableRequest request,
      @Nullable LoadTableResponse response,
      @Nullable Exception error) {
    return auditExtras(keyUpdates, reason, error, commitId(request, response));
  }

  private static Map<String, String> auditExtras(
      EncryptionKeyUpdates keyUpdates,
      IcebergEncryptionAuditInfos.Reason reason,
      @Nullable Exception error,
      @Nullable String commitId) {
    IcebergEncryptionAuditInfos.Builder extras =
        IcebergEncryptionAuditInfos.builder().withReason(reason);
    if (error != null) {
      extras.withError(error);
    }
    ImmutableMap.Builder<String, String> merged = ImmutableMap.builder();
    merged.putAll(extras.build());
    if (keyUpdates.addedKeyIds.length > 0) {
      merged.put(
          IcebergEncryptionAuditInfos.PREFIX + "addedEncryptionKeyIds",
          String.join(",", keyUpdates.addedKeyIds));
    }
    if (keyUpdates.removedKeyIds.length > 0) {
      merged.put(
          IcebergEncryptionAuditInfos.PREFIX + "removedEncryptionKeyIds",
          String.join(",", keyUpdates.removedKeyIds));
    }
    if (commitId != null) {
      merged.put(IcebergEncryptionAuditInfos.PREFIX + "commitId", commitId);
    }
    return merged.build();
  }

  @Nullable
  private static String commitId(UpdateTableRequest request, @Nullable LoadTableResponse response) {
    if (response != null && StringUtils.isNotBlank(response.metadataLocation())) {
      return response.metadataLocation();
    }
    return request.updates().stream()
        .filter(MetadataUpdate.AddSnapshot.class::isInstance)
        .map(MetadataUpdate.AddSnapshot.class::cast)
        .map(addSnapshot -> Long.toString(addSnapshot.snapshot().snapshotId()))
        .findFirst()
        .orElse(null);
  }

  private static final class EncryptionKeyUpdates {

    private final String[] addedKeyIds;
    private final String[] removedKeyIds;

    private EncryptionKeyUpdates(String[] addedKeyIds, String[] removedKeyIds) {
      this.addedKeyIds = addedKeyIds;
      this.removedKeyIds = removedKeyIds;
    }

    private boolean isEmpty() {
      return addedKeyIds.length == 0 && removedKeyIds.length == 0;
    }
  }
}
