/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.encryption;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Optional;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.encryption.kms.KmsReference;
import org.apache.gravitino.exceptions.AmbiguousPolicyException;
import org.apache.gravitino.exceptions.NoSuchMetadataObjectException;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyContent;
import org.apache.gravitino.policy.PolicyContents;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the resolution rules: only enabled encryption policies count, only catalog and schema
 * ancestors are consulted — never the table itself, which does not exist at create time, and never
 * the metalake — each level of a hierarchical schema is walked separately, a policy reached twice
 * is deduped by name, more than one distinct match is rejected, and a missing ancestor is treated
 * as having no policies rather than failing resolve.
 */
public class TestIcebergEncryptionPolicyResolver {

  private static final String METALAKE = "metalake";
  private static final NameIdentifier TABLE =
      NameIdentifier.of(METALAKE, "catalog", "schema", "table");
  private static final MetadataObject SCHEMA =
      MetadataObjects.of("catalog", "schema", MetadataObject.Type.SCHEMA);
  private static final MetadataObject CATALOG =
      MetadataObjects.of(null, "catalog", MetadataObject.Type.CATALOG);

  private static final NameIdentifier HIERARCHICAL_TABLE =
      NameIdentifier.of(METALAKE, "hcat", "a:b:c", "tbl");
  private static final MetadataObject HIERARCHICAL_SCHEMA_A =
      MetadataObjects.parse("hcat.a", MetadataObject.Type.SCHEMA);
  private static final MetadataObject HIERARCHICAL_SCHEMA_B =
      MetadataObjects.parse("hcat.a:b", MetadataObject.Type.SCHEMA);
  private static final MetadataObject HIERARCHICAL_SCHEMA_C =
      MetadataObjects.parse("hcat.a:b:c", MetadataObject.Type.SCHEMA);
  private static final MetadataObject HIERARCHICAL_CATALOG =
      MetadataObjects.parse("hcat", MetadataObject.Type.CATALOG);

  private PolicyDispatcher policyDispatcher;
  private IcebergEncryptionPolicyResolver resolver;

  @BeforeEach
  void setUp() {
    policyDispatcher = mock(PolicyDispatcher.class);
    resolver = new IcebergEncryptionPolicyResolver(policyDispatcher, new SinglePolicyChecker());
  }

  @Test
  void testNullConstructorArgumentsAreRejectedAsIllegalArguments() {
    Assertions.assertEquals(
        "policyDispatcher cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new IcebergEncryptionPolicyResolver(null, new SinglePolicyChecker()))
            .getMessage());
    Assertions.assertEquals(
        "singlePolicyChecker cannot be null",
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new IcebergEncryptionPolicyResolver(policyDispatcher, null))
            .getMessage());
  }

  @Test
  void testResolveEnabledEncryptionPolicyAssociatedOnSchema() {
    PolicyEntity disabled = icebergPolicy("disabled", false);
    PolicyEntity match = icebergPolicy("match", true);
    PolicyEntity otherType = mock(PolicyEntity.class);
    when(otherType.enabled()).thenReturn(true);
    when(otherType.policyType()).thenReturn(Policy.BuiltInType.ICEBERG_COMPACTION);
    when(policyDispatcher.listPolicyInfosForMetadataObject(METALAKE, SCHEMA))
        .thenReturn(new PolicyEntity[] {disabled, match, otherType});

    Optional<PolicyEntity> result = resolver.resolve(TABLE);

    Assertions.assertTrue(result.isPresent());
    Assertions.assertSame(match, result.get());
    verify(policyDispatcher, never())
        .listPolicyInfosForMetadataObject(
            METALAKE, NameIdentifierUtil.toMetadataObject(TABLE, Entity.EntityType.TABLE));
  }

  @Test
  void testResolveInheritedCatalogAssociation() {
    PolicyEntity match = icebergPolicy("match", true);
    when(policyDispatcher.listPolicyInfosForMetadataObject(METALAKE, CATALOG))
        .thenReturn(new PolicyEntity[] {match});

    Assertions.assertSame(match, resolver.resolve(TABLE).orElseThrow(AssertionError::new));
  }

  @Test
  void testNoAssociatedPolicy() {
    when(policyDispatcher.listPolicyInfosForMetadataObject(METALAKE, SCHEMA))
        .thenReturn(new PolicyEntity[0]);

    Assertions.assertEquals(Optional.empty(), resolver.resolve(TABLE));
  }

  @Test
  void testMultipleMatchingPoliciesAreRejected() {
    PolicyEntity zPolicy = icebergPolicy("z-policy", true);
    PolicyEntity aPolicy = icebergPolicy("a-policy", true);
    when(policyDispatcher.listPolicyInfosForMetadataObject(METALAKE, SCHEMA))
        .thenReturn(new PolicyEntity[] {zPolicy, aPolicy});

    AmbiguousPolicyException exception =
        Assertions.assertThrows(AmbiguousPolicyException.class, () -> resolver.resolve(TABLE));
    Assertions.assertTrue(exception.getMessage().contains("[a-policy, z-policy]"));
  }

  @Test
  void testSamePolicyAssociatedOnCatalogAndSchemaIsDeduped() {
    PolicyEntity match = icebergPolicy("shared-policy", true);
    when(policyDispatcher.listPolicyInfosForMetadataObject(METALAKE, SCHEMA))
        .thenReturn(new PolicyEntity[] {match});
    when(policyDispatcher.listPolicyInfosForMetadataObject(METALAKE, CATALOG))
        .thenReturn(new PolicyEntity[] {match});

    Assertions.assertSame(match, resolver.resolve(TABLE).orElseThrow(AssertionError::new));
  }

  @Test
  void testResolvePolicyOnIntermediateHierarchicalSchema() {
    PolicyEntity match = icebergPolicy("intermediate-policy", true);
    when(policyDispatcher.listPolicyInfosForMetadataObject(METALAKE, HIERARCHICAL_SCHEMA_B))
        .thenReturn(new PolicyEntity[] {match});

    Assertions.assertSame(
        match, resolver.resolve(HIERARCHICAL_TABLE).orElseThrow(AssertionError::new));
  }

  @Test
  void testResolvePolicyOnRootHierarchicalSchemaOnly() {
    PolicyEntity match = icebergPolicy("root-policy", true);
    when(policyDispatcher.listPolicyInfosForMetadataObject(METALAKE, HIERARCHICAL_SCHEMA_A))
        .thenReturn(new PolicyEntity[] {match});

    Assertions.assertSame(
        match, resolver.resolve(HIERARCHICAL_TABLE).orElseThrow(AssertionError::new));
  }

  @Test
  void testResolveAmbiguityAcrossCatalogAndSchema() {
    PolicyEntity catalogPolicy = icebergPolicy("catalog-policy", true);
    PolicyEntity schemaPolicy = icebergPolicy("schema-policy", true);
    when(policyDispatcher.listPolicyInfosForMetadataObject(METALAKE, HIERARCHICAL_CATALOG))
        .thenReturn(new PolicyEntity[] {catalogPolicy});
    when(policyDispatcher.listPolicyInfosForMetadataObject(METALAKE, HIERARCHICAL_SCHEMA_C))
        .thenReturn(new PolicyEntity[] {schemaPolicy});

    AmbiguousPolicyException exception =
        Assertions.assertThrows(
            AmbiguousPolicyException.class, () -> resolver.resolve(HIERARCHICAL_TABLE));
    Assertions.assertTrue(exception.getMessage().contains("[catalog-policy, schema-policy]"));
  }

  @Test
  void testNullDispatcherResultIsIgnored() {
    when(policyDispatcher.listPolicyInfosForMetadataObject(METALAKE, SCHEMA)).thenReturn(null);

    Assertions.assertEquals(Optional.empty(), resolver.resolve(TABLE));
  }

  @Test
  void testMissingSchemaParentIsTreatedAsNoPolicies() {
    when(policyDispatcher.listPolicyInfosForMetadataObject(METALAKE, SCHEMA))
        .thenThrow(
            new NoSuchMetadataObjectException(
                "Metadata object %s type SCHEMA doesn't exist", "catalog.schema"));

    Assertions.assertEquals(Optional.empty(), resolver.resolve(TABLE));
    verify(policyDispatcher).listPolicyInfosForMetadataObject(METALAKE, CATALOG);
  }

  @Test
  void testMissingSchemaParentStillUsesCatalogPolicy() {
    PolicyEntity catalogPolicy = icebergPolicy("catalog-policy", true);
    when(policyDispatcher.listPolicyInfosForMetadataObject(METALAKE, SCHEMA))
        .thenThrow(
            new NoSuchMetadataObjectException(
                "Metadata object %s type SCHEMA doesn't exist", "catalog.schema"));
    when(policyDispatcher.listPolicyInfosForMetadataObject(METALAKE, CATALOG))
        .thenReturn(new PolicyEntity[] {catalogPolicy});

    Assertions.assertSame(catalogPolicy, resolver.resolve(TABLE).orElseThrow(AssertionError::new));
  }

  @Test
  void testOtherPolicyLookupErrorsPropagate() {
    when(policyDispatcher.listPolicyInfosForMetadataObject(METALAKE, SCHEMA))
        .thenThrow(new IllegalStateException("policy store unavailable"));

    Assertions.assertEquals(
        "policy store unavailable",
        Assertions.assertThrows(IllegalStateException.class, () -> resolver.resolve(TABLE))
            .getMessage());
  }

  private static PolicyEntity icebergPolicy(String name, boolean enabled) {
    PolicyContent content =
        PolicyContents.icebergEncryption(
            1, Collections.singletonList(new KmsReference("production", "key")));
    PolicyEntity policy = mock(PolicyEntity.class);
    when(policy.name()).thenReturn(name);
    when(policy.enabled()).thenReturn(enabled);
    when(policy.policyType()).thenReturn(Policy.BuiltInType.ICEBERG_ENCRYPTION);
    when(policy.content()).thenReturn(content);
    return policy;
  }
}
