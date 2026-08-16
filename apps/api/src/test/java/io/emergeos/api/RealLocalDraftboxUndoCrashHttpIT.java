package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Process-crash and response-loss Acceptance for the local logical-Undo Receipt. */
@Testcontainers
class RealLocalDraftboxUndoCrashHttpIT {

  private static final String OWNER = "logical-undo-crash-owner";

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  @Test
  void precommitKillRollsBackAndLostResponseReplaysAfterFreshJvm() throws Exception {
    LogicalUndoFaultHarness harness = new LogicalUndoFaultHarness(POSTGRES);
    CompletableFuture<HttpResponse<String>> blockedUndo = null;
    boolean blockerInstalled = false;
    try {
      LogicalUndoFaultHarness.RunningApplication writer =
          harness.startApplication(OWNER, "crash-writer");
      LogicalUndoFaultHarness.ActiveDraft rollbackDraft =
          harness.createActiveDraft(writer, "precommit-kill");
      String rollbackNonce = "logical-undo-crash-precommit-nonce";
      LogicalUndoFaultHarness.UndoRequest rollbackRequest =
          harness.undoRequest(rollbackDraft, rollbackNonce);
      LogicalUndoFaultHarness.TruthDigest rollbackTruth = harness.truthDigest();
      LogicalUndoFaultHarness.DatabaseDigest rollbackBaseline = harness.databaseDigest();

      harness.installUndoPrecommitBlocker();
      blockerInstalled = true;
      try (Connection lockConnection = harness.connection();
          Statement lock = lockConnection.createStatement()) {
        lock.setQueryTimeout(5);
        int lockHolderPid = harness.backendPid(lockConnection);
        lock.execute("SELECT pg_catalog.pg_advisory_lock(" + LogicalUndoFaultHarness.PRECOMMIT_LOCK + ")");
        boolean blockerHeld = true;
        try {
          blockedUndo =
              harness.sendAsync(
                  writer,
                  "POST",
                  harness.undoPath(rollbackDraft.attemptId()),
                  rollbackRequest.body());
          harness.awaitUndoPrecommitBlock(writer.applicationName(), lockHolderPid);
          assertEquals(0, harness.undoCount(rollbackDraft.attemptId()));
          assertEquals(rollbackTruth, harness.truthDigest());
          harness.forceStop(writer);
          unlockUndoPrecommitBlocker(lock);
          blockerHeld = false;
          harness.awaitNoApplicationBackends(writer);
        } finally {
          if (blockerHeld) {
            unlockUndoPrecommitBlocker(lock);
            blockerHeld = false;
          }
        }
      }
      blockedUndo.cancel(true);
      harness.dropUndoPrecommitBlocker();
      blockerInstalled = false;

      assertEquals(0, harness.undoCount(rollbackDraft.attemptId()));
      assertEquals(rollbackTruth, harness.truthDigest());
      assertEquals(rollbackBaseline, harness.databaseDigest());
      assertEquals(1, harness.countActiveRawDrafts(OWNER));

      LogicalUndoFaultHarness.RunningApplication recoveryWriter =
          harness.startApplication(OWNER, "crash-recovery-writer");
      HttpResponse<String> recoveredActive =
          harness.send(
              recoveryWriter,
              "GET",
              harness.approvalPath(rollbackDraft.attemptId()),
              null);
      assertEquals(200, recoveredActive.statusCode());
      harness.assertPrivateNoStore(recoveredActive);
      assertEquals(
          harness.bodyHash(rollbackDraft.canonicalActiveBody()),
          harness.bodyHash(recoveredActive.body()));
      assertEquals("ACTIVE", JsonPath.read(recoveredActive.body(), "$.localDraft.state"));
      assertEquals(true, JsonPath.read(recoveredActive.body(), "$.undoAvailable"));
      harness.assertEvolutionResponseSurfaceAbsent(recoveredActive.body());

      HttpResponse<String> rollbackRetry =
          harness.send(
              recoveryWriter,
              "POST",
              harness.undoPath(rollbackDraft.attemptId()),
              rollbackRequest.body());
      assertEquals(201, rollbackRetry.statusCode());
      harness.assertPrivateNoStore(rollbackRetry);
      assertEquals(
          harness.approvalPath(rollbackDraft.attemptId()),
          rollbackRetry.headers().firstValue("Location").orElse(null));
      harness.assertLogicallyUndone(rollbackRetry.body(), rollbackDraft, rollbackNonce);
      harness.assertEvolutionResponseSurfaceAbsent(rollbackRetry.body());
      assertEquals(1, harness.undoCount(rollbackDraft.attemptId()));
      String rollbackReceipt = harness.undoReceiptDigest(rollbackDraft.attemptId());

      LogicalUndoFaultHarness.ActiveDraft responseLossDraft =
          harness.createActiveDraft(recoveryWriter, "response-loss");
      String responseLossNonce = "logical-undo-crash-response-loss-nonce";
      LogicalUndoFaultHarness.UndoRequest responseLossRequest =
          harness.undoRequest(responseLossDraft, responseLossNonce);
      LogicalUndoFaultHarness.TruthDigest responseLossTruth = harness.truthDigest();
      harness.fireAndForget(
          recoveryWriter,
          harness.undoPath(responseLossDraft.attemptId()),
          responseLossRequest.body());
      harness.awaitUndoCommitted(responseLossDraft.attemptId());
      assertEquals(responseLossTruth, harness.truthDigest());
      String committedReceipt = harness.undoReceiptDigest(responseLossDraft.attemptId());
      LogicalUndoFaultHarness.DatabaseDigest committedDatabase = harness.databaseDigest();
      assertEquals(rollbackReceipt, harness.undoReceiptDigest(rollbackDraft.attemptId()));

      harness.forceStop(recoveryWriter);
      harness.awaitNoApplicationBackends(recoveryWriter);
      LogicalUndoFaultHarness.RunningApplication freshReader =
          harness.startApplication(OWNER, "crash-fresh-reader");
      HttpResponse<String> recoveredUndone =
          harness.send(
              freshReader,
              "GET",
              harness.approvalPath(responseLossDraft.attemptId()),
              null);
      assertEquals(200, recoveredUndone.statusCode());
      harness.assertPrivateNoStore(recoveredUndone);
      harness.assertLogicallyUndone(
          recoveredUndone.body(), responseLossDraft, responseLossNonce);
      harness.assertEvolutionResponseSurfaceAbsent(recoveredUndone.body());

      HttpResponse<String> responseLossReplay =
          harness.send(
              freshReader,
              "POST",
              harness.undoPath(responseLossDraft.attemptId()),
              responseLossRequest.body());
      assertEquals(200, responseLossReplay.statusCode());
      harness.assertPrivateNoStore(responseLossReplay);
      assertEquals(
          harness.approvalPath(responseLossDraft.attemptId()),
          responseLossReplay.headers().firstValue("Location").orElse(null));
      assertEquals(
          harness.bodyHash(recoveredUndone.body()),
          harness.bodyHash(responseLossReplay.body()),
          "lost-response replay must return byte-identical canonical JSON");
      assertEquals(committedReceipt, harness.undoReceiptDigest(responseLossDraft.attemptId()));
      assertEquals(rollbackReceipt, harness.undoReceiptDigest(rollbackDraft.attemptId()));
      assertEquals(responseLossTruth, harness.truthDigest());
      assertEquals(committedDatabase, harness.databaseDigest());
      assertEquals(2, harness.countActiveRawDrafts(OWNER));
      assertEquals(2, harness.count("local_draft_undo_receipts"));
      assertEquals(0, harness.count("action_receipts"));
      harness.assertEvolutionResponseSurfaceAbsent(responseLossReplay.body());
      harness.finishAndAssertNoProviderCalls();

      System.out.println(
          "REAL_LOCAL_DRAFTBOX_UNDO_CRASH_RECEIPT "
              + "precommitKill=ROLLED_BACK backendDrained=true freshActiveGet=200 retry=201 "
              + "responseLoss=COMMITTED freshUndoneGet=200 replay=200 rowUnchanged=true "
              + "localOnly=true synthetic=true providerBase=LOCAL_COUNTING_TRAP providerCalls=0 "
              + "legacyActionReceipts=0 shippingLive=DISABLED rawDraftState=ACTIVE "
              + "projectedState=LOGICALLY_UNDONE "
              + "retention=CAPTURE_ARTIFACT_HISTORY_RETAINED "
              + "reflectionResponseSurface=ABSENT workingSelfResponseSurface=ABSENT "
              + "reflectionMutation=NOT_OBSERVABLE workingSelfMutation=NOT_OBSERVABLE "
              + "authority=ENGINEERING_ONLY live=DISABLED");
    } finally {
      if (blockedUndo != null) {
        blockedUndo.cancel(true);
      }
      try {
        harness.close();
      } finally {
        if (blockerInstalled) {
          harness.dropUndoPrecommitBlocker();
        }
      }
    }
  }

  private static void unlockUndoPrecommitBlocker(Statement lock) throws Exception {
    lock.execute(
        "SELECT pg_catalog.pg_advisory_unlock("
            + LogicalUndoFaultHarness.PRECOMMIT_LOCK
            + ")");
  }
}
