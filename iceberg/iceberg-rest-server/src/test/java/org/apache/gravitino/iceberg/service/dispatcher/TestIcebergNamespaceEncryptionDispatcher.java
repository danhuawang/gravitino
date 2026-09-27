/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.iceberg.service.dispatcher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.apache.gravitino.encryption.EncryptionAdmissionService;
import org.apache.gravitino.iceberg.service.FederatedCatalogWrapper;
import org.apache.gravitino.iceberg.service.GovernedRegistrationService;
import org.apache.gravitino.iceberg.service.IcebergCatalogWrapperManager;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.gravitino.utils.RequestContext;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.ImmutableRegisterTableRequest;
import org.apache.iceberg.rest.requests.RegisterTableRequest;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the registration hook of the namespace chain: a governed deployment hands registration to
 * {@link GovernedRegistrationService}, every other deployment passes it through untouched, and so
 * does a federated catalog in any deployment.
 *
 * <p>Nothing here reads metadata, resolves policy or calls a KMS; that is the service's suite.
 */
public class TestIcebergNamespaceEncryptionDispatcher {

  private static final String CATALOG = "catalog";
  private static final String FEDERATED_CATALOG = "federated";
  private static final Predicate<String> FEDERATED = Set.of(FEDERATED_CATALOG)::contains;
  private static final String METADATA_LOCATION = "s3://warehouse/table/v1.metadata.json";
  private static final Namespace NAMESPACE = Namespace.of("schema");

  private IcebergNamespaceOperationDispatcher delegate;
  private GovernedRegistrationService registrationService;
  private IcebergRequestContext context;
  private RegisterTableRequest request;

  @AfterEach
  void clearRequestContext() {
    RequestContext.clear();
  }

  @BeforeEach
  void setUp() {
    delegate = mock(IcebergNamespaceOperationDispatcher.class);
    registrationService = mock(GovernedRegistrationService.class);
    context = mock(IcebergRequestContext.class);
    when(context.catalogName()).thenReturn(CATALOG);
    when(context.httpHeaders()).thenReturn(Collections.emptyMap());
    request =
        ImmutableRegisterTableRequest.builder()
            .name("table")
            .metadataLocation(METADATA_LOCATION)
            .build();
  }

  @Test
  void testStaticAndStandaloneRegistrationPassesThroughWithoutReadingMetadata() {
    IcebergNamespaceEncryptionDispatcher dispatcher =
        new IcebergNamespaceEncryptionDispatcher(delegate);
    LoadTableResponse response = mock(LoadTableResponse.class);
    when(delegate.registerTable(context, NAMESPACE, request)).thenReturn(response);

    Assertions.assertSame(response, dispatcher.registerTable(context, NAMESPACE, request));

    verify(delegate).registerTable(same(context), same(NAMESPACE), same(request));
    verifyNoInteractions(registrationService);
    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty());
  }

  @Test
  void testGovernedDeploymentRoutesRegistrationToTheRegistrationService() {
    IcebergNamespaceEncryptionDispatcher dispatcher =
        new IcebergNamespaceEncryptionDispatcher(delegate, registrationService, FEDERATED);
    LoadTableResponse response = mock(LoadTableResponse.class);
    when(registrationService.register(context, NAMESPACE, request)).thenReturn(response);

    Assertions.assertSame(response, dispatcher.registerTable(context, NAMESPACE, request));

    verify(registrationService).register(same(context), same(NAMESPACE), same(request));
    verifyNoInteractions(delegate);
  }

  /**
   * A federated catalog is pass-through: its registration bypasses the governed path, while a
   * catalog with a local backend in the same deployment is still governed.
   */
  @Test
  void testFederatedRegistrationBypassesTheGovernedPathWhileLocalCatalogsStayGoverned() {
    IcebergNamespaceEncryptionDispatcher dispatcher =
        new IcebergNamespaceEncryptionDispatcher(delegate, registrationService, FEDERATED);
    IcebergRequestContext federatedContext = mock(IcebergRequestContext.class);
    when(federatedContext.catalogName()).thenReturn(FEDERATED_CATALOG);
    LoadTableResponse forwarded = mock(LoadTableResponse.class);
    when(delegate.registerTable(federatedContext, NAMESPACE, request)).thenReturn(forwarded);
    LoadTableResponse governed = mock(LoadTableResponse.class);
    when(registrationService.register(context, NAMESPACE, request)).thenReturn(governed);

    Assertions.assertSame(
        forwarded, dispatcher.registerTable(federatedContext, NAMESPACE, request));
    Assertions.assertSame(governed, dispatcher.registerTable(context, NAMESPACE, request));

    verify(delegate).registerTable(same(federatedContext), same(NAMESPACE), same(request));
    verify(registrationService, never()).register(same(federatedContext), any(), any());
    verify(registrationService).register(same(context), same(NAMESPACE), same(request));
    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty());
  }

  /**
   * Runs a federated registration through the real governed service and executor: the request
   * reaches the remote catalog by location and this catalog never reads the metadata file, admits
   * anything or publishes parsed metadata.
   */
  @Test
  void testFederatedRegistrationIsForwardedByLocationWithoutReadingTheMetadataFile() {
    FederatedCatalogWrapper federated = mock(FederatedCatalogWrapper.class);
    LoadTableResponse response = mock(LoadTableResponse.class);
    when(federated.registerTable(NAMESPACE, request, false)).thenReturn(response);
    IcebergCatalogWrapperManager manager = mock(IcebergCatalogWrapperManager.class);
    when(manager.getCatalogWrapper(FEDERATED_CATALOG)).thenReturn(federated);
    EncryptionAdmissionService admissionService = mock(EncryptionAdmissionService.class);
    IcebergNamespaceEncryptionDispatcher dispatcher =
        new IcebergNamespaceEncryptionDispatcher(
            new IcebergNamespaceOperationExecutor(manager, Optional.empty()),
            new GovernedRegistrationService(
                admissionService, manager, "metalake", Optional.empty()),
            FEDERATED);
    IcebergRequestContext federatedContext = mock(IcebergRequestContext.class);
    when(federatedContext.catalogName()).thenReturn(FEDERATED_CATALOG);

    Assertions.assertSame(response, dispatcher.registerTable(federatedContext, NAMESPACE, request));

    verify(federated).registerTable(same(NAMESPACE), same(request), eq(false));
    verify(federated, never()).readTableMetadata(anyString());
    verify(federated, never())
        .registerTable(
            any(TableIdentifier.class), any(TableMetadata.class), anyBoolean(), anyBoolean());
    verifyNoInteractions(admissionService);
    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty());
  }

  @Test
  void testGovernedRegistrationFailurePropagatesUnchanged() {
    IcebergNamespaceEncryptionDispatcher dispatcher =
        new IcebergNamespaceEncryptionDispatcher(delegate, registrationService, FEDERATED);
    RuntimeException failure = new IllegalStateException("backend refused");
    when(registrationService.register(context, NAMESPACE, request)).thenThrow(failure);

    Assertions.assertSame(
        failure,
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> dispatcher.registerTable(context, NAMESPACE, request)));

    verifyNoInteractions(delegate);
  }

  @Test
  void testNullConstructorAndRegisterArgumentsAreRejected() {
    Assertions.assertEquals(
        "dispatcher cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new IcebergNamespaceEncryptionDispatcher(null))
            .getMessage());
    Assertions.assertEquals(
        "registrationService cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new IcebergNamespaceEncryptionDispatcher(delegate, null, FEDERATED))
            .getMessage());
    Assertions.assertEquals(
        "federatedCatalog cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new IcebergNamespaceEncryptionDispatcher(delegate, registrationService, null))
            .getMessage());

    IcebergNamespaceEncryptionDispatcher dispatcher =
        new IcebergNamespaceEncryptionDispatcher(delegate, registrationService, FEDERATED);
    Assertions.assertEquals(
        "context cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> dispatcher.registerTable(null, NAMESPACE, request))
            .getMessage());
    Assertions.assertEquals(
        "namespace cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> dispatcher.registerTable(context, null, request))
            .getMessage());
    Assertions.assertEquals(
        "registerTableRequest cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> dispatcher.registerTable(context, NAMESPACE, null))
            .getMessage());

    verifyNoInteractions(delegate, registrationService);
  }
}
