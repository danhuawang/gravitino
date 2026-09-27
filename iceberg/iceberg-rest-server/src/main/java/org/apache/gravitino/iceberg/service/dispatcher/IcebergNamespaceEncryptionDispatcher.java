/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.iceberg.service.dispatcher;

import com.google.common.base.Preconditions;
import java.util.Optional;
import java.util.function.Predicate;
import org.apache.gravitino.iceberg.service.GovernedRegistrationService;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.rest.requests.CreateNamespaceRequest;
import org.apache.iceberg.rest.requests.RegisterTableRequest;
import org.apache.iceberg.rest.requests.UpdateNamespacePropertiesRequest;
import org.apache.iceberg.rest.responses.CreateNamespaceResponse;
import org.apache.iceberg.rest.responses.GetNamespaceResponse;
import org.apache.iceberg.rest.responses.ListNamespacesResponse;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.rest.responses.UpdateNamespacePropertiesResponse;

/**
 * Namespace dispatcher hook for IRC table registration: in a governed deployment (see {@code
 * RESTService#shouldEnableGovernedTableCreate}) registration goes to {@link
 * GovernedRegistrationService}; otherwise, and for a federated catalog in every deployment ({@link
 * IcebergTableEncryptionDispatcher} says why), it passes through without this catalog reading the
 * metadata file.
 */
public final class IcebergNamespaceEncryptionDispatcher
    implements IcebergNamespaceOperationDispatcher {

  private final IcebergNamespaceOperationDispatcher dispatcher;
  private final Optional<GovernedRegistrationService> registrationService;
  private final Predicate<String> federatedCatalog;

  /**
   * Creates a pass-through dispatcher.
   *
   * @param dispatcher underlying namespace dispatcher
   */
  public IcebergNamespaceEncryptionDispatcher(IcebergNamespaceOperationDispatcher dispatcher) {
    Preconditions.checkArgument(dispatcher != null, "dispatcher cannot be null");
    this.dispatcher = dispatcher;
    this.registrationService = Optional.empty();
    this.federatedCatalog = catalogName -> false;
  }

  /**
   * Creates a dispatcher for a dynamically configured, Gravitino-managed deployment.
   *
   * @param dispatcher underlying namespace dispatcher
   * @param registrationService the governed registration path
   * @param federatedCatalog whether a catalog, by name, is federated and so passed through
   */
  public IcebergNamespaceEncryptionDispatcher(
      IcebergNamespaceOperationDispatcher dispatcher,
      GovernedRegistrationService registrationService,
      Predicate<String> federatedCatalog) {
    Preconditions.checkArgument(dispatcher != null, "dispatcher cannot be null");
    Preconditions.checkArgument(registrationService != null, "registrationService cannot be null");
    Preconditions.checkArgument(federatedCatalog != null, "federatedCatalog cannot be null");
    this.dispatcher = dispatcher;
    this.registrationService = Optional.of(registrationService);
    this.federatedCatalog = federatedCatalog;
  }

  @Override
  public CreateNamespaceResponse createNamespace(
      IcebergRequestContext context, CreateNamespaceRequest createNamespaceRequest) {
    return dispatcher.createNamespace(context, createNamespaceRequest);
  }

  @Override
  public UpdateNamespacePropertiesResponse updateNamespace(
      IcebergRequestContext context,
      Namespace namespace,
      UpdateNamespacePropertiesRequest updateNamespacePropertiesRequest) {
    return dispatcher.updateNamespace(context, namespace, updateNamespacePropertiesRequest);
  }

  @Override
  public void dropNamespace(IcebergRequestContext context, Namespace namespace) {
    dispatcher.dropNamespace(context, namespace);
  }

  @Override
  public GetNamespaceResponse loadNamespace(IcebergRequestContext context, Namespace namespace) {
    return dispatcher.loadNamespace(context, namespace);
  }

  @Override
  public ListNamespacesResponse listNamespaces(
      IcebergRequestContext context, Namespace parentNamespace) {
    return dispatcher.listNamespaces(context, parentNamespace);
  }

  @Override
  public boolean namespaceExists(IcebergRequestContext context, Namespace namespace) {
    return dispatcher.namespaceExists(context, namespace);
  }

  @Override
  public LoadTableResponse registerTable(
      IcebergRequestContext context,
      Namespace namespace,
      RegisterTableRequest registerTableRequest) {
    Preconditions.checkArgument(context != null, "context cannot be null");
    Preconditions.checkArgument(namespace != null, "namespace cannot be null");
    Preconditions.checkArgument(
        registerTableRequest != null, "registerTableRequest cannot be null");

    if (registrationService.isPresent() && !federatedCatalog.test(context.catalogName())) {
      return registrationService.get().register(context, namespace, registerTableRequest);
    }
    return dispatcher.registerTable(context, namespace, registerTableRequest);
  }
}
