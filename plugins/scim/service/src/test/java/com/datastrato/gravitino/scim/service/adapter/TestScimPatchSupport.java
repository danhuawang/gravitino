/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.service.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.apache.directory.scim.spec.exception.ResourceException;
import org.apache.directory.scim.spec.patch.PatchOperation;
import org.apache.directory.scim.spec.patch.PatchOperationPath;
import org.apache.directory.scim.spec.resources.GroupMembership;
import org.junit.jupiter.api.Test;

class TestScimPatchSupport {

  @Test
  void testParseMembersValueFilter() throws Exception {
    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REMOVE);
    operation.setPath(PatchOperationPath.fromString("members[value eq \"123\"]"));

    ScimPatchSupport.GroupPatchOperation parsed = ScimPatchSupport.parseGroupPatch(operation);
    assertEquals(ScimPatchSupport.GroupPatchKind.MEMBERS, parsed.kind());
    assertTrue(parsed.membersFromPathFilter());
    assertTrue(parsed.replacementMembers().isEmpty());
    assertEquals(1, parsed.members().size());
    assertEquals("123", parsed.members().get(0).getValue());
  }

  @Test
  void testParseMembersValueFilterWithReplacement() throws Exception {
    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setPath(PatchOperationPath.fromString("members[value eq \"123\"]"));
    operation.setValue(new GroupMembership().setValue("456"));

    ScimPatchSupport.GroupPatchOperation parsed = ScimPatchSupport.parseGroupPatch(operation);
    assertTrue(parsed.membersFromPathFilter());
    assertEquals("123", parsed.members().get(0).getValue());
    assertEquals(1, parsed.replacementMembers().size());
    assertEquals("456", parsed.replacementMembers().get(0).getValue());
  }

  @Test
  void testParsePathlessExternalId() throws Exception {
    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setValue(Map.of("externalId", "ext-2"));

    ScimPatchSupport.GroupPatchOperation parsed = ScimPatchSupport.parseGroupPatch(operation);
    assertEquals(ScimPatchSupport.GroupPatchKind.EXTERNAL_ID, parsed.kind());
    assertEquals("ext-2", parsed.externalId());
  }

  @Test
  void testParsePathlessDisplayName() throws Exception {
    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setValue(Map.of("displayName", "renamed"));

    ScimPatchSupport.GroupPatchOperation parsed = ScimPatchSupport.parseGroupPatch(operation);
    assertEquals(ScimPatchSupport.GroupPatchKind.DISPLAY_NAME, parsed.kind());
    assertEquals("renamed", parsed.displayName());
  }

  @Test
  void testParseMembersList() throws Exception {
    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setPath(PatchOperationPath.fromString("members"));
    operation.setValue(List.of(new GroupMembership().setValue("3")));

    ScimPatchSupport.GroupPatchOperation parsed = ScimPatchSupport.parseGroupPatch(operation);
    assertEquals(ScimPatchSupport.GroupPatchKind.MEMBERS, parsed.kind());
    assertFalse(parsed.membersFromPathFilter());
    assertEquals("3", parsed.members().get(0).getValue());
  }

  @Test
  void testRejectNonMemberMapAsMembership() throws Exception {
    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setPath(PatchOperationPath.fromString("members"));
    operation.setValue(Map.of("externalId", "ext-1"));

    assertThrows(ResourceException.class, () -> ScimPatchSupport.parseGroupPatch(operation));
  }

  @Test
  void testRejectUnsupportedMembersFilterAttribute() throws Exception {
    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REMOVE);
    operation.setPath(PatchOperationPath.fromString("members[display eq \"alice\"]"));

    ResourceException exception =
        assertThrows(ResourceException.class, () -> ScimPatchSupport.parseGroupPatch(operation));
    assertEquals(400, exception.getStatus());
  }

  @Test
  void testRejectBlankMemberValue() throws Exception {
    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.ADD);
    operation.setPath(PatchOperationPath.fromString("members"));
    operation.setValue(List.of(Map.of("value", "  ")));

    ResourceException exception =
        assertThrows(ResourceException.class, () -> ScimPatchSupport.parseGroupPatch(operation));
    assertEquals(400, exception.getStatus());
  }

  @Test
  void testParsePathlessExternalIdAndDisplayNameTogether() throws Exception {
    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setValue(Map.of("externalId", "ext-1", "displayName", "engineers"));

    List<ScimPatchSupport.GroupPatchOperation> parsed =
        ScimPatchSupport.parseGroupPatches(operation);
    assertEquals(2, parsed.size());
    assertEquals(ScimPatchSupport.GroupPatchKind.EXTERNAL_ID, parsed.get(0).kind());
    assertEquals("ext-1", parsed.get(0).externalId());
    assertEquals(ScimPatchSupport.GroupPatchKind.DISPLAY_NAME, parsed.get(1).kind());
    assertEquals("engineers", parsed.get(1).displayName());
  }

  @Test
  void testParsePathlessOktaGroupFragment() throws Exception {
    PatchOperation operation = new PatchOperation();
    operation.setOperation(PatchOperation.Type.REPLACE);
    operation.setValue(
        Map.of(
            "id",
            "123",
            "externalId",
            "00gabc",
            "displayName",
            "engineers",
            "members",
            List.of(Map.of("value", "9"))));

    List<ScimPatchSupport.GroupPatchOperation> parsed =
        ScimPatchSupport.parseGroupPatches(operation);
    assertEquals(3, parsed.size());
    assertEquals(ScimPatchSupport.GroupPatchKind.EXTERNAL_ID, parsed.get(0).kind());
    assertEquals("00gabc", parsed.get(0).externalId());
    assertEquals(ScimPatchSupport.GroupPatchKind.DISPLAY_NAME, parsed.get(1).kind());
    assertEquals("engineers", parsed.get(1).displayName());
    assertEquals(ScimPatchSupport.GroupPatchKind.MEMBERS, parsed.get(2).kind());
    assertEquals("9", parsed.get(2).members().get(0).getValue());
  }
}
