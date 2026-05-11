/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.license.tools;

import java.util.Arrays;

public class LicenseToolsCli {

  public static void main(String[] args) {
    if (args.length == 0) {
      System.err.println("Usage: license-tools <sign|inspect> [options]");
      System.exit(1);
    }
    String[] rest = Arrays.copyOfRange(args, 1, args.length);
    switch (args[0]) {
      case "sign":
        new SignCommand().execute(rest);
        break;
      case "inspect":
        new InspectCommand().execute(rest);
        break;
      default:
        System.err.println("Unknown command: " + args[0]);
        System.err.println("Usage: license-tools <sign|inspect> [options]");
        System.exit(1);
    }
  }
}
