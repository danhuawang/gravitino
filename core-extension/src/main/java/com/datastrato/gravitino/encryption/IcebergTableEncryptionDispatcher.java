/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.encryption;

import static org.apache.gravitino.utils.NameIdentifierUtil.getCatalogIdentifier;

import com.datastrato.gravitino.catalog.DatastratoTableDispatcher;
import com.datastrato.gravitino.preview.DataPreviewSensitiveTableException;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergCatalogBackend;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.connector.SupportsAtomicIntegrity;
import org.apache.gravitino.encryption.AtomicIntegrityCapability;
import org.apache.gravitino.encryption.EncryptionAdmissionService;
import org.apache.gravitino.encryption.EncryptionAdmissionService.AdmissionFailure;
import org.apache.gravitino.encryption.EncryptionAdmissionService.AdmissionRequest;
import org.apache.gravitino.encryption.EncryptionAdmissionService.EncryptionAdmission;
import org.apache.gravitino.encryption.EncryptionAdmissionService.Operation;
import org.apache.gravitino.encryption.MetadataIntegrityFailureClassifier;
import org.apache.gravitino.encryption.MetadataIntegrityFailureClassifier.CatalogFacts;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.exceptions.AmbiguousPolicyException;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distribution;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.utils.RequestContext;

/**
 * Translates a Gravitino API table create into an admission request and forwards what the {@link
 * EncryptionAdmissionService} admitted.
 *
 * <p>The dispatcher holds no policy logic; it recognises the built-in Iceberg provider, maps an
 * {@link AdmissionFailure} to this API's exception type and stashes the audit facts the service
 * built. Encryption-property alters are audited here because they are not admission decisions.
 *
 * <p>It is also where a metadata-integrity refusal the backend already raised gets <b>recorded</b>.
 * Nothing here detects a tamper or changes an outcome: every operation below rethrows the exact
 * instance it caught, so the status, body and message a client sees are untouched.
 */
public final class IcebergTableEncryptionDispatcher implements DatastratoTableDispatcher {

  private static final String LAKEHOUSE_ICEBERG_PROVIDER = "lakehouse-iceberg";

  private final DatastratoTableDispatcher dispatcher;
  private final CatalogManager catalogManager;
  private final EncryptionAdmissionService admissionService;

  /**
   * Creates the dispatcher.
   *
   * @param dispatcher underlying table dispatcher
   * @param catalogManager catalog manager used for the cheap built-in-provider pre-check
   * @param admissionService service owning the whole admission decision
   */
  public IcebergTableEncryptionDispatcher(
      DatastratoTableDispatcher dispatcher,
      CatalogManager catalogManager,
      EncryptionAdmissionService admissionService) {
    Preconditions.checkArgument(dispatcher != null, "dispatcher cannot be null");
    Preconditions.checkArgument(catalogManager != null, "catalogManager cannot be null");
    Preconditions.checkArgument(admissionService != null, "admissionService cannot be null");
    this.dispatcher = dispatcher;
    this.catalogManager = catalogManager;
    this.admissionService = admissionService;
  }

  /** {@inheritDoc} */
  @Override
  public NameIdentifier[] listTables(Namespace namespace) {
    return dispatcher.listTables(namespace);
  }

  /** {@inheritDoc} */
  @Override
  public Table loadTable(NameIdentifier ident) {
    CatalogFacts facts = catalogFacts(ident);
    try {
      return dispatcher.loadTable(ident);
    } catch (RuntimeException e) {
      recordIntegrityFailure(facts, e);
      throw e;
    }
  }

  /** {@inheritDoc} */
  @Override
  public Table loadTable(NameIdentifier ident, Set<Privilege.Name> requiredPrivilegeNames) {
    CatalogFacts facts = catalogFacts(ident);
    try {
      return dispatcher.loadTable(ident, requiredPrivilegeNames);
    } catch (RuntimeException e) {
      recordIntegrityFailure(facts, e);
      throw e;
    }
  }

  /** {@inheritDoc} */
  @Override
  public Table createTable(
      NameIdentifier ident,
      Column[] columns,
      String comment,
      Map<String, String> properties,
      Transform[] partitions,
      Distribution distribution,
      SortOrder[] sortOrders,
      Index[] indexes) {
    CatalogFacts facts = catalogFacts(ident);
    try {
      Catalog catalog = catalogManager.loadCatalog(getCatalogIdentifier(ident));
      if (!LAKEHOUSE_ICEBERG_PROVIDER.equals(catalog.provider())) {
        return dispatcher.createTable(
            ident, columns, comment, properties, partitions, distribution, sortOrders, indexes);
      }

      EncryptionAdmission admission =
          admissionService.admit(
              new AdmissionRequest(
                  ident,
                  Operation.CREATE,
                  properties == null ? Collections.emptyMap() : properties));
      if (!admission.evaluated()) {
        return dispatcher.createTable(
            ident, columns, comment, properties, partitions, distribution, sortOrders, indexes);
      }
      if (!admission.admitted()) {
        RuntimeException rejection = rejection(admission.failure());
        stashAuditExtras(
            EncryptionAdmissionService.auditExtrasWithError(admission.auditExtras(), rejection));
        throw rejection;
      }

      try {
        Table table =
            dispatcher.createTable(
                ident,
                columns,
                comment,
                admission.admittedProperties(),
                partitions,
                distribution,
                sortOrders,
                indexes);
        stashAuditExtras(admission.auditExtras());
        return table;
      } catch (RuntimeException e) {
        stashAuditExtras(
            EncryptionAdmissionService.auditExtrasForCreateFailure(admission.auditExtras(), e));
        throw e;
      }
    } catch (RuntimeException e) {
      recordIntegrityFailure(facts, e);
      throw e;
    }
  }

  /** {@inheritDoc} */
  @Override
  public Table alterTable(NameIdentifier ident, TableChange... changes) {
    CatalogFacts facts = catalogFacts(ident);
    try {
      TableChange[] encryptionChanges = encryptionChanges(changes);
      if (encryptionChanges.length == 0) {
        return dispatcher.alterTable(ident, changes);
      }

      Catalog catalog = catalogManager.loadCatalog(getCatalogIdentifier(ident));
      if (!LAKEHOUSE_ICEBERG_PROVIDER.equals(catalog.provider())) {
        return dispatcher.alterTable(ident, changes);
      }

      try {
        Table table = dispatcher.alterTable(ident, changes);
        stashAuditExtras(
            alterExtras(
                encryptionChanges,
                IcebergEncryptionAuditInfos.Reason.ENCRYPTION_PROPERTIES_UPDATED,
                null));
        return table;
      } catch (IllegalArgumentException e) {
        stashAuditExtras(
            alterExtras(
                encryptionChanges,
                IcebergEncryptionAuditInfos.Reason.ENCRYPTION_KEY_CHANGE_DENIED,
                e));
        throw e;
      } catch (RuntimeException e) {
        stashAuditExtras(
            alterExtras(encryptionChanges, IcebergEncryptionAuditInfos.Reason.OPERATION_FAILED, e));
        throw e;
      }
    } catch (RuntimeException e) {
      recordIntegrityFailure(facts, e);
      throw e;
    }
  }

  /** {@inheritDoc} */
  @Override
  public boolean dropTable(NameIdentifier ident) {
    return dispatcher.dropTable(ident);
  }

  /** {@inheritDoc} */
  @Override
  public boolean purgeTable(NameIdentifier ident) {
    return dispatcher.purgeTable(ident);
  }

  /** {@inheritDoc} */
  @Override
  public boolean tableExists(NameIdentifier ident) {
    return dispatcher.tableExists(ident);
  }

  /** {@inheritDoc} */
  @Override
  public List<TableEntity> listEntities(Namespace namespace) {
    return dispatcher.listEntities(namespace);
  }

  /** {@inheritDoc} */
  @Override
  public Map<String, Object>[] preview(
      NameIdentifier identifier, Entity.EntityType type, int resultLimit, Column[] columns)
      throws DataPreviewSensitiveTableException {
    return dispatcher.preview(identifier, type, resultLimit, columns);
  }

  /**
   * Records what a failure the backend has already detected and refused says about metadata
   * integrity, and nothing else.
   *
   * <p>The caller rethrows the very instance it caught, so no status, body, message or log line
   * changes. Gating is catalog-level and on {@code encryptionGoverned} alone — never on whether an
   * admission evaluated this particular request — so a governed catalog records a refusal even on a
   * plain load, and an ungoverned one records nothing even where the backend correctly detected the
   * tamper and refused.
   *
   * @param facts what the catalog is, or {@code null} when there is nothing to record against
   * @param failure the failure the caller is about to rethrow unchanged
   */
  private static void recordIntegrityFailure(
      @Nullable CatalogFacts facts, RuntimeException failure) {
    if (facts == null || !facts.encryptionGoverned()) {
      return;
    }
    MetadataIntegrityFailureClassifier.classify(failure, facts)
        .ifPresent(RequestContext::mergeAuditExtras);
  }

  /**
   * Reads what the catalog is, so a catch below can classify against it.
   *
   * <p>Everything comes from catalog properties. The connector's {@code IcebergCatalogWrapper} is
   * not an option here: {@code core-extension} does not depend on {@code iceberg-common}, and the
   * wrapper lives behind the isolated connector ClassLoader.
   *
   * <p>Returns {@code null} whenever nothing would be recorded anyway — another provider, an
   * ungoverned catalog, a backend that cannot be named — and whenever the lookup itself fails. The
   * provider guard is what keeps a Paimon catalog configured with {@code catalog-backend=hive} from
   * being labelled a Hive integrity signal: both {@code loadTable} overloads are reached for every
   * catalog type.
   *
   * <p>This runs <b>before</b> the {@code try} and swallows its own failures because a probe for an
   * audit fact must never replace the exception the caller is about to receive — least of all when
   * the catalog is what failed in the first place.
   */
  @Nullable
  private CatalogFacts catalogFacts(NameIdentifier ident) {
    try {
      NameIdentifier catalogIdent = getCatalogIdentifier(ident);
      Catalog catalog = catalogManager.loadCatalog(catalogIdent);
      if (!LAKEHOUSE_ICEBERG_PROVIDER.equals(catalog.provider())) {
        return null;
      }
      Map<String, String> properties = catalog.properties();
      if (properties == null
          || StringUtils.isBlank(properties.get(IcebergConstants.ENCRYPTION_KMS_SOURCE))) {
        // Ungoverned: nothing is recorded, so the capability probe's catalog lease is never taken.
        return null;
      }
      IcebergCatalogBackend backend = backend(properties.get(IcebergConstants.CATALOG_BACKEND));
      if (backend == null) {
        return null;
      }
      return CatalogFacts.of(backend, true, atomicIntegrityCapability(catalogIdent));
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** The configured backend, upper-cased as the Iceberg catalog wrapper does, or {@code null}. */
  @Nullable
  private static IcebergCatalogBackend backend(@Nullable String configured) {
    if (StringUtils.isBlank(configured)) {
      return null;
    }
    try {
      return IcebergCatalogBackend.valueOf(configured.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * Repeats the probe {@code DatastratoGravitinoEnv} makes for the admission service, rather than
   * widening that package-private method: the capability has a single source, the initialized
   * catalog, and the {@code instanceof} is on a core interface, so no connector class crosses the
   * isolated ClassLoader boundary.
   */
  private AtomicIntegrityCapability atomicIntegrityCapability(NameIdentifier catalogIdent) {
    return AtomicIntegrityCapability.of(
        catalogManager.doWithCatalog(
            catalogIdent,
            catalog ->
                catalog.ops() instanceof SupportsAtomicIntegrity
                    && ((SupportsAtomicIntegrity) catalog.ops()).atomicIntegrityAvailable()));
  }

  /** Only the exception class is this API's; kind, decision id and message are the service's. */
  private static RuntimeException rejection(AdmissionFailure failure) {
    switch (failure.kind()) {
      case AMBIGUOUS_POLICY:
        return new AmbiguousPolicyException(
            failure.matchedPolicyNames().toArray(new String[0]), "%s", failure.message());
      case KMS_UNAVAILABLE:
        return new ConnectionFailedException("%s", failure.message());
      default:
        return new IllegalArgumentException(failure.message());
    }
  }

  private static void stashAuditExtras(Map<String, String> extras) {
    RequestContext.mergeAuditExtras(extras);
  }

  private static Map<String, String> alterExtras(
      TableChange[] changes, IcebergEncryptionAuditInfos.Reason reason, @Nullable Exception error) {
    IcebergEncryptionAuditInfos.Builder extras =
        IcebergEncryptionAuditInfos.builder().withReason(reason);
    KmsReference attempted = attemptedProviderKey(changes);
    if (attempted != null) {
      extras.withAttemptedProviderKey(attempted);
    }
    if (error != null && reason == IcebergEncryptionAuditInfos.Reason.OPERATION_FAILED) {
      extras.withError(error);
    }
    return extras.build();
  }

  private static TableChange[] encryptionChanges(@Nullable TableChange[] changes) {
    if (changes == null) {
      return new TableChange[0];
    }
    return Arrays.stream(changes)
        .filter(IcebergTableEncryptionDispatcher::isEncryptionChange)
        .toArray(TableChange[]::new);
  }

  // The leftover Apache protocol properties carry no admission meaning here, but an alter that
  // touches one is still an encryption change worth auditing, which is why they are listed.
  private static boolean isEncryptionChange(@Nullable TableChange change) {
    String property = null;
    if (change instanceof TableChange.SetProperty) {
      property = ((TableChange.SetProperty) change).getProperty();
    } else if (change instanceof TableChange.RemoveProperty) {
      property = ((TableChange.RemoveProperty) change).getProperty();
    }
    return IcebergConstants.ENCRYPTION_KEY_PROVIDER.equals(property)
        || IcebergConstants.ENCRYPTION_KEY_ID.equals(property)
        || IcebergConstants.ENCRYPTION_KMS_API.equals(property)
        || IcebergConstants.ENCRYPTION_KEY_SOURCE.equals(property);
  }

  @Nullable
  private static KmsReference attemptedProviderKey(TableChange[] changes) {
    String provider = null;
    String keyId = null;
    for (TableChange change : changes) {
      if (change instanceof TableChange.SetProperty) {
        TableChange.SetProperty set = (TableChange.SetProperty) change;
        if (IcebergConstants.ENCRYPTION_KEY_PROVIDER.equals(set.getProperty())) {
          provider = set.getValue();
        } else if (IcebergConstants.ENCRYPTION_KEY_ID.equals(set.getProperty())) {
          keyId = set.getValue();
        }
      }
    }
    if (StringUtils.isAnyBlank(provider, keyId)) {
      return null;
    }
    try {
      return new KmsReference(provider, keyId);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
