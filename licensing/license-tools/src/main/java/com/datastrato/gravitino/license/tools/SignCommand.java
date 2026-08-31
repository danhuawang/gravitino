/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.license.tools;

import com.google.cloud.kms.v1.AsymmetricSignRequest;
import com.google.cloud.kms.v1.Digest;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

public class SignCommand {

  @FunctionalInterface
  public interface Signer {
    byte[] sign(byte[] payload) throws Exception;
  }

  public void execute(String[] args) {
    Options options = new Options();
    options.addOption(
        Option.builder()
            .longOpt("kms-key-version")
            .hasArg()
            .required()
            .desc(
                "Full GCP KMS key version resource name, e.g. "
                    + "projects/P/locations/L/keyRings/R/cryptoKeys/K/cryptoKeyVersions/1")
            .build());
    options.addOption(Option.builder().longOpt("issued-to").hasArg().required().build());
    options.addOption(
        Option.builder().longOpt("expires").hasArg().required().desc("YYYY-MM-DD").build());
    options.addOption(Option.builder().longOpt("grace-days").hasArg().build());
    options.addOption(Option.builder().longOpt("max-nodes").hasArg().build());
    CommandLine cli;
    try {
      cli = new DefaultParser().parse(options, args);
    } catch (ParseException e) {
      System.err.println("Error: " + e.getMessage());
      new HelpFormatter().printHelp("license-tools sign", options);
      System.exit(1);
      return;
    }
    String kmsKeyVersion = cli.getOptionValue("kms-key-version");
    try (KeyManagementServiceClient kmsClient = KeyManagementServiceClient.create()) {
      Signer kmsSigner =
          payload -> {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            AsymmetricSignRequest request =
                AsymmetricSignRequest.newBuilder()
                    .setName(kmsKeyVersion)
                    .setDigest(Digest.newBuilder().setSha256(ByteString.copyFrom(digest)).build())
                    .build();
            return kmsClient.asymmetricSign(request).getSignature().toByteArray();
          };
      String key =
          buildKeyString(
              kmsSigner,
              cli.getOptionValue("issued-to"),
              cli.getOptionValue("expires"),
              Integer.parseInt(cli.getOptionValue("grace-days", "30")),
              Integer.parseInt(cli.getOptionValue("max-nodes", "-1")));
      System.out.println(key);
    } catch (Exception e) {
      System.err.println("Signing failed: " + e.getMessage());
      System.exit(1);
    }
  }

  String buildKeyString(
      Signer signer, String issuedTo, String expiresAt, int graceDays, int maxNodes)
      throws Exception {
    long issuedAtDays = LocalDate.now(ZoneOffset.UTC).toEpochDay();
    long expiresAtDays = LocalDate.parse(expiresAt).toEpochDay();
    validate(issuedTo, issuedAtDays, expiresAtDays, graceDays, maxNodes);
    byte[] payload =
        LicenseSerializer.serialize(
            issuedTo, UUID.randomUUID(), issuedAtDays, expiresAtDays, graceDays, (short) maxNodes);
    byte[] sig = signer.sign(payload);
    byte[] combined = new byte[payload.length + sig.length];
    System.arraycopy(payload, 0, combined, 0, payload.length);
    System.arraycopy(sig, 0, combined, payload.length, sig.length);
    return "GRAV-" + Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
  }

  private static void validate(
      String issuedTo, long issuedAtDays, long expiresAtDays, int graceDays, int maxNodes) {
    if (issuedTo == null || issuedTo.isBlank()) {
      throw new IllegalArgumentException("--issued-to must not be blank");
    }
    if (issuedTo.getBytes(StandardCharsets.UTF_8).length > 255) {
      throw new IllegalArgumentException("--issued-to exceeds 255 bytes in UTF-8");
    }
    if (expiresAtDays <= issuedAtDays) {
      throw new IllegalArgumentException("--expires must be a future date (after today)");
    }
    if (graceDays < 0 || graceDays > 255) {
      throw new IllegalArgumentException(
          "--grace-days must be between 0 and 255, got " + graceDays);
    }
    if (maxNodes != -1 && maxNodes <= 0) {
      throw new IllegalArgumentException(
          "--max-nodes must be -1 (unlimited) or > 0, got " + maxNodes);
    }
    if (maxNodes > 65534) {
      throw new IllegalArgumentException(
          "--max-nodes must be <= 65534 (0xFFFF is the unlimited sentinel), got " + maxNodes);
    }
  }
}
