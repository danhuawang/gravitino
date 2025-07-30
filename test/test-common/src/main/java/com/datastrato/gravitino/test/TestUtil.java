/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.test;

import static com.datastrato.gravitino.test.OpenSearchContainer.LOG;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class TestUtil {

  public static CommandResult execCommand(String[] command) throws Exception {

    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    Process process = builder.start();

    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
    StringBuilder sb = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      sb.append(line).append("\n");
    }
    LOG.info("Initialization index template output: {}", sb);
    int exitCode = process.waitFor();
    return new CommandResult(exitCode, sb.toString());
  }

  public static class CommandResult {
    private final int exitCode;
    private final String output;

    CommandResult(int exitCode, String output) {
      this.exitCode = exitCode;
      this.output = output;
    }

    public int exitCode() {
      return exitCode;
    }

    public String output() {
      return output;
    }
  }
}
