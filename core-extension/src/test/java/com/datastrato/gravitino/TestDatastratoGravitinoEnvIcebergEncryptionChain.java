/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.datastrato.gravitino.catalog.DatastratoTableDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTableHookDispatcher;
import com.datastrato.gravitino.encryption.IcebergTableEncryptionDispatcher;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.encryption.IcebergEncryptionDecision;
import org.apache.gravitino.encryption.IcebergEncryptionPolicyEvaluator;
import org.apache.gravitino.encryption.IcebergEncryptionPolicyResolver;
import org.apache.gravitino.encryption.SinglePolicyChecker;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.junit.jupiter.api.Test;

/** Verifies that Iceberg encryption enforcement is confined to the external table-create chain. */
public class TestDatastratoGravitinoEnvIcebergEncryptionChain {

  @Test
  void testEncryptionWrapsOnlyExternalTableOperationDispatcher() throws IllegalAccessException {
    DatastratoTableDispatcher externalOperation = mock(DatastratoTableDispatcher.class);
    DatastratoTableDispatcher internalOperation = mock(DatastratoTableDispatcher.class);
    CatalogManager catalogManager = mock(CatalogManager.class);
    OwnerDispatcher ownerDispatcher = mock(OwnerDispatcher.class);
    IcebergEncryptionPolicyResolver policyResolver =
        new IcebergEncryptionPolicyResolver(
            mock(PolicyDispatcher.class), new SinglePolicyChecker());
    IcebergEncryptionPolicyEvaluator.KmsKeyValidator keyValidator =
        key -> IcebergEncryptionDecision.KmsValidationStatus.VALID;

    DatastratoTableHookDispatcher externalHook =
        DatastratoGravitinoEnv.createExternalTableHookDispatcher(
            externalOperation, catalogManager, () -> ownerDispatcher, policyResolver, keyValidator);

    Object encryption = FieldUtils.readField(externalHook, "dispatcher", true);
    IcebergTableEncryptionDispatcher encryptionDispatcher =
        assertInstanceOf(IcebergTableEncryptionDispatcher.class, encryption);
    Object wrappedOperation = FieldUtils.readField(encryptionDispatcher, "dispatcher", true);

    assertSame(externalOperation, wrappedOperation);
    assertNotSame(internalOperation, wrappedOperation);
    verifyNoInteractions(internalOperation);
  }
}
