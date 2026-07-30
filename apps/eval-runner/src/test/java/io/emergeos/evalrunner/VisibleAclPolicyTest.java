package io.emergeos.evalrunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisibleAclPolicyTest {

  private static final UserPrincipal OWNER = () -> "owner";
  private static final UserPrincipal FOREIGN = () -> "foreign";

  @Test
  void rejectsOnlyProviderVisibleForeignAllowEntries() {
    assertFalse(
        VisibleAclPolicy.hasForeignAllow(
            List.of(entry(AclEntryType.ALLOW, OWNER)), OWNER));
    assertFalse(
        VisibleAclPolicy.hasForeignAllow(
            List.of(entry(AclEntryType.DENY, FOREIGN)), OWNER));
    assertTrue(
        VisibleAclPolicy.hasForeignAllow(
            List.of(entry(AclEntryType.ALLOW, FOREIGN)), OWNER));
  }

  private static AclEntry entry(
      AclEntryType type, UserPrincipal principal) {
    return AclEntry.newBuilder()
        .setType(type)
        .setPrincipal(principal)
        .setPermissions(AclEntryPermission.READ_DATA)
        .build();
  }
}
