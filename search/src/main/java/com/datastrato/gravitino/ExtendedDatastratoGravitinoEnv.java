/*
 * Copyright 2024 Datastrato Inc.
 */

package com.datastrato.gravitino;

import com.datastrato.gravitino.license.LicenseManager;
import com.datastrato.gravitino.search.service.SearchService;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;

public class ExtendedDatastratoGravitinoEnv extends DatastratoGravitinoEnv {
  private static final ExtendedDatastratoGravitinoEnv INSTANCE =
      new ExtendedDatastratoGravitinoEnv();

  public static ExtendedDatastratoGravitinoEnv getInstance() {
    return INSTANCE;
  }

  private SearchService searchService;

  public SearchService getSearchService() {
    return searchService;
  }

  @Override
  public void initializeFullComponents(Config config) {
    this.searchService = new SearchService(config);
    super.initializeFullComponents(config);
    LicenseManager.getInstance().initialize(config);
  }

  @Override
  public void shutdown() {
    LicenseManager.getInstance().shutdown();
    if (searchService != null) {
      searchService.close();
    }
    GravitinoEnv.getInstance().shutdown();
  }
}
