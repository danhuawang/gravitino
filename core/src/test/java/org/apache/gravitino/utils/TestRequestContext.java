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

package org.apache.gravitino.utils;

import com.google.common.collect.ImmutableMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos;
import org.apache.gravitino.listener.api.event.IcebergEncryptionAuditInfos.Reason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestRequestContext {

  private static final String ADDED_KEY_IDS =
      IcebergEncryptionAuditInfos.PREFIX + "addedEncryptionKeyIds";

  private static final String REMOVED_KEY_IDS =
      IcebergEncryptionAuditInfos.PREFIX + "removedEncryptionKeyIds";

  private static final String COMMIT_ID = IcebergEncryptionAuditInfos.PREFIX + "commitId";

  @AfterEach
  public void cleanup() {
    RequestContext.clear();
  }

  @Test
  public void testSetAndGet() {
    RequestContext.setRemoteAddress("192.168.1.1");
    Assertions.assertEquals("192.168.1.1", RequestContext.getRemoteAddress());
  }

  @Test
  public void testClearRemovesValue() {
    RequestContext.setRemoteAddress("10.0.0.1");
    RequestContext.clear();
    Assertions.assertNull(RequestContext.getRemoteAddress());
  }

  @Test
  public void testGetReturnsNullWhenNotSet() {
    Assertions.assertNull(RequestContext.getRemoteAddress());
  }

  @Test
  public void testThreadIsolation() throws InterruptedException {
    RequestContext.setRemoteAddress("main-thread-ip");
    AtomicReference<String> childValue = new AtomicReference<>();

    Thread child =
        new Thread(
            () -> {
              // Child thread has its own ThreadLocal slot — must not see the main-thread value.
              childValue.set(RequestContext.getRemoteAddress());
            });
    child.start();
    child.join();

    Assertions.assertNull(childValue.get(), "Child thread should not inherit parent ThreadLocal");
    Assertions.assertEquals(
        "main-thread-ip", RequestContext.getRemoteAddress(), "Main thread value unchanged");
  }

  /**
   * Callers read the stash unconditionally on every table operation, so the common case is that
   * nothing was stashed. Pins that this returns an empty map rather than {@code null}, which keeps
   * the dispatcher free of a null check on the hot path.
   */
  @Test
  public void testTakeAuditExtrasReturnsEmptyWhenUnset() {
    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty());
  }

  /**
   * The stash is a hand-off between two dispatcher layers, not a request-scoped register: exactly
   * one reader is expected to consume each set. Pins the take-and-clear semantics that the
   * dispatcher relies on to avoid attributing a fact to a later operation.
   */
  @Test
  public void testSetAndTakeAuditExtras() {
    RequestContext.setAuditExtras(Collections.singletonMap("audit.reason", "policy-applied"));
    Assertions.assertEquals("policy-applied", RequestContext.takeAuditExtras().get("audit.reason"));
    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty(), "take must clear extras");
  }

  /**
   * A contributor that decides it has nothing to report will naturally pass an empty or {@code
   * null} map. Pins that this retracts an earlier stash instead of being a no-op, so a stale fact
   * from an earlier decision cannot be attached to the event.
   */
  @Test
  public void testEmptyOrNullAuditExtrasClearsStash() {
    RequestContext.setAuditExtras(Collections.singletonMap("k", "v"));
    RequestContext.setAuditExtras(Collections.emptyMap());
    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty());

    RequestContext.setAuditExtras(Collections.singletonMap("k", "v"));
    RequestContext.setAuditExtras(null);
    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty());
  }

  /**
   * Extras are a third {@link ThreadLocal} on a pooled servlet thread, so the request-teardown
   * sweep has to know about them. Pins that {@code clear()} was extended alongside the new state
   * and cannot silently leave it bound to the thread.
   */
  @Test
  public void testClearRemovesAuditExtras() {
    RequestContext.setAuditExtras(Collections.singletonMap("k", "v"));
    RequestContext.clear();
    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty());
  }

  /**
   * The stash is only safe because a contributor and the dispatcher share one servlet thread. Pins
   * that a fact set on one thread is invisible to another, which is the assumption that makes a
   * {@link ThreadLocal} acceptable here in place of a threaded-through parameter.
   */
  @Test
  public void testAuditExtrasAreThreadConfined() throws InterruptedException {
    RequestContext.setAuditExtras(Collections.singletonMap("audit.reason", "policy-applied"));
    AtomicReference<Map<String, String>> childValue = new AtomicReference<>();

    Thread child = new Thread(() -> childValue.set(RequestContext.takeAuditExtras()));
    child.start();
    child.join();

    Assertions.assertTrue(childValue.get().isEmpty(), "Child thread must not see stashed extras");
    Assertions.assertEquals(
        "policy-applied",
        RequestContext.takeAuditExtras().get("audit.reason"),
        "Child thread must not consume the parent's stash");
  }

  /**
   * Unlike audit extras, the query-param snapshot is read by every {@code Event} constructed during
   * the request, not consumed once. Pins that reading it does not clear it.
   */
  @Test
  public void testGetRequestQueryParamsDoesNotClear() {
    RequestContext.setRequestQueryParams(Collections.singletonMap("details", "true"));
    Assertions.assertEquals("true", RequestContext.getRequestQueryParams().get("details"));
    Assertions.assertEquals(
        "true",
        RequestContext.getRequestQueryParams().get("details"),
        "a second read must still see the value");
  }

  @Test
  public void testGetRequestQueryParamsReturnsEmptyWhenUnset() {
    Assertions.assertTrue(RequestContext.getRequestQueryParams().isEmpty());
  }

  @Test
  public void testEmptyOrNullRequestQueryParamsClearsStash() {
    RequestContext.setRequestQueryParams(Collections.singletonMap("k", "v"));
    RequestContext.setRequestQueryParams(Collections.emptyMap());
    Assertions.assertTrue(RequestContext.getRequestQueryParams().isEmpty());

    RequestContext.setRequestQueryParams(Collections.singletonMap("k", "v"));
    RequestContext.setRequestQueryParams(null);
    Assertions.assertTrue(RequestContext.getRequestQueryParams().isEmpty());
  }

  @Test
  public void testClearRemovesRequestQueryParams() {
    RequestContext.setRequestQueryParams(Collections.singletonMap("k", "v"));
    RequestContext.clear();
    Assertions.assertTrue(RequestContext.getRequestQueryParams().isEmpty());
  }

  /**
   * Mirrors the existing {@code operationFailureFired} flag on the success path. Kept as an
   * independent flag (not merged with the failure one) so that an operation which succeeds but
   * whose HTTP response delivery later fails can still produce both a success and a failure audit
   * entry.
   */
  @Test
  public void testOperationSuccessFiredLifecycle() {
    Assertions.assertFalse(RequestContext.isOperationSuccessFired());
    RequestContext.markOperationSuccessFired();
    Assertions.assertTrue(RequestContext.isOperationSuccessFired());
    RequestContext.resetOperationSuccessFired();
    Assertions.assertFalse(RequestContext.isOperationSuccessFired());
  }

  @Test
  public void testClearRemovesOperationSuccessFired() {
    RequestContext.markOperationSuccessFired();
    RequestContext.clear();
    Assertions.assertFalse(RequestContext.isOperationSuccessFired());
  }

  /**
   * Failure is sticky: a success dispatch that happens to follow a failure dispatch on the same
   * thread (not expected in practice, but not structurally prevented either) must not overwrite it
   * — otherwise HttpAuditFilter would see isOperationFailureFired() == false and double-log a
   * 4xx/5xx response with its own fallback failure event, on top of the real operation-layer one.
   */
  @Test
  public void testMarkOperationSuccessFiredDoesNotOverwriteFailure() {
    RequestContext.markOperationFailureFired();
    RequestContext.markOperationSuccessFired();
    Assertions.assertTrue(RequestContext.isOperationFailureFired(), "failure must remain recorded");
    Assertions.assertFalse(
        RequestContext.isOperationSuccessFired(), "success must not overwrite a recorded failure");
  }

  /**
   * The bug the merge exists to fix. A contributor that decides it has nothing to report passes an
   * empty or {@code null} map, and the replacing setter answered that by wiping the stash — so an
   * earlier contributor's facts vanished from the audit record for reasons that had nothing to do
   * with them. Pins that merging nothing changes nothing.
   */
  @Test
  public void testMergeAuditExtrasWithEmptyOrNullMapIsANoOp() {
    RequestContext.mergeAuditExtras(Collections.singletonMap("k", "v"));

    RequestContext.mergeAuditExtras(Collections.emptyMap());
    RequestContext.mergeAuditExtras(null);

    Assertions.assertEquals("v", RequestContext.takeAuditExtras().get("k"));
  }

  /** Merging into an untouched stash is just a set; the common single-contributor case. */
  @Test
  public void testMergeAuditExtrasIntoEmptyStashStoresTheExtras() {
    RequestContext.mergeAuditExtras(Collections.singletonMap("k", "v"));
    Assertions.assertEquals(Collections.singletonMap("k", "v"), RequestContext.takeAuditExtras());
  }

  /**
   * An integrity violation is a statement about the table's metadata, not about the request, so it
   * outranks whatever the admission concluded — and it brings its own error class with it, because
   * one contributor's reason beside another's error type names a failure that never happened.
   */
  @Test
  public void testIntegrityReasonOutranksAnAdmissionReasonAndTakesItsErrorType() {
    RequestContext.mergeAuditExtras(
        reasonExtras(Reason.KEY_NOT_ALLOWED.code(), "java.lang.IllegalArgumentException"));

    RequestContext.mergeAuditExtras(
        reasonExtras(Reason.METADATA_INTEGRITY_FAILED.code(), "java.lang.IllegalStateException"));

    Map<String, String> extras = RequestContext.takeAuditExtras();
    Assertions.assertEquals(
        Reason.METADATA_INTEGRITY_FAILED.code(), extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        "java.lang.IllegalStateException", extras.get(IcebergEncryptionAuditInfos.ERROR_TYPE));
  }

  /** The reverse order must give the same answer: rank decides, arrival order does not. */
  @Test
  public void testAnAdmissionReasonDoesNotDisplaceAnIntegrityReason() {
    RequestContext.mergeAuditExtras(
        reasonExtras(Reason.METADATA_INTEGRITY_FAILED.code(), "java.lang.IllegalStateException"));

    RequestContext.mergeAuditExtras(
        reasonExtras(Reason.KEY_NOT_ALLOWED.code(), "java.lang.IllegalArgumentException"));

    Map<String, String> extras = RequestContext.takeAuditExtras();
    Assertions.assertEquals(
        Reason.METADATA_INTEGRITY_FAILED.code(), extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals(
        "java.lang.IllegalStateException",
        extras.get(IcebergEncryptionAuditInfos.ERROR_TYPE),
        "the losing reason must not leave its error type behind");
  }

  /**
   * The Hive context reason sits between the two: more specific than anything an admission
   * concluded, less certain than a proven violation.
   */
  @Test
  public void testNativeLoadFailureRanksBetweenAdmissionAndIntegrity() {
    RequestContext.mergeAuditExtras(reasonExtras(Reason.NO_POLICY.code(), null));
    RequestContext.mergeAuditExtras(reasonExtras(Reason.NATIVE_LOAD_FAILURE.code(), null));
    Assertions.assertEquals(
        Reason.NATIVE_LOAD_FAILURE.code(),
        RequestContext.takeAuditExtras().get(IcebergEncryptionAuditInfos.REASON));

    RequestContext.mergeAuditExtras(reasonExtras(Reason.NATIVE_LOAD_FAILURE.code(), null));
    RequestContext.mergeAuditExtras(reasonExtras(Reason.METADATA_INTEGRITY_FAILED.code(), null));
    Assertions.assertEquals(
        Reason.METADATA_INTEGRITY_FAILED.code(),
        RequestContext.takeAuditExtras().get(IcebergEncryptionAuditInfos.REASON));
  }

  /**
   * {@code OPERATION_FAILED} is the reason a contributor picks when it has nothing better to say,
   * so it must never displace one that does.
   */
  @Test
  public void testAGenericFailureDoesNotDisplaceASpecificAdmissionReason() {
    RequestContext.mergeAuditExtras(reasonExtras(Reason.ENCRYPTION_KEY_CHANGE_DENIED.code(), null));
    RequestContext.mergeAuditExtras(reasonExtras(Reason.OPERATION_FAILED.code(), null));

    Assertions.assertEquals(
        Reason.ENCRYPTION_KEY_CHANGE_DENIED.code(),
        RequestContext.takeAuditExtras().get(IcebergEncryptionAuditInfos.REASON));
  }

  /**
   * Two contributors at the same rank are the ordinary case, and the later one stands closer to the
   * throw — which is what the replacing setter already did for them.
   */
  @Test
  public void testEqualRankGivesTheReasonToTheLaterContributor() {
    RequestContext.mergeAuditExtras(
        reasonExtras(Reason.ENCRYPTION_KEY_UPDATE_OBSERVED.code(), null));
    RequestContext.mergeAuditExtras(reasonExtras(Reason.NO_POLICY.code(), null));

    Assertions.assertEquals(
        Reason.NO_POLICY.code(),
        RequestContext.takeAuditExtras().get(IcebergEncryptionAuditInfos.REASON));
  }

  /**
   * A reason code minted by a newer server, or by a caller outside this vocabulary, must not fail
   * the request it is describing. It ranks lowest — below even the generic failure.
   */
  @Test
  public void testAnUnknownReasonCodeRanksLowestInsteadOfThrowing() {
    RequestContext.mergeAuditExtras(reasonExtras(Reason.OPERATION_FAILED.code(), null));
    RequestContext.mergeAuditExtras(reasonExtras("SOMETHING_FROM_THE_FUTURE", null));

    Assertions.assertEquals(
        Reason.OPERATION_FAILED.code(),
        RequestContext.takeAuditExtras().get(IcebergEncryptionAuditInfos.REASON));
  }

  /** A reason arriving where none was recorded wins by default; there is nothing to outrank. */
  @Test
  public void testAReasonFillsAStashThatHasNone() {
    RequestContext.mergeAuditExtras(Collections.singletonMap("k", "v"));
    RequestContext.mergeAuditExtras(reasonExtras(Reason.OPERATION_FAILED.code(), null));

    Map<String, String> extras = RequestContext.takeAuditExtras();
    Assertions.assertEquals("v", extras.get("k"));
    Assertions.assertEquals(
        Reason.OPERATION_FAILED.code(), extras.get(IcebergEncryptionAuditInfos.REASON));
  }

  /**
   * Every key other than the reason unit is filled, never overwritten: a late contributor adds the
   * facts it owns without disturbing the decision an earlier one recorded.
   */
  @Test
  public void testUnrelatedKeysSurviveInBothDirections() {
    RequestContext.mergeAuditExtras(
        ImmutableMap.of(
            IcebergEncryptionAuditInfos.DECISION_ID,
            "decision-1",
            IcebergEncryptionAuditInfos.POLICY_NAME,
            "policy-a"));

    RequestContext.mergeAuditExtras(
        ImmutableMap.of(
            IcebergEncryptionAuditInfos.DECISION_ID,
            "decision-2",
            IcebergEncryptionAuditInfos.KEY_ID,
            "key-1"));

    Map<String, String> extras = RequestContext.takeAuditExtras();
    Assertions.assertEquals(
        "decision-1",
        extras.get(IcebergEncryptionAuditInfos.DECISION_ID),
        "a later contributor must not rewrite a recorded decision id");
    Assertions.assertEquals("policy-a", extras.get(IcebergEncryptionAuditInfos.POLICY_NAME));
    Assertions.assertEquals("key-1", extras.get(IcebergEncryptionAuditInfos.KEY_ID));
  }

  /**
   * The shape of the one path that stashes twice: a staged-create commit records its key updates
   * and commit id, then the admission records its evaluation. The hand-rolled merge in the Iceberg
   * REST dispatcher named those three keys explicitly; this pins that the general fill rule keeps
   * them, which is why that special-casing could be deleted.
   */
  @Test
  public void testAStagedCreateCommitKeepsItsCommitFactsUnderTheAdmissionReason() {
    RequestContext.mergeAuditExtras(
        ImmutableMap.of(
            IcebergEncryptionAuditInfos.REASON,
            Reason.ENCRYPTION_KEY_UPDATE_OBSERVED.code(),
            ADDED_KEY_IDS,
            "kek-1",
            REMOVED_KEY_IDS,
            "kek-0",
            COMMIT_ID,
            "s3://warehouse/table/metadata/v3.metadata.json"));

    RequestContext.mergeAuditExtras(
        ImmutableMap.of(
            IcebergEncryptionAuditInfos.REASON,
            Reason.NO_POLICY.code(),
            IcebergEncryptionAuditInfos.DECISION_ID,
            "decision-1",
            IcebergEncryptionAuditInfos.KEY_ID,
            "key-1"));

    Map<String, String> extras = RequestContext.takeAuditExtras();
    Assertions.assertEquals("kek-1", extras.get(ADDED_KEY_IDS));
    Assertions.assertEquals("kek-0", extras.get(REMOVED_KEY_IDS));
    Assertions.assertEquals(
        "s3://warehouse/table/metadata/v3.metadata.json", extras.get(COMMIT_ID));
    Assertions.assertEquals(
        Reason.NO_POLICY.code(), extras.get(IcebergEncryptionAuditInfos.REASON));
    Assertions.assertEquals("decision-1", extras.get(IcebergEncryptionAuditInfos.DECISION_ID));
    Assertions.assertEquals("key-1", extras.get(IcebergEncryptionAuditInfos.KEY_ID));
  }

  /** The merged stash stays a snapshot: mutating the caller's map afterwards cannot reach it. */
  @Test
  public void testMergedAuditExtrasAreSnapshotted() {
    Map<String, String> mutable = new HashMap<>();
    mutable.put("k", "v");
    RequestContext.mergeAuditExtras(mutable);
    mutable.put("k", "changed");
    mutable.put("other", "late");

    Map<String, String> extras = RequestContext.takeAuditExtras();
    Assertions.assertEquals("v", extras.get("k"));
    Assertions.assertNull(extras.get("other"));
  }

  @Test
  public void testClearRemovesMergedAuditExtras() {
    RequestContext.mergeAuditExtras(Collections.singletonMap("k", "v"));
    RequestContext.clear();
    Assertions.assertTrue(RequestContext.takeAuditExtras().isEmpty());
  }

  private static Map<String, String> reasonExtras(String reasonCode, String errorType) {
    Map<String, String> extras = new HashMap<>();
    extras.put(IcebergEncryptionAuditInfos.REASON, reasonCode);
    if (errorType != null) {
      extras.put(IcebergEncryptionAuditInfos.ERROR_TYPE, errorType);
    }
    return extras;
  }
}
