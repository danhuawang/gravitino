/*
 * Copyright 2026 Datastrato Inc.
 *
 * Shared path lists for the enterprise license jobs.
 *
 * Who: enterpriseFilePatterns (Inc. include / withheld from RAT) and
 * generatedFilePatterns (skipped by every license check).
 * Not: header predicates, tasks, or per-job waivers.
 *
 * To: extra.enterpriseFilePatterns, extra.generatedFilePatterns.
 *     Parent applies this first; the check-*.gradle.kts jobs read them with `by extra`,
 *     and build.gradle.kts shipsEnterpriseCode() reads enterpriseFilePatterns to pick
 *     the license files a jar carries.
 */

// To extra. Read by the check-*.gradle.kts jobs and by build.gradle.kts shipsEnterpriseCode().
val enterpriseFilePatterns: List<String> by extra(
  listOf(
    // keep-sorted start name=enterprise-includes
    "authorization-jdbc-enterprise/**",
    "bin/gravitino-metrics-service.sh.template",
    "bin/index.sh.template",
    "bin/opensearch/**",
    "buildSrc/build.gradle.kts",
    "buildSrc/src/main/java/org/apache/gravitino/license/**",
    "buildSrc/src/test/java/org/apache/gravitino/license/**",
    "bundles/openbao/**",
    "bundles/vault-compatible/secret/**",
    "bundles/vault-compatible/transit/**",
    "bundles/vault/**",
    "catalogs/catalog-jdbc-bigquery/**/*",
    "catalogs/catalog-jdbc-maxcompute/**/*",
    "catalogs/catalog-jdbc-oracle/**/*",
    "catalogs/catalog-jdbc-sqlserver/**/*",
    "common-extension/**",
    "conf/gravitino-metrics-server.conf.template",
    "conf/gravitino-metrics-service-log4j2.properties.template",
    "core-extension/**",
    "core/src/main/java/org/apache/gravitino/listener/api/event/scim/**",
    "core/src/test/java/org/apache/gravitino/listener/api/event/scim/**",
    "datastrato-server/**",
    "dev/ci/test_*license_headers.sh",
    "dev/headers/*.py",
    "dev/headers/*.sh",
    "dev/headers/test/run.sh",
    "docs-enterprise/**",
    "enterprise-licensing.gradle.kts",
    "enterprise-licensing/**",
    "flink-connector/ext/**",
    "iceberg/iceberg-common/src/main/resources/org/apache/gravitino/iceberg/jdbc/metadata-integrity/**",
    "integration-test-common/src/test/java/org/apache/gravitino/integration/test/container/OracleContainer.java",
    "licensing/**",
    "lineage-extension/**",
    "metrics/**",
    "plugins/scim/**",
    "qa/**",
    "scripts/enterprise/**",
    "search/**",
    "server/src/test/java/org/apache/gravitino/server/TestLog4j2RollingConfiguration.java",
    "server/src/test/java/org/apache/gravitino/server/TestOutFileRotation.java",
    "spark-connector/ext/**",
    "src/test/java/com/datastrato/gravitino/transit/packaging/TransitProviderDiscoveryProbe.java",
    "test/test-common/**",
    "trino-connector/trino-connector-ext/**"
    // keep-sorted end
  )
)

// To extra. Generated and tooling trees. No license check cares about these.
val generatedFilePatterns: List<String> by extra(
  listOf(
    // keep-sorted start name=generated-file-patterns
    "**/.git/**",
    "**/.gradle/**",
    "**/.idea/**",
    "**/.node/**",
    "**/.pytest_cache/**",
    "**/.venv/**",
    "**/__pycache__/**",
    "**/build/**",
    "**/dist/**",
    "**/node_modules/**",
    "**/venv/**"
    // keep-sorted end
  )
)
