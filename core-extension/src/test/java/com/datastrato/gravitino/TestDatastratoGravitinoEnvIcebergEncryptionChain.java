/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.datastrato.gravitino.catalog.DatastratoTableDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTableHookDispatcher;
import com.datastrato.gravitino.encryption.IcebergTableEncryptionDispatcher;
import java.util.function.Supplier;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.GravitinoEnv;
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
    DatastratoGravitinoEnv env = mock(DatastratoGravitinoEnv.class, CALLS_REAL_METHODS);
    doReturn(ownerDispatcher).when(env).internalOwnerDispatcher();
    IcebergEncryptionPolicyResolver policyResolver =
        new IcebergEncryptionPolicyResolver(
            mock(PolicyDispatcher.class), new SinglePolicyChecker());
    IcebergEncryptionPolicyEvaluator.KmsKeyValidator keyValidator =
        key -> IcebergEncryptionDecision.KmsValidationStatus.VALID;

    DatastratoTableHookDispatcher externalHook =
        env.createExternalTableHookDispatcher(
            externalOperation, catalogManager, policyResolver, keyValidator);

    Supplier<?> ownerSupplier =
        (Supplier<?>) FieldUtils.readField(externalHook, "ownerDispatcher", true);
    assertSame(ownerDispatcher, ownerSupplier.get());
    verify(env).internalOwnerDispatcher();
    verify(env, never()).ownerDispatcher();

    Object encryption = FieldUtils.readField(externalHook, "dispatcher", true);
    IcebergTableEncryptionDispatcher encryptionDispatcher =
        assertInstanceOf(IcebergTableEncryptionDispatcher.class, encryption);
    Object wrappedOperation = FieldUtils.readField(encryptionDispatcher, "dispatcher", true);

    assertSame(externalOperation, wrappedOperation);
    assertNotSame(internalOperation, wrappedOperation);
    verifyNoInteractions(internalOperation);
  }

  @Test
  void testRealEnvironmentResolvesInternalOwnerForTableHook() throws IllegalAccessException {
    GravitinoEnv ossEnv = GravitinoEnv.getInstance();
    OwnerDispatcher previousInternal = ossEnv.internalOwnerDispatcher();
    OwnerDispatcher previousPublic = ossEnv.ownerDispatcher();
    OwnerDispatcher internalOwner = mock(OwnerDispatcher.class);
    OwnerDispatcher publicOwner = mock(OwnerDispatcher.class);
    try {
      FieldUtils.writeField(ossEnv, "internalOwnerDispatcher", null, true);
      FieldUtils.writeField(ossEnv, "ownerDispatcher", publicOwner, true);
      DatastratoGravitinoEnv env = DatastratoGravitinoEnv.getInstance();
      DatastratoTableHookDispatcher hook =
          env.createExternalTableHookDispatcher(
              mock(DatastratoTableDispatcher.class),
              mock(CatalogManager.class),
              new IcebergEncryptionPolicyResolver(
                  mock(PolicyDispatcher.class), new SinglePolicyChecker()),
              key -> IcebergEncryptionDecision.KmsValidationStatus.VALID);
      Supplier<?> supplier = (Supplier<?>) FieldUtils.readField(hook, "ownerDispatcher", true);
      assertNull(supplier.get());

      // The real environment must delegate to the initialized OSS singleton, even when
      // ownership is initialized after the table hook is constructed.
      FieldUtils.writeField(ossEnv, "internalOwnerDispatcher", internalOwner, true);
      assertSame(internalOwner, env.internalOwnerDispatcher());
      assertSame(internalOwner, supplier.get());
      assertNotSame(publicOwner, supplier.get());
      verifyNoInteractions(publicOwner);
    } finally {
      FieldUtils.writeField(ossEnv, "internalOwnerDispatcher", previousInternal, true);
      FieldUtils.writeField(ossEnv, "ownerDispatcher", previousPublic, true);
    }
  }
}
