/*
 * Copyright 2026 Datastrato Inc.
 */

package org.apache.gravitino.iceberg.service.dispatcher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.api.EventListenerPlugin;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.listener.api.event.IcebergRegisterTableEvent;
import org.apache.gravitino.listener.api.event.IcebergRegisterTableFailureEvent;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.gravitino.utils.RequestContext;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.rest.requests.ImmutableRegisterTableRequest;
import org.apache.iceberg.rest.requests.RegisterTableRequest;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Tests audit-extra propagation on register-table terminal events. */
public class TestIcebergNamespaceEventDispatcher {

  private static final String METALAKE = "metalake";
  private static final String CATALOG = "catalog";
  private static final Namespace NAMESPACE = Namespace.of("namespace");
  private static final String TABLE = "table";
  private static final String REQUEST_HEADER = "X-Request-Id";
  private static final String REQUEST_HEADER_VALUE = "request-1";
  private static final String AUDIT_KEY = "icebergEncryption.reason";
  private static final String AUDIT_VALUE = "COMPLIANT";

  @AfterEach
  void cleanup() {
    RequestContext.clear();
  }

  @Test
  void testRegisterSuccessAttachesAndConsumesAuditExtras() {
    RecordingListener listener = new RecordingListener();
    IcebergNamespaceOperationDispatcher inner = mock(IcebergNamespaceOperationDispatcher.class);
    when(inner.registerTable(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              RequestContext.setAuditExtras(ImmutableMap.of(AUDIT_KEY, AUDIT_VALUE));
              return loadTableResponse();
            });
    IcebergNamespaceEventDispatcher dispatcher = dispatcher(listener, inner);

    dispatcher.registerTable(requestContext(), NAMESPACE, registerTableRequest());

    Event terminal = listener.popPostEvent();
    Assertions.assertEquals(IcebergRegisterTableEvent.class, terminal.getClass());
    assertAuditExtrasAttachedAndConsumed(terminal);
  }

  @Test
  void testRegisterFailureAttachesAndConsumesAuditExtras() {
    RecordingListener listener = new RecordingListener();
    IcebergNamespaceOperationDispatcher inner = mock(IcebergNamespaceOperationDispatcher.class);
    RuntimeException failure = new RuntimeException("registration denied");
    when(inner.registerTable(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              RequestContext.setAuditExtras(ImmutableMap.of(AUDIT_KEY, AUDIT_VALUE));
              throw failure;
            });
    IcebergNamespaceEventDispatcher dispatcher = dispatcher(listener, inner);

    RuntimeException thrown =
        Assertions.assertThrows(
            RuntimeException.class,
            () -> dispatcher.registerTable(requestContext(), NAMESPACE, registerTableRequest()));

    Assertions.assertSame(failure, thrown);
    Event terminal = listener.popPostEvent();
    Assertions.assertEquals(IcebergRegisterTableFailureEvent.class, terminal.getClass());
    assertAuditExtrasAttachedAndConsumed(terminal);
  }

  private static void assertAuditExtrasAttachedAndConsumed(Event terminal) {
    Assertions.assertEquals(AUDIT_VALUE, terminal.customInfo().get(AUDIT_KEY));
    Assertions.assertEquals(REQUEST_HEADER_VALUE, terminal.customInfo().get(REQUEST_HEADER));
    Assertions.assertTrue(
        RequestContext.takeAuditExtras().isEmpty(), "register dispatch must consume audit extras");
  }

  private static IcebergNamespaceEventDispatcher dispatcher(
      EventListenerPlugin listener, IcebergNamespaceOperationDispatcher inner) {
    return new IcebergNamespaceEventDispatcher(
        inner, new EventBus(Collections.singletonList(listener)), METALAKE);
  }

  private static IcebergRequestContext requestContext() {
    return new IcebergRequestContext(
        requestWithHeader(REQUEST_HEADER, REQUEST_HEADER_VALUE), CATALOG);
  }

  private static RegisterTableRequest registerTableRequest() {
    return ImmutableRegisterTableRequest.builder()
        .name(TABLE)
        .metadataLocation("file:///tmp/table/metadata/v1.metadata.json")
        .build();
  }

  private static LoadTableResponse loadTableResponse() {
    Schema schema =
        new Schema(Types.NestedField.required(1, "id", Types.LongType.get(), "record id"));
    TableMetadata metadata =
        TableMetadata.newTableMetadata(
            schema, PartitionSpec.unpartitioned(), "file:///tmp/table", Collections.emptyMap());
    return LoadTableResponse.builder().withTableMetadata(metadata).build();
  }

  private static HttpServletRequest requestWithHeader(String name, String value) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    Enumeration<String> headerNames = Collections.enumeration(Collections.singleton(name));
    when(request.getRemoteHost()).thenReturn("localhost");
    when(request.getHeaderNames()).thenReturn(headerNames);
    when(request.getHeader(name)).thenReturn(value);
    return request;
  }

  private static final class RecordingListener implements EventListenerPlugin {

    private final LinkedList<Event> postEvents = new LinkedList<>();

    @Override
    public void init(Map<String, String> properties) {}

    @Override
    public void start() {}

    @Override
    public void stop() {}

    @Override
    public void onPostEvent(Event event) {
      postEvents.add(event);
    }

    private Event popPostEvent() {
      Assertions.assertFalse(postEvents.isEmpty(), "No post events to pop");
      return postEvents.removeLast();
    }
  }
}
