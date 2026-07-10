/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.service;

import com.datastrato.gravitino.scim.ScimTokenManager;
import com.datastrato.gravitino.scim.ScimTokenManagerTestHelper;
import com.datastrato.gravitino.scim.storage.mapper.AbstractScimMetaStorageTest;
import com.datastrato.gravitino.scim.storage.mapper.ScimTokenMetaMapper;
import java.io.IOException;
import org.apache.gravitino.Configs;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.EntityStoreFactory;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.storage.RandomIdGenerator;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.gravitino.utils.PrincipalUtils;
import org.junit.jupiter.api.AfterEach;

/** Base class for SCIM token manager tests. */
public abstract class AbstractScimManagerTest extends AbstractScimMetaStorageTest {
  protected ScimTokenMetaMapper scimTokenMetaMapper;
  protected EntityStore entityStore;
  protected ScimTokenManager manager;

  protected void initManagerTest(String type) throws Exception {
    init(type);
    getConfig().set(Configs.CACHE_ENABLED, false);
    getConfig().set(Configs.ENTITY_RELATIONAL_STORE, Configs.DEFAULT_ENTITY_RELATIONAL_STORE);
    entityStore = EntityStoreFactory.createEntityStore(getConfig());
    entityStore.initialize(getConfig());
    manager =
        ScimTokenManagerTestHelper.newManager(getConfig(), entityStore, RandomIdGenerator.INSTANCE);
    initializeMappers();
  }

  @Override
  protected void initializeMappers() {
    scimTokenMetaMapper = sharedSession.getMapper(ScimTokenMetaMapper.class);
  }

  /** Reopens the shared session after manager-layer commits. */
  protected void refreshSession() {
    closeSession();
    sharedSession = SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
    initializeMappers();
  }

  /** Runs a manager call outside the shared session, then refreshes mapper state. */
  protected void runManagerCall(ManagerCall managerCall) throws IOException {
    closeSession();
    managerCall.run();
    refreshSession();
  }

  protected void runManagerCallAs(String principalName, ManagerCall managerCall) throws Exception {
    closeSession();
    PrincipalUtils.doAs(
        new UserPrincipal(principalName),
        () -> {
          managerCall.run();
          return null;
        });
    refreshSession();
  }

  protected <T> T runManagerCallAndReturnAs(
      String principalName, ManagerCallWithResult<T> managerCall) throws Exception {
    closeSession();
    try {
      return PrincipalUtils.doAs(new UserPrincipal(principalName), managerCall::run);
    } finally {
      refreshSession();
    }
  }

  @FunctionalInterface
  protected interface ManagerCallWithResult<T> {
    T run() throws IOException;
  }

  @FunctionalInterface
  protected interface ManagerCall {
    void run() throws IOException;
  }

  @AfterEach
  void closeManager() throws Exception {
    if (manager != null) {
      manager.close();
      manager = null;
    }
    if (entityStore != null) {
      entityStore.close();
      entityStore = null;
    }
    reinitializeBackend();
    reopenSession();
  }
}
