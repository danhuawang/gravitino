/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.gravitino.listener.api.event;

import java.util.Map;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.annotation.DeveloperApi;

/** Represents an abstract post event in Gravitino Iceberg REST server. */
@DeveloperApi
public abstract class IcebergEvent extends Event {
  private IcebergRequestContext icebergRequestContext;

  protected IcebergEvent(
      IcebergRequestContext icebergRequestContext, NameIdentifier resourceIdentifier) {
    super(icebergRequestContext.userName(), resourceIdentifier);
    this.icebergRequestContext = icebergRequestContext;
  }

  @Override
  public EventSource eventSource() {
    return EventSource.GRAVITINO_ICEBERG_REST_SERVER;
  }

  @Override
  public OperationStatus operationStatus() {
    return OperationStatus.SUCCESS;
  }

  public IcebergRequestContext icebergRequestContext() {
    return icebergRequestContext;
  }

  @Override
  public String remoteAddress() {
    return icebergRequestContext.remoteHostName();
  }

<<<<<<< HEAD
  /**
   * Returns HTTP headers unioned with inner-dispatcher extras, including encryption {@code
   * icebergEncryption.*} facts. Distinct keys from both sources are kept.
   */
  @Override
  public Map<String, String> customInfo() {
    return icebergRequestContext.customInfo();
  }

  /**
   * Unions HTTP headers with stashed extras. Delegates to {@link
   * IcebergRequestContext#customInfo()} so apache#12723 inner-dispatcher facts and encryption
   * extras share one map.
   *
   * @param context request context whose headers and extras should be merged
   * @return headers ∪ extras; empty extras keep the headers map identity
   */
  static Map<String, String> mergeCustomInfo(IcebergRequestContext context) {
    return context.customInfo();
=======
  /** {@inheritDoc} */
  @Override
  protected Map<String, String> ownCustomInfo() {
    return icebergRequestContext.httpHeaders();
>>>>>>> upstream/branch-1.3
  }
}
