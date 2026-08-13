/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.gravitino.listener.api.event.scim;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

/** Shared customInfo keys/values for SCIM audit events. */
final class ScimAuditInfos {

  static final String INFO_RESOURCE_TYPE = "resourceType";
  static final String INFO_ID = "id";
  static final String INFO_EXTERNAL_ID = "externalId";
  static final String INFO_STATUS = "status";
  static final String INFO_SOURCE = "source";

  static final String RESOURCE_USER = "User";
  static final String SOURCE_SCIM = "scim";
  static final String STATUS_SUCCESS = "SUCCESS";
  static final String STATUS_FAILURE = "FAILURE";
  static final String STATUS_UNPROCESSED = "UNPROCESSED";

  private ScimAuditInfos() {}

  static Map<String, String> of(
      String resourceType, String resourceId, String externalId, String status) {
    ImmutableMap.Builder<String, String> builder =
        ImmutableMap.<String, String>builder()
            .put(INFO_SOURCE, SOURCE_SCIM)
            .put(INFO_RESOURCE_TYPE, resourceType)
            .put(INFO_STATUS, status);
    if (StringUtils.isNotBlank(resourceId)) {
      builder.put(INFO_ID, resourceId);
    }
    if (StringUtils.isNotBlank(externalId)) {
      builder.put(INFO_EXTERNAL_ID, externalId);
    }
    return builder.build();
  }
}
