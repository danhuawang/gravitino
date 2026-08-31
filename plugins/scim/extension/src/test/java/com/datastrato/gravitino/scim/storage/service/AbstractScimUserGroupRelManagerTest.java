/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.service;

import com.datastrato.gravitino.scim.ScimUserGroupRelManager;
import com.datastrato.gravitino.scim.ScimUserGroupRelManagerTestHelper;
import com.datastrato.gravitino.scim.storage.mapper.AbstractScimUserGroupRelStorageTest;
import java.io.IOException;
import org.apache.gravitino.Configs;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.gravitino.utils.PrincipalUtils;
import org.junit.jupiter.api.AfterEach;

/** Base class for SCIM user-group membership manager tests. */
public abstract class AbstractScimUserGroupRelManagerTest
    extends AbstractScimUserGroupRelStorageTest {
  protected ScimUserGroupRelManager manager;

  protected void initManagerTest(String type) throws Exception {
    init(type);
    getConfig().set(Configs.CACHE_ENABLED, false);
    getConfig().set(Configs.ENTITY_RELATIONAL_STORE, Configs.DEFAULT_ENTITY_RELATIONAL_STORE);
    manager = ScimUserGroupRelManagerTestHelper.newManager(getConfig());
    initializeMappers();
  }

  protected void insertMetalakeForManager() throws Exception {
    insertMetalake();
  }

  protected void refreshSession() {
    closeSession();
    sharedSession = SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
    initializeMappers();
  }

  protected void runManagerCall(ManagerCall managerCall) throws Exception {
    closeSession();
    PrincipalUtils.doAs(
        new UserPrincipal("admin"),
        () -> {
          managerCall.run();
          return null;
        });
    refreshSession();
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
    reinitializeBackend();
    reopenSession();
  }
}
