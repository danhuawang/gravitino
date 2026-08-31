/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.encryption;

import static org.apache.gravitino.utils.NameIdentifierUtil.getCatalogIdentifier;

import com.datastrato.gravitino.catalog.DatastratoTableDispatcher;
import com.datastrato.gravitino.preview.DataPreviewSensitiveTableException;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.encryption.IcebergEncryptionDecision;
import org.apache.gravitino.encryption.IcebergEncryptionKmsKeyValidators;
import org.apache.gravitino.encryption.IcebergEncryptionPolicyEvaluator;
import org.apache.gravitino.encryption.IcebergEncryptionPolicyResolver;
import org.apache.gravitino.encryption.kms.KmsClient;
import org.apache.gravitino.encryption.kms.KmsClientRegistry;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.exceptions.AmbiguousPolicyException;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.policy.IcebergEncryptionContent;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distribution;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.utils.RequestContext;

/** Enforces governed Iceberg table encryption for creates received through the Gravitino API. */
public final class IcebergTableEncryptionDispatcher implements DatastratoTableDispatcher {

  private static final String ICEBERG_PROVIDER = "lakehouse-iceberg";
  private static final String CATALOG_KMS_SOURCE = "encryption-kms-source";
  private static final String FORMAT_VERSION = "format-version";
  private static final String ICEBERG_V3 = "3";

  private final DatastratoTableDispatcher dispatcher;
  private final CatalogManager catalogManager;
  private final IcebergEncryptionPolicyResolver policyResolver;
  private final IcebergEncryptionPolicyEvaluator policyEvaluator;

  /**
   * Creates an Iceberg table-encryption enforcement decorator.
   *
   * @param dispatcher underlying table dispatcher
   * @param catalogManager catalog manager used to inspect the target catalog
   * @param policyResolver resolver for the candidate table's effective encryption policy
   * @param kmsClientRegistry registry used for metadata-only KMS validation
   */
  public IcebergTableEncryptionDispatcher(
      DatastratoTableDispatcher dispatcher,
      CatalogManager catalogManager,
      IcebergEncryptionPolicyResolver policyResolver,
      KmsClientRegistry kmsClientRegistry) {
    this(dispatcher, catalogManager, policyResolver, registryValidator(kmsClientRegistry));
  }

  /**
   * Creates an Iceberg table-encryption enforcement decorator with an explicit KMS validation
   * boundary.
   *
   * @param dispatcher underlying table dispatcher
   * @param catalogManager catalog manager used to inspect the target catalog
   * @param policyResolver resolver for the candidate table's effective encryption policy
   * @param keyValidator metadata-only validator for same-catalog-source keys
   */
  public IcebergTableEncryptionDispatcher(
      DatastratoTableDispatcher dispatcher,
      CatalogManager catalogManager,
      IcebergEncryptionPolicyResolver policyResolver,
      IcebergEncryptionPolicyEvaluator.KmsKeyValidator keyValidator) {
    this(
        dispatcher,
        catalogManager,
        policyResolver,
        new IcebergEncryptionPolicyEvaluator(keyValidator));
  }

  IcebergTableEncryptionDispatcher(
      DatastratoTableDispatcher dispatcher,
      CatalogManager catalogManager,
      IcebergEncryptionPolicyResolver policyResolver,
      IcebergEncryptionPolicyEvaluator policyEvaluator) {
    Preconditions.checkArgument(dispatcher != null, "dispatcher cannot be null");
    Preconditions.checkArgument(catalogManager != null, "catalogManager cannot be null");
    Preconditions.checkArgument(policyResolver != null, "policyResolver cannot be null");
    Preconditions.checkArgument(policyEvaluator != null, "policyEvaluator cannot be null");
    this.dispatcher = dispatcher;
    this.catalogManager = catalogManager;
    this.policyResolver = policyResolver;
    this.policyEvaluator = policyEvaluator;
  }

  /** {@inheritDoc} */
  @Override
  public NameIdentifier[] listTables(Namespace namespace) {
    return dispatcher.listTables(namespace);
  }

  /** {@inheritDoc} */
  @Override
  public Table loadTable(NameIdentifier ident) {
    return dispatcher.loadTable(ident);
  }

  /** {@inheritDoc} */
  @Override
  public Table loadTable(NameIdentifier ident, Set<Privilege.Name> requiredPrivilegeNames) {
    return dispatcher.loadTable(ident, requiredPrivilegeNames);
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
    Catalog catalog = catalogManager.loadCatalog(getCatalogIdentifier(ident));
    if (!ICEBERG_PROVIDER.equals(catalog.provider())) {
      return dispatcher.createTable(
          ident, columns, comment, properties, partitions, distribution, sortOrders, indexes);
    }

    Map<String, String> requestedProperties =
        properties == null ? Collections.emptyMap() : properties;
    boolean encryptionInputSupplied =
        requestedProperties.containsKey(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_PROVIDER)
            || requestedProperties.containsKey(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID)
            || requestedProperties.containsKey(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KMS_API)
            || requestedProperties.containsKey(
                IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_SOURCE);
    Optional<PolicyEntity> resolvedPolicy;
    try {
      resolvedPolicy = policyResolver.resolve(ident);
    } catch (AmbiguousPolicyException e) {
      String decisionId = UUID.randomUUID().toString();
      IcebergEncryptionAuditInfos.Builder extras =
          IcebergEncryptionAuditInfos.builder()
              .withPolicyNames(e.matchedPolicyNames())
              .withPolicyEvaluation(
                  IcebergEncryptionAuditInfos.Compliance.VIOLATION,
                  IcebergEncryptionContent.Enforcement.DENY_CREATE)
              .withReason(IcebergEncryptionAuditInfos.Reason.AMBIGUOUS_POLICY);
      KmsReference requestedProviderKey = requestedProviderKey(requestedProperties);
      if (requestedProviderKey != null) {
        extras.withProviderKey(requestedProviderKey);
      }
      stashAuditExtras(extras.build());
      throw new AmbiguousPolicyException(
          e.matchedPolicyNames(), "%s decisionId=%s", e.getMessage(), decisionId);
    }

    if (!resolvedPolicy.isPresent() && !encryptionInputSupplied) {
      return dispatcher.createTable(
          ident, columns, comment, properties, partitions, distribution, sortOrders, indexes);
    }

    String catalogKmsSource =
        catalog.properties() == null ? null : catalog.properties().get(CATALOG_KMS_SOURCE);
    IcebergEncryptionDecision decision =
        policyEvaluator.evaluate(resolvedPolicy, catalogKmsSource, requestedProperties);
    RuntimeException terminalException = terminalException(ident, requestedProperties, decision);
    PolicyEntity policy = resolvedPolicy.orElse(null);
    if (terminalException != null) {
      stashAuditExtras(
          extras(
              requestedProperties,
              policy,
              decision,
              toAuditReason(decision.reason()),
              terminalException));
      throw terminalException;
    }

    Map<String, String> forwardedProperties =
        prepareForwardedProperties(requestedProperties, decision.validatedKey());
    try {
      Table table =
          dispatcher.createTable(
              ident,
              columns,
              comment,
              forwardedProperties,
              partitions,
              distribution,
              sortOrders,
              indexes);
      stashAuditExtras(
          extras(requestedProperties, policy, decision, toAuditReason(decision.reason()), null));
      return table;
    } catch (RuntimeException e) {
      stashAuditExtras(
          extras(
              requestedProperties,
              policy,
              decision,
              IcebergEncryptionAuditInfos.Reason.TABLE_CREATE_FAILED,
              e));
      throw e;
    }
  }

  /** {@inheritDoc} */
  @Override
  public Table alterTable(NameIdentifier ident, TableChange... changes) {
    TableChange[] encryptionChanges = encryptionChanges(changes);
    if (encryptionChanges.length == 0) {
      return dispatcher.alterTable(ident, changes);
    }

    Catalog catalog = catalogManager.loadCatalog(getCatalogIdentifier(ident));
    if (!ICEBERG_PROVIDER.equals(catalog.provider())) {
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

  private static void stashAuditExtras(Map<String, String> extras) {
    RequestContext.setAuditExtras(extras);
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

  private static Map<String, String> extras(
      Map<String, String> requestedProperties,
      @Nullable PolicyEntity policy,
      @Nullable IcebergEncryptionDecision decision,
      IcebergEncryptionAuditInfos.Reason reason,
      @Nullable Exception error) {
    IcebergEncryptionAuditInfos.Builder extras =
        IcebergEncryptionAuditInfos.builder().withReason(reason);
    if (policy != null) {
      extras.withPolicyName(policy.name());
    }
    if (decision != null
        && decision.enforcement() != null
        && decision.compliance() != IcebergEncryptionDecision.Compliance.NOT_APPLICABLE) {
      extras.withPolicyEvaluation(toAuditCompliance(decision.compliance()), decision.enforcement());
    }
    KmsReference providerKey = requestedProviderKey(requestedProperties);
    if (providerKey == null && decision != null) {
      providerKey = decision.validatedKey();
    }
    if (providerKey != null) {
      extras.withProviderKey(providerKey);
    }
    if (decision != null) {
      extras.withKmsValidation(toAuditKmsStatus(decision.kmsValidationStatus()));
    }
    if (error != null) {
      extras.withError(error);
    }
    return extras.build();
  }

  private static RuntimeException terminalException(
      NameIdentifier ident,
      Map<String, String> requestedProperties,
      IcebergEncryptionDecision decision) {
    if (decision.outcome() == IcebergEncryptionDecision.Outcome.SUCCEEDED) {
      return null;
    }

    String provider =
        requestedProperties.get(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_PROVIDER);
    String keyId = requestedProperties.get(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID);
    if (decision.reason() == IcebergEncryptionDecision.Reason.KMS_KEY_INVALID) {
      return new IllegalArgumentException(
          String.format(
              "KMS key '%s:%s' is not valid for encryption. Verify that the key exists, is "
                  + "enabled, and supports encryption. decisionId=%s",
              provider, keyId, decision.decisionId()));
    }
    if (decision.reason() == IcebergEncryptionDecision.Reason.KMS_SERVICE_UNAVAILABLE) {
      return new ConnectionFailedException(
          "KMS provider '%s' is unavailable; KMS key '%s' could not be validated. Retry later. "
              + "decisionId=%s",
          provider, keyId, decision.decisionId());
    }
    String subject = decision.policy() == null ? "input" : "policy";
    return new IllegalArgumentException(
        String.format(
            "Iceberg encryption %s denied table creation: decisionId=%s, reason=%s, table=%s",
            subject, decision.decisionId(), decision.reason().code(), ident));
  }

  private static Map<String, String> prepareForwardedProperties(
      Map<String, String> requestedProperties, @Nullable KmsReference validatedKey) {
    Map<String, String> forwarded = new LinkedHashMap<>(requestedProperties);
    // Preserve provider identity through Gravitino's table operation and event layers. IcebergTable
    // strips the companion provider property when it constructs the physical Iceberg REST request.
    // Rebuild key properties exclusively from the validated provider key so raw input is never
    // forwarded after evaluation.
    forwarded.remove(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KMS_API);
    forwarded.remove(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_SOURCE);
    forwarded.remove(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_PROVIDER);
    forwarded.remove(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID);
    forwarded.put(FORMAT_VERSION, ICEBERG_V3);
    if (validatedKey != null) {
      forwarded.put(
          IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_PROVIDER, validatedKey.provider());
      forwarded.put(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID, validatedKey.keyId());
    }
    return forwarded;
  }

  private static TableChange[] encryptionChanges(@Nullable TableChange[] changes) {
    if (changes == null) {
      return new TableChange[0];
    }
    return Arrays.stream(changes)
        .filter(IcebergTableEncryptionDispatcher::isEncryptionChange)
        .toArray(TableChange[]::new);
  }

  private static boolean isEncryptionChange(@Nullable TableChange change) {
    String property = null;
    if (change instanceof TableChange.SetProperty) {
      property = ((TableChange.SetProperty) change).getProperty();
    } else if (change instanceof TableChange.RemoveProperty) {
      property = ((TableChange.RemoveProperty) change).getProperty();
    }
    return IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_PROVIDER.equals(property)
        || IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID.equals(property)
        || IcebergEncryptionPolicyEvaluator.ENCRYPTION_KMS_API.equals(property)
        || IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_SOURCE.equals(property);
  }

  @Nullable
  private static KmsReference attemptedProviderKey(TableChange[] changes) {
    String provider = null;
    String keyId = null;
    for (TableChange change : changes) {
      if (change instanceof TableChange.SetProperty) {
        TableChange.SetProperty set = (TableChange.SetProperty) change;
        if (IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_PROVIDER.equals(set.getProperty())) {
          provider = set.getValue();
        } else if (IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID.equals(set.getProperty())) {
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

  @Nullable
  private static KmsReference requestedProviderKey(Map<String, String> properties) {
    String provider = properties.get(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_PROVIDER);
    String keyId = properties.get(IcebergEncryptionPolicyEvaluator.ENCRYPTION_KEY_ID);
    if (StringUtils.isAnyBlank(provider, keyId)) {
      return null;
    }
    try {
      return new KmsReference(provider, keyId);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * Adapts the configured KMS client registry to the evaluator's terminal validation contract.
   *
   * @param kmsClientRegistry configured KMS client registry
   * @return metadata-only key validator
   */
  public static IcebergEncryptionPolicyEvaluator.KmsKeyValidator registryValidator(
      KmsClientRegistry kmsClientRegistry) {
    Preconditions.checkArgument(kmsClientRegistry != null, "kmsClientRegistry cannot be null");
    return IcebergEncryptionKmsKeyValidators.fromRegistry(kmsClientRegistry);
  }

  static IcebergEncryptionPolicyEvaluator.KmsKeyValidator clientResolverValidator(
      Function<KmsReference, KmsClient> clientResolver) {
    Preconditions.checkArgument(clientResolver != null, "clientResolver cannot be null");
    return IcebergEncryptionKmsKeyValidators.fromClientResolver(clientResolver);
  }

  static IcebergEncryptionPolicyEvaluator.KmsKeyValidator clientValidator(KmsClient kmsClient) {
    Preconditions.checkArgument(kmsClient != null, "kmsClient cannot be null");
    return IcebergEncryptionKmsKeyValidators.fromClient(kmsClient);
  }

  private static IcebergEncryptionAuditInfos.Compliance toAuditCompliance(
      IcebergEncryptionDecision.Compliance compliance) {
    switch (compliance) {
      case COMPLIANT:
        return IcebergEncryptionAuditInfos.Compliance.COMPLIANT;
      case VIOLATION:
        return IcebergEncryptionAuditInfos.Compliance.VIOLATION;
      default:
        throw new IllegalArgumentException("Policy decision must have applicable compliance");
    }
  }

  private static IcebergEncryptionAuditInfos.Reason toAuditReason(
      IcebergEncryptionDecision.Reason reason) {
    return IcebergEncryptionAuditInfos.Reason.valueOf(reason.name());
  }

  private static IcebergEncryptionAuditInfos.KmsValidationStatus toAuditKmsStatus(
      IcebergEncryptionDecision.KmsValidationStatus status) {
    return IcebergEncryptionAuditInfos.KmsValidationStatus.valueOf(status.name());
  }
}
