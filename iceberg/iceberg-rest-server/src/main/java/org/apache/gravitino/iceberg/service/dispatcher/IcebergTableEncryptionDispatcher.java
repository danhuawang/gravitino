/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.iceberg.service.dispatcher;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.encryption.EncryptionAdmissionService;
import org.apache.gravitino.encryption.EncryptionAdmissionService.AdmissionFailure;
import org.apache.gravitino.encryption.EncryptionAdmissionService.AdmissionRequest;
import org.apache.gravitino.encryption.EncryptionAdmissionService.EncryptionAdmission;
import org.apache.gravitino.encryption.EncryptionAdmissionService.Operation;
import org.apache.gravitino.encryption.MetadataIntegrityFailureClassifier;
import org.apache.gravitino.encryption.MetadataIntegrityFailureClassifier.CatalogFacts;
import org.apache.gravitino.iceberg.common.utils.IcebergIdentifierUtils;
import org.apache.gravitino.iceberg.service.EncryptionAdmissionRejections;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.gravitino.utils.HierarchicalSchemaUtil;
import org.apache.gravitino.utils.RequestContext;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.UpdateRequirement;
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
 * Enforces table-encryption invariants at the IRC boundary and forwards every create, staged or
 * not, and every staged-create commit to the shared {@link EncryptionAdmissionService}, which owns
 * the whole decision, including how a staged create is prepared at {@link Operation#STAGE} and
 * decided at its {@code assert-create} commit, {@link Operation#STAGED_COMMIT}. This dispatcher
 * only translates the IRC request shape into an {@link AdmissionRequest}, forwards {@link
 * EncryptionAdmission#admittedProperties()} rather than the properties the request carried, maps an
 * {@link AdmissionFailure} to an Iceberg REST exception and stashes the audit facts the service
 * built.
 *
 * <p>Without an admission service (see {@code RESTService#shouldEnableGovernedTableCreate}) only
 * the invariants apply. A federated catalog is pass-through in either mode: the remote Iceberg REST
 * catalog performs every commit and owns encryption for its tables, so its requests are forwarded
 * untouched, with no admission, immutable-property check or encryption audit.
 *
 * <p><b>A refused tamper is recorded, not detected here.</b> Hive and JDBC refuse a tampered
 * metadata pointer on their own; the dispatcher classifies that failure on its way out, records it
 * for a governed catalog, and rethrows it unchanged.
 */
public final class IcebergTableEncryptionDispatcher implements IcebergTableOperationDispatcher {

  // The key identity the service validated.
  private static final List<String> ENCRYPTION_KEY_BINDING =
      List.of(IcebergConstants.ENCRYPTION_KEY_PROVIDER, IcebergConstants.ENCRYPTION_KEY_ID);

  // Every encryption-relevant table property the service reads out of a request.
  private static final List<String> ENCRYPTION_PROPERTIES =
      List.of(
          IcebergConstants.ENCRYPTION_KEY_PROVIDER,
          IcebergConstants.ENCRYPTION_KEY_ID,
          IcebergConstants.ENCRYPTION_KMS_API,
          IcebergConstants.ENCRYPTION_KEY_SOURCE);

  // The key identity, which an ordinary commit may neither set nor remove: repointing the provider
  // rebinds the table to another KMS as effectively as changing the key id.
  private static final List<String> IMMUTABLE_ENCRYPTION_PROPERTIES =
      List.of(TableProperties.ENCRYPTION_TABLE_KEY, IcebergConstants.ENCRYPTION_KEY_PROVIDER);

  // The legacy protocol spellings, which an ordinary commit may not set, because setting one could
  // name another KMS for the table. Removing one is allowed: a removal cannot rebind a KMS.
  private static final List<String> SET_ONLY_GUARDED_ENCRYPTION_PROPERTIES =
      List.of(IcebergConstants.ENCRYPTION_KMS_API, IcebergConstants.ENCRYPTION_KEY_SOURCE);

  private final IcebergTableOperationDispatcher dispatcher;
  private final String metalakeName;
  private final Optional<EncryptionAdmissionService> admissionService;
  private final Predicate<String> federatedCatalog;
  private final Optional<Function<String, CatalogFacts>> catalogFacts;

  /**
   * Creates an IRC encryption invariant and audit dispatcher that knows nothing about its catalogs'
   * backends, and so records no metadata-integrity failure.
   *
   * @param dispatcher underlying IRC table dispatcher
   * @param federatedCatalog whether a catalog, by name, is federated and so passed through
   */
  public IcebergTableEncryptionDispatcher(
      IcebergTableOperationDispatcher dispatcher, Predicate<String> federatedCatalog) {
    this(dispatcher, federatedCatalog, null);
  }

  /**
   * Creates an IRC encryption invariant and audit dispatcher with no admission service.
   *
   * @param dispatcher underlying IRC table dispatcher
   * @param federatedCatalog tells, by catalog name, whether a catalog federates another Iceberg
   *     REST catalog, whose requests are forwarded untouched
   * @param catalogFacts names, by catalog name, what a catalog is for the purpose of classifying a
   *     failure, or {@code null} when the deployment cannot answer
   */
  public IcebergTableEncryptionDispatcher(
      IcebergTableOperationDispatcher dispatcher,
      Predicate<String> federatedCatalog,
      @Nullable Function<String, CatalogFacts> catalogFacts) {
    this(dispatcher, "invariant-only", null, federatedCatalog, catalogFacts);
  }

  /**
   * Creates an IRC encryption dispatcher that admits creates through the shared service.
   *
   * @param dispatcher underlying IRC table dispatcher
   * @param metalakeName metalake containing the dynamically configured IRC catalogs
   * @param admissionService service owning the whole admission decision, or {@code null} for
   *     invariant-only handling
   * @param federatedCatalog whether a catalog, by name, is federated and so passed through
   * @param catalogFacts what a catalog is, by name, for classifying a failure, or {@code null} when
   *     the deployment cannot answer
   */
  public IcebergTableEncryptionDispatcher(
      IcebergTableOperationDispatcher dispatcher,
      String metalakeName,
      @Nullable EncryptionAdmissionService admissionService,
      Predicate<String> federatedCatalog,
      @Nullable Function<String, CatalogFacts> catalogFacts) {
    Preconditions.checkArgument(dispatcher != null, "dispatcher cannot be null");
    Preconditions.checkArgument(
        StringUtils.isNotBlank(metalakeName), "metalakeName cannot be blank");
    Preconditions.checkArgument(federatedCatalog != null, "federatedCatalog cannot be null");
    this.dispatcher = dispatcher;
    this.metalakeName = metalakeName;
    this.admissionService = Optional.ofNullable(admissionService);
    this.federatedCatalog = federatedCatalog;
    this.catalogFacts = Optional.ofNullable(catalogFacts);
  }

  /** {@inheritDoc} */
  @Override
  public LoadTableResponse createTable(
      IcebergRequestContext context, Namespace namespace, CreateTableRequest createTableRequest) {
    Preconditions.checkArgument(context != null, "context cannot be null");
    Preconditions.checkArgument(namespace != null, "namespace cannot be null");
    Preconditions.checkArgument(createTableRequest != null, "createTableRequest cannot be null");

    CatalogFacts facts = catalogFacts(context);
    try {
      if (!admissionService.isPresent() || isFederated(context)) {
        return dispatcher.createTable(context, namespace, createTableRequest);
      }

      TableIdentifier tableIdentifier = TableIdentifier.of(namespace, createTableRequest.name());
      Operation operation = createTableRequest.stageCreate() ? Operation.STAGE : Operation.CREATE;
      EncryptionAdmission admission =
          admit(context, tableIdentifier, operation, createTableRequest.properties());
      return runAdmitted(
          admission,
          admitted ->
              dispatcher.createTable(
                  context, namespace, withAdmittedProperties(createTableRequest, admitted)));
    } catch (RuntimeException e) {
      recordMetadataIntegrityFailure(facts, e);
      throw e;
    }
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

    CatalogFacts facts = catalogFacts(context);
    try {
      if (isFederated(context)) {
        return dispatcher.updateTable(context, tableIdentifier, updateTableRequest);
      }

      boolean stagedCreateCommit = isStagedCreateCommit(updateTableRequest);
      if (stagedCreateCommit && admissionService.isPresent()) {
        EncryptionAdmission admission =
            admit(
                context,
                tableIdentifier,
                Operation.STAGED_COMMIT,
                stagedCreateProperties(updateTableRequest));
        return runAdmitted(
            admission,
            admitted ->
                updateAfterInvariants(
                    context,
                    tableIdentifier,
                    withAdmittedProperties(updateTableRequest, admitted),
                    true));
      }

      return updateAfterInvariants(
          context, tableIdentifier, updateTableRequest, stagedCreateCommit);
    } catch (RuntimeException e) {
      recordMetadataIntegrityFailure(facts, e);
      throw e;
    }
  }

  private LoadTableResponse updateAfterInvariants(
      IcebergRequestContext context,
      TableIdentifier tableIdentifier,
      UpdateTableRequest updateTableRequest,
      boolean stagedCreateCommit) {
    EncryptionKeyUpdates encryptionKeyUpdates = encryptionKeyUpdates(updateTableRequest);
    List<String> prohibitedChanges = prohibitedEncryptionPropertyChanges(updateTableRequest);
    if (!stagedCreateCommit && !prohibitedChanges.isEmpty()) {
      String decisionId = nextDecisionId();
      IllegalArgumentException failure =
          new IllegalArgumentException(
              String.format(
                  "Iceberg table property '%s' is immutable and cannot be changed or removed: "
                      + "table=%s, decisionId=%s",
                  String.join("', '", prohibitedChanges), tableIdentifier, decisionId));
      stashAuditExtras(
          auditExtras(
              encryptionKeyUpdates,
              IcebergEncryptionAuditInfos.Reason.ENCRYPTION_KEY_CHANGE_DENIED,
              decisionId,
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
    CatalogFacts facts = catalogFacts(context);
    try {
      return dispatcher.loadTable(context, tableIdentifier);
    } catch (RuntimeException e) {
      recordMetadataIntegrityFailure(facts, e);
      throw e;
    }
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

  private boolean isFederated(IcebergRequestContext context) {
    return federatedCatalog.test(context.catalogName());
  }

  /**
   * Names what the request's catalog is, before the operation runs.
   *
   * <p>Resolved up front, never from inside an operation catch: the lookup reaches the catalog
   * wrapper and can itself fail. A failed audit probe yields {@code null}, so it can neither
   * prevent the operation nor replace the exception the caller is about to receive.
   *
   * <p>A deployment that cannot answer, and a request carrying no context, yield {@code null}, so
   * nothing is recorded rather than something being guessed.
   */
  @Nullable
  private CatalogFacts catalogFacts(@Nullable IcebergRequestContext context) {
    if (context == null || !catalogFacts.isPresent()) {
      return null;
    }
    try {
      return catalogFacts.get().apply(context.catalogName());
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  /**
   * Records what a failed operation says about metadata integrity, and nothing else.
   *
   * <p>Detection already happened in the backend: Hive and JDBC refuse a tampered metadata pointer
   * on their own, and this only carries that refusal into the audit record, which the reason codes
   * otherwise never reach. The failure is classified, never inspected for a message, and the caller
   * rethrows it unchanged.
   *
   * <p>The gate is the catalog, not the request: a governed catalog records even on a plain load,
   * where no admission was evaluated, and an ungoverned one records nothing even on a backend that
   * detected the tamper. A federated catalog is never governed here, because its integrity belongs
   * to the remote catalog that commits for it.
   */
  private static void recordMetadataIntegrityFailure(
      @Nullable CatalogFacts facts, RuntimeException failure) {
    if (facts == null || !facts.encryptionGoverned()) {
      return;
    }
    MetadataIntegrityFailureClassifier.classify(failure, facts)
        .ifPresent(RequestContext::mergeAuditExtras);
  }

  private EncryptionAdmission admit(
      IcebergRequestContext context,
      TableIdentifier tableIdentifier,
      Operation operation,
      @Nullable Map<String, String> requestedProperties) {
    NameIdentifier identifier = gravitinoIdentifier(context, tableIdentifier);
    return admissionService
        .get()
        .admit(
            new AdmissionRequest(
                identifier,
                operation,
                requestedProperties == null ? Collections.emptyMap() : requestedProperties));
  }

  /**
   * Runs an admitted operation against the properties the service admitted, not the ones the
   * request carried: provider completion, protocol-property stripping and the resolved format
   * version are the service's decisions and would otherwise be thrown away on this path.
   */
  private LoadTableResponse runAdmitted(
      EncryptionAdmission admission, Function<EncryptionAdmission, LoadTableResponse> operation) {
    if (!admission.evaluated()) {
      return operation.apply(admission);
    }
    if (!admission.admitted()) {
      RuntimeException rejection =
          EncryptionAdmissionRejections.toRestException(admission.failure());
      stashAuditExtras(
          EncryptionAdmissionService.auditExtrasWithError(admission.auditExtras(), rejection));
      throw rejection;
    }

    try {
      LoadTableResponse response = operation.apply(admission);
      stashAuditExtras(admission.auditExtras());
      return response;
    } catch (RuntimeException e) {
      // A prepared stage carries no audit facts, and a stage the backend then fails still created
      // nothing, so there is no decision to correlate the failure with.
      if (!admission.auditExtras().isEmpty()) {
        stashAuditExtras(
            EncryptionAdmissionService.auditExtrasForCreateFailure(admission.auditExtras(), e));
      }
      throw e;
    }
  }

  /**
   * Rebuilds the request around the admitted properties; the stage flag must survive the rebuild.
   */
  private static CreateTableRequest withAdmittedProperties(
      CreateTableRequest request, EncryptionAdmission admission) {
    if (!admission.evaluated()) {
      return request;
    }
    CreateTableRequest.Builder builder =
        CreateTableRequest.builder()
            .withName(request.name())
            .withSchema(request.schema())
            .withPartitionSpec(request.spec())
            .withWriteOrder(request.writeOrder())
            .withLocation(request.location())
            .setProperties(admission.admittedProperties());
    if (request.stageCreate()) {
      builder = builder.stageCreate();
    }
    return builder.build();
  }

  /**
   * Appends the admitted key binding and, when the commit names no format version, the resolved
   * version to the commit that publishes a staged create; a create forwards both as properties, but
   * a commit carries updates. Nothing is stripped: the service reads the map the commit folds to,
   * so a commit still setting a leftover Apache protocol property is refused, not cleaned up.
   */
  private static UpdateTableRequest withAdmittedProperties(
      UpdateTableRequest request, EncryptionAdmission admission) {
    if (!admission.evaluated()) {
      return request;
    }
    Map<String, String> admittedKeyBinding = admittedKeyBinding(admission);
    MetadataUpdate formatVersionUpgrade = formatVersionUpgrade(request, admission);
    if (admittedKeyBinding.isEmpty() && formatVersionUpgrade == null) {
      return request;
    }

    List<MetadataUpdate> updates = new ArrayList<>(request.updates());
    if (formatVersionUpgrade != null) {
      updates.add(formatVersionUpgrade);
    }
    if (!admittedKeyBinding.isEmpty()) {
      updates.add(new MetadataUpdate.SetProperties(admittedKeyBinding));
    }
    // The identifier is part of the commit on the paths that carry one, so rebuilding the request
    // must not drop it.
    return request.identifier() == null
        ? new UpdateTableRequest(request.requirements(), updates)
        : UpdateTableRequest.create(request.identifier(), request.requirements(), updates);
  }

  /**
   * Returns the {@code upgrade-format-version} update carrying the resolved version, or {@code
   * null} when the commit names one. The guard is the commit's shape, never a comparison: R6 writes
   * a version only where the request named none, and a comparison would raise a lower named
   * version. Iceberg applies no catalog property to an {@code assert-create} commit, so a version
   * the commit names is the version it publishes; one encryption cannot use was refused already.
   */
  @Nullable
  private static MetadataUpdate formatVersionUpgrade(
      UpdateTableRequest request, EncryptionAdmission admission) {
    if (committedFormatVersion(request) > 0) {
      return null;
    }
    int resolved =
        formatVersion(admission.admittedProperties().get(IcebergConstants.FORMAT_VERSION));
    return resolved > 0 ? new MetadataUpdate.UpgradeFormatVersion(resolved) : null;
  }

  /**
   * Returns the highest format version the commit's {@code upgrade-format-version} updates name,
   * because that is where Iceberg's builder leaves the table, or {@code 0} when it names none.
   */
  private static int committedFormatVersion(UpdateTableRequest request) {
    int committed = 0;
    for (MetadataUpdate update : request.updates()) {
      if (update instanceof MetadataUpdate.UpgradeFormatVersion) {
        committed =
            Math.max(committed, ((MetadataUpdate.UpgradeFormatVersion) update).formatVersion());
      }
    }
    return committed;
  }

  private static int formatVersion(@Nullable String value) {
    if (StringUtils.isBlank(value)) {
      return 0;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      // An unreadable format version is treated as absent: the admitted binding still has to reach
      // the commit, and the backend remains the authority on what version it accepts.
      return 0;
    }
  }

  private static Map<String, String> admittedKeyBinding(EncryptionAdmission admission) {
    Map<String, String> binding = new LinkedHashMap<>();
    for (String property : ENCRYPTION_KEY_BINDING) {
      String value = admission.admittedProperties().get(property);
      if (value != null) {
        binding.put(property, value);
      }
    }
    return binding;
  }

  private static boolean isStagedCreateCommit(UpdateTableRequest request) {
    return request.requirements().stream()
        .anyMatch(UpdateRequirement.AssertTableDoesNotExist.class::isInstance);
  }

  /**
   * Folds the commit's property updates, in order, into the encryption-relevant properties the
   * staged table ends up with, so the service is handed the same map a create of that table would
   * hand it: reading only the key id would hide a leftover Apache protocol property the commit
   * sets, and the same input would be refused on a create and admitted here. The version the commit
   * upgrades to is handed in as {@code format-version}, the property a create names it with.
   */
  private static Map<String, String> stagedCreateProperties(UpdateTableRequest request) {
    Map<String, String> resulting = new LinkedHashMap<>();
    for (MetadataUpdate update : request.updates()) {
      if (update instanceof MetadataUpdate.SetProperties) {
        Map<String, String> updated = ((MetadataUpdate.SetProperties) update).updated();
        for (String property : ENCRYPTION_PROPERTIES) {
          if (updated.containsKey(property)) {
            resulting.put(property, updated.get(property));
          }
        }
      } else if (update instanceof MetadataUpdate.RemoveProperties) {
        ((MetadataUpdate.RemoveProperties) update).removed().forEach(resulting::remove);
      }
    }
    int committedVersion = committedFormatVersion(request);
    if (committedVersion > 0) {
      resulting.put(IcebergConstants.FORMAT_VERSION, String.valueOf(committedVersion));
    }
    return resulting;
  }

  // A staged commit stashes twice: the commit's key facts from updateAfterInvariants, then the
  // admission's. The stash merges, so the first survives the second.
  private static void stashAuditExtras(Map<String, String> extras) {
    RequestContext.mergeAuditExtras(extras);
  }

  private NameIdentifier gravitinoIdentifier(
      IcebergRequestContext requestContext, TableIdentifier tableIdentifier) {
    return IcebergIdentifierUtils.toGravitinoTableIdentifier(
        metalakeName,
        requestContext.catalogName(),
        tableIdentifier,
        HierarchicalSchemaUtil.schemaSeparator());
  }

  /** Guarded properties an ordinary commit changes; applies to every table, in both modes. */
  private static List<String> prohibitedEncryptionPropertyChanges(UpdateTableRequest request) {
    Set<String> changed = new LinkedHashSet<>();
    for (MetadataUpdate update : request.updates()) {
      if (update instanceof MetadataUpdate.SetProperties) {
        Map<String, String> updated = ((MetadataUpdate.SetProperties) update).updated();
        IMMUTABLE_ENCRYPTION_PROPERTIES.stream().filter(updated::containsKey).forEach(changed::add);
        SET_ONLY_GUARDED_ENCRYPTION_PROPERTIES.stream()
            .filter(updated::containsKey)
            .forEach(changed::add);
      } else if (update instanceof MetadataUpdate.RemoveProperties) {
        Set<String> removed = ((MetadataUpdate.RemoveProperties) update).removed();
        IMMUTABLE_ENCRYPTION_PROPERTIES.stream().filter(removed::contains).forEach(changed::add);
      }
    }
    return new ArrayList<>(changed);
  }

  private String nextDecisionId() {
    return admissionService
        .map(EncryptionAdmissionService::nextDecisionId)
        .orElseGet(EncryptionAdmissionService::newDecisionId);
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
    return auditExtras(keyUpdates, reason, null, error, commitId(request, response));
  }

  private static Map<String, String> auditExtras(
      EncryptionKeyUpdates keyUpdates,
      IcebergEncryptionAuditInfos.Reason reason,
      @Nullable String decisionId,
      @Nullable Exception error,
      @Nullable String commitId) {
    IcebergEncryptionAuditInfos.Builder extras =
        IcebergEncryptionAuditInfos.builder().withReason(reason);
    if (decisionId != null) {
      extras.withDecisionId(decisionId);
    }
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
