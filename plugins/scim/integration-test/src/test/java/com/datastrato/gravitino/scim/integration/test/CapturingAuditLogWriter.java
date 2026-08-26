/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.integration.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.gravitino.audit.AuditLog;
import org.apache.gravitino.audit.AuditLogWriter;
import org.apache.gravitino.audit.Formatter;

/**
 * Test-only audit writer that records formatted audit lines in memory for assertions.
 *
 * <p>Configured via {@code gravitino.audit.writer.className} in SCIM audit ITs.
 */
public final class CapturingAuditLogWriter implements AuditLogWriter {

  private static final ConcurrentLinkedQueue<String> LINES = new ConcurrentLinkedQueue<>();

  private Formatter formatter;

  /** Clears all captured lines. */
  public static void clear() {
    LINES.clear();
  }

  /** Returns a snapshot of captured formatted audit lines. */
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
