/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.audit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.EventListenerManager;

/**
 * Test-only audit writer that keeps every written line in memory: the rendering the configured
 * formatter produced, redaction included, exactly as a file writer would log it.
 *
 * <p>The queue is static because {@link AuditLogManager} builds its writer by reflection, so a test
 * cannot hold the instance. Tests that use it must {@link #clear()} before and after each case.
 */
public final class CapturingAuditLogWriter implements AuditLogWriter {

  private static final ConcurrentLinkedQueue<String> LINES = new ConcurrentLinkedQueue<>();

  private Formatter formatter;

  /**
   * Wires the real audit chain the server builds: an {@link EventListenerManager} whose event bus
   * reaches an {@link AuditLogManager} writing through the given formatter into this writer.
   *
   * @param formatterClass the audit formatter under test
   * @return the event bus a dispatcher publishes to
   */
  public static EventBus auditEventBus(Class<? extends Formatter> formatterClass) {
    Config config = new Config(false) {};
    config.set(Configs.AUDIT_LOG_ENABLED_CONF, true);
    config.set(Configs.AUDIT_LOG_WRITER_CLASS_NAME, CapturingAuditLogWriter.class.getName());
    config.set(Configs.AUDIT_LOG_FORMATTER_CLASS_NAME, formatterClass.getName());
    EventListenerManager eventListenerManager = new EventListenerManager();
    eventListenerManager.init(new HashMap<>());
    eventListenerManager.start();
    new AuditLogManager().init(config, eventListenerManager);
    return eventListenerManager.createEventBus();
  }

  /** Clears all captured lines. */
  public static void clear() {
    LINES.clear();
  }

  /**
   * Returns the lines written so far, oldest first.
   *
   * @return a snapshot of the written lines
   */
  public static List<String> lines() {
    return new ArrayList<>(LINES);
  }

  @Override
  public Formatter getFormatter() {
    return formatter;
  }

  @Override
  public void init(Formatter formatter, Map<String, String> properties) {
    this.formatter = formatter;
  }

  @Override
  public void doWrite(AuditLog auditLog) {
    LINES.add(auditLog.toString());
  }

  @Override
  public void close() {}

  @Override
  public String name() {
    return "capturing";
  }
}
