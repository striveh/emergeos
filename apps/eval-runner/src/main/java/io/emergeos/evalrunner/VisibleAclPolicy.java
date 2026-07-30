package io.emergeos.evalrunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.Objects;

/**
 * Detects foreign {@code ALLOW} entries only when the filesystem provider
 * exposes an ACL view.
 *
 * <p>An unavailable view is not evidence that no ACL exists. Callers still
 * enforce their separate POSIX owner/mode cooperative boundary.
 */
final class VisibleAclPolicy {

  private VisibleAclPolicy() {}

  static boolean hasForeignAllow(
      Path path, UserPrincipal owner) throws IOException {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(owner, "owner");
    AclFileAttributeView view =
        Files.getFileAttributeView(
            path,
            AclFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
    return view != null && hasForeignAllow(view.getAcl(), owner);
  }

  static boolean hasForeignAllow(
      List<AclEntry> visibleEntries, UserPrincipal owner) {
    Objects.requireNonNull(visibleEntries, "visibleEntries");
    Objects.requireNonNull(owner, "owner");
    return visibleEntries.stream()
        .anyMatch(
            entry ->
                entry.type() == AclEntryType.ALLOW
                    && !owner.equals(entry.principal()));
  }
}
