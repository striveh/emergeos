package io.emergeos.offlineharness;

@FunctionalInterface
interface OfflineComparisonPersistenceObserver {

  void observed(Phase phase);

  static OfflineComparisonPersistenceObserver noop() {
    return ignored -> {};
  }

  enum Phase {
    CLAIM_WRITE_STARTED,
    CLAIM_DURABLE,
    PENDING_WRITE_STARTED,
    PENDING_FILE_FSYNC_COMPLETE,
    PENDING_FILE_DURABLE,
    REPORT_COMMIT_STARTED,
    REPORT_LINK_COMMIT_COMPLETE,
    REPORT_DIRECTORY_DURABLE
  }
}
