/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.storage.service;

import com.datastrato.gravitino.scim.storage.mapper.AbstractScimUserGroupRelStorageTest;
import java.io.IOException;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;

/** Base class for SCIM user-group membership meta service tests. */
public abstract class AbstractScimUserGroupRelMetaServiceTest
    extends AbstractScimUserGroupRelStorageTest {

  protected void refreshSession() {
    closeSession();
    sharedSession = SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
    initializeMappers();
  }

  protected void runServiceCall(ServiceCall serviceCall) throws IOException {
    closeSession();
    serviceCall.run();
    refreshSession();
  }

  @FunctionalInterface
  protected interface ServiceCall {
    void run() throws IOException;
  }
}
