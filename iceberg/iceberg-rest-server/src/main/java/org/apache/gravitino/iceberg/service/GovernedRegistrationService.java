/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.iceberg.service;

import com.google.common.base.Preconditions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.encryption.EncryptionAdmissionService;
import org.apache.gravitino.encryption.EncryptionAdmissionService.AdmissionRequest;
import org.apache.gravitino.encryption.EncryptionAdmissionService.EncryptionAdmission;
import org.apache.gravitino.encryption.EncryptionAdmissionService.Operation;
import org.apache.gravitino.iceberg.common.utils.IcebergIdentifierUtils;
import org.apache.gravitino.iceberg.service.cleanup.IcebergCleanupManager;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergCleanupHelper;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.gravitino.utils.HierarchicalSchemaUtil;
import org.apache.gravitino.utils.RequestContext;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.RegisterTableRequest;
import org.apache.iceberg.rest.responses.LoadTableResponse;

/**
 * The one governed registration path of the Iceberg REST catalog: reads the referenced metadata
 * file once, admits exactly that object through {@link EncryptionAdmissionService}, and publishes
 * that same object through the catalog wrapper. It is admitted as {@link Operation#REGISTER}, or
 * {@link Operation#OVERWRITE}, with the file's own key, provider and format version, never a
 * catalog key; the parsed object is published untouched, so the version travels only so a keyed
 * file below version 3 is refused (R7). A refusal by the backend after admission (for example an
 * overwrite of a table that is not enrolled) is rethrown unchanged with the admission's failure
 * audit facts.
 */
public final class GovernedRegistrationService {

  private final EncryptionAdmissionService admissionService;
  private final IcebergCatalogWrapperManager catalogWrapperManager;
  private final String metalakeName;
  private final Optional<IcebergCleanupManager> cleanupManager;

  /**
   * Creates the registration service.
   *
   * @param admissionService the admission service shared by every governed request
   * @param catalogWrapperManager manager owning the loaded IRC catalogs
   * @param metalakeName metalake containing the dynamically configured IRC catalogs
   * @param cleanupManager async purge manager, used to refuse a name still being purged
   */
  public GovernedRegistrationService(
      EncryptionAdmissionService admissionService,
      IcebergCatalogWrapperManager catalogWrapperManager,
      String metalakeName,
      Optional<IcebergCleanupManager> cleanupManager) {
    Preconditions.checkArgument(admissionService != null, "admissionService cannot be null");
    Preconditions.checkArgument(
        catalogWrapperManager != null, "catalogWrapperManager cannot be null");
    Preconditions.checkArgument(
        StringUtils.isNotBlank(metalakeName), "metalakeName cannot be blank");
    Preconditions.checkArgument(cleanupManager != null, "cleanupManager cannot be null");
    this.admissionService = admissionService;
    this.catalogWrapperManager = catalogWrapperManager;
    this.metalakeName = metalakeName;
    this.cleanupManager = cleanupManager;
  }

  /**
   * Reads, admits and publishes one registration.
   *
   * @param context the request context
   * @param namespace the namespace that will contain the table
   * @param request the register-table request
   * @return the registered table
   */
  public LoadTableResponse register(
      IcebergRequestContext context, Namespace namespace, RegisterTableRequest request) {
    request.validate();
    IcebergCleanupHelper.rejectIfBeingPurged(
        cleanupManager, context.catalogName(), namespace, request.name());

    CatalogWrapperForREST catalogWrapper =
        catalogWrapperManager.getCatalogWrapper(context.catalogName());
    TableMetadata metadata = catalogWrapper.readTableMetadata(request.metadataLocation());
    TableIdentifier identifier = TableIdentifier.of(namespace, request.name());
    EncryptionAdmission admission =
        admissionService.admit(
            new AdmissionRequest(
                IcebergIdentifierUtils.toGravitinoTableIdentifier(
                    metalakeName,
                    context.catalogName(),
                    identifier,
                    HierarchicalSchemaUtil.schemaSeparator()),
                request.overwrite() ? Operation.OVERWRITE : Operation.REGISTER,
                requestedProperties(metadata)));
    if (!admission.admitted()) {
      RuntimeException rejection =
          EncryptionAdmissionRejections.toRestException(admission.failure());
      RequestContext.setAuditExtras(
          EncryptionAdmissionService.auditExtrasWithError(admission.auditExtras(), rejection));
      throw rejection;
    }

    try {
      LoadTableResponse response =
          catalogWrapper.registerTable(
              identifier, metadata, request.overwrite(), context.requestCredentialVending());
      RequestContext.setAuditExtras(admission.auditExtras());
      return response;
    } catch (RuntimeException e) {
      if (admission.evaluated()) {
        RequestContext.setAuditExtras(
            EncryptionAdmissionService.auditExtrasForCreateFailure(admission.auditExtras(), e));
      }
      throw e;
    }
  }

  /**
   * The file's own key binding, any leftover protocol property and its format version, never a
   * catalog default: registration keeps the file's key. Values are read raw, so admission (not
   * metadata parsing) refuses a blank key or a leftover property as it would on a create, and a
   * keyed file below version 3, which Iceberg parses and registers without complaint.
   */
  private static Map<String, String> requestedProperties(TableMetadata metadata) {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put(IcebergConstants.FORMAT_VERSION, String.valueOf(metadata.formatVersion()));
    String keyId = metadata.properties().get(TableProperties.ENCRYPTION_TABLE_KEY);
    if (keyId != null) {
      properties.put(IcebergConstants.ENCRYPTION_KEY_ID, keyId);
    }
    for (String property :
        List.of(
            IcebergConstants.ENCRYPTION_KEY_PROVIDER,
            IcebergConstants.ENCRYPTION_KMS_API,
            IcebergConstants.ENCRYPTION_KEY_SOURCE)) {
      String value = metadata.properties().get(property);
      if (value != null) {
        properties.put(property, value);
      }
    }
    return properties;
  }
}
