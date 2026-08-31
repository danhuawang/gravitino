/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.license.tools;

import com.datastrato.gravitino.license.LicensePayload;
import com.datastrato.gravitino.license.LicenseVerifier;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

public class InspectCommand {

  private final LicenseVerifier verifier;

  public InspectCommand() {
    this.verifier = LicenseVerifier.fromClasspath();
  }

  InspectCommand(LicenseVerifier verifier) {
    this.verifier = verifier;
  }

  public void execute(String[] args) {
    Options options = new Options();
    options.addOption(
        Option.builder().longOpt("key").hasArg().required().desc("License key string").build());
    CommandLine cli;
    try {
      cli = new DefaultParser().parse(options, args);
    } catch (ParseException e) {
      System.err.println("Error: " + e.getMessage());
      new HelpFormatter().printHelp("license-tools inspect", options);
      System.exit(1);
      return;
    }
    try {
      LicensePayload p = verifier.verify(cli.getOptionValue("key"));
      long todayDays = LocalDate.now(ZoneOffset.UTC).toEpochDay();
      long expiresAtDays = p.getExpiresAtDays();
      long gracePeriodDays = p.getGracePeriodDays();
      LocalDate graceEnds = LocalDate.ofEpochDay(expiresAtDays + gracePeriodDays);

      String status;
      String daysNote;
      if (todayDays <= expiresAtDays) {
        long daysLeft = expiresAtDays - todayDays;
        status = "VALID";
        daysNote = daysLeft + " days remaining";
      } else if (todayDays <= expiresAtDays + gracePeriodDays) {
        long daysLeft = expiresAtDays + gracePeriodDays - todayDays;
        status = "IN_GRACE_PERIOD";
        daysNote = daysLeft + " grace days remaining";
      } else {
        long daysOver = todayDays - (expiresAtDays + gracePeriodDays);
        status = "EXPIRED";
        daysNote = daysOver + " days past grace period";
      }

      System.out.printf("License ID  : %s%n", p.getLicenseId());
      System.out.printf("Issued to   : %s%n", p.getIssuedTo());
      System.out.printf("Issued at   : %s%n", LocalDate.ofEpochDay(p.getIssuedAtDays()));
      System.out.printf("Expires at  : %s%n", LocalDate.ofEpochDay(expiresAtDays));
      System.out.printf("Grace ends  : %s%n", graceEnds);
      System.out.printf("Grace days  : %d%n", gracePeriodDays);
      System.out.printf(
          "Max nodes   : %s%n", p.getMaxNodes() == -1 ? "unlimited" : p.getMaxNodes());
      System.out.printf("Status      : %s (%s)%n", status, daysNote);
      System.out.printf("Signature   : VALID%n");
    } catch (Exception e) {
      System.err.println("Inspect failed: " + e.getMessage());
      System.exit(1);
    }
  }
}
