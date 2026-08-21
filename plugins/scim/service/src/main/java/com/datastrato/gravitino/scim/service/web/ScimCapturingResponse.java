/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;

/**
 * Records HTTP status and a truncated SCIM error body after {@code chain.doFilter} returns.
 *
 * <p>SCIMple writes through {@link #getOutputStream()}; {@link ScimHttpResponses#writeError} sets
 * detail via {@link #noteDetail} instead of copying the writer stream.
 */
final class ScimCapturingResponse extends HttpServletResponseWrapper {

  private static final int MAX_BODY_BYTES = 2048;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ByteArrayOutputStream body = new ByteArrayOutputStream();
  private final boolean captureBody;
  private int status = HttpServletResponse.SC_OK;
  @Nullable private String notedDetail;
  @Nullable private ServletOutputStream outputStream;
  @Nullable private PrintWriter printWriter;

  ScimCapturingResponse(HttpServletResponse response, boolean captureBody) {
    super(response);
    this.captureBody = captureBody;
  }

  int status() {
    return status;
  }

  /**
   * Records the SCIM error detail from {@link ScimHttpResponses#writeError} without copying the
   * writer stream.
   *
   * @param detail human-readable error detail
   */
  void noteDetail(String detail) {
    this.notedDetail = detail;
  }

  Failure failure(@Nullable Throwable chainException) {
    if (printWriter != null) {
      printWriter.flush();
    }
    if (outputStream != null) {
      try {
        outputStream.flush();
      } catch (IOException ignored) {
        // Best-effort capture of the error body.
      }
    }
    String jsonDetail = null;
    String scimType = null;
    if (body.size() > 0) {
      try {
        JsonNode node = MAPPER.readTree(body.toByteArray());
        if (node != null && node.isObject()) {
          jsonDetail = StringUtils.trimToNull(node.path("detail").asText(null));
          scimType = StringUtils.trimToNull(node.path("scimType").asText(null));
        }
      } catch (Exception ignored) {
        // Non-JSON or truncated bodies are ignored; status and noted detail still apply.
      }
    }
    return new Failure(
        status,
        scimType,
        StringUtils.firstNonBlank(
            chainException == null ? null : chainException.getMessage(), notedDetail, jsonDetail));
  }

  @Override
  public ServletOutputStream getOutputStream() throws IOException {
    if (printWriter != null) {
      throw new IllegalStateException("getWriter() has already been called");
    }
    if (outputStream == null) {
      ServletOutputStream delegate = super.getOutputStream();
      outputStream =
          captureBody ? new TeeServletOutputStream(delegate, body, MAX_BODY_BYTES) : delegate;
    }
    return outputStream;
  }

  @Override
  public PrintWriter getWriter() throws IOException {
    if (outputStream != null) {
      throw new IllegalStateException("getOutputStream() has already been called");
    }
    if (printWriter == null) {
      printWriter = super.getWriter();
    }
    return printWriter;
  }

  @Override
  public void setStatus(int sc) {
    status = sc;
    super.setStatus(sc);
  }

  @Override
  public void sendError(int sc) throws IOException {
    status = sc;
    super.sendError(sc);
  }

  @Override
  public void sendError(int sc, String msg) throws IOException {
    status = sc;
    notedDetail = msg;
    super.sendError(sc, msg);
  }

  @Override
  public void reset() {
    status = HttpServletResponse.SC_OK;
    notedDetail = null;
    body.reset();
    super.reset();
  }

  @Override
  public void resetBuffer() {
    body.reset();
    super.resetBuffer();
  }

  private static final class TeeServletOutputStream extends ServletOutputStream {
    private final ServletOutputStream delegate;
    private final ByteArrayOutputStream captured;
    private final int maxBytes;

    private TeeServletOutputStream(
        ServletOutputStream delegate, ByteArrayOutputStream captured, int maxBytes) {
      this.delegate = delegate;
      this.captured = captured;
      this.maxBytes = maxBytes;
    }

    @Override
    public boolean isReady() {
      return delegate.isReady();
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
      delegate.setWriteListener(writeListener);
    }

    @Override
    public void write(int b) throws IOException {
      delegate.write(b);
      if (captured.size() < maxBytes) {
        captured.write(b);
      }
    }

    @Override
    public void write(byte[] buffer, int off, int len) throws IOException {
      delegate.write(buffer, off, len);
      int copy = Math.min(len, maxBytes - captured.size());
      if (copy > 0) {
        captured.write(buffer, off, copy);
      }
    }

    @Override
    public void flush() throws IOException {
      delegate.flush();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }

  record Failure(int status, @Nullable String scimType, @Nullable String detail) {}
}
