package io.emergeos.grapheval;

import io.emergeos.adapters.postgres.Pack010GraphTerminalStoreBridge;
import io.emergeos.adapters.postgres.PostgresProviderValidationAttestor;
import io.emergeos.adapters.postgres.PostgresProviderValidationAttestorTestAccess;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.core.domain.GraphAttemptConflictException;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptIntegrityException;
import io.emergeos.core.domain.GraphAttemptPhase;
import io.emergeos.core.domain.GraphAttributedFailureCode;
import io.emergeos.core.domain.GraphProviderValidationAttestation;
import io.emergeos.core.domain.GraphProviderValidationDecision;
import io.emergeos.core.domain.GraphProviderValidationStatement;
import io.emergeos.core.domain.GraphProviderValidationTranscript;
import java.io.DataInputStream;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Fresh-JVM fault harness for V13 provider-validation attestation. */
public final class Pack010ProviderValidationAttestationHarnessMain {

  static final String TRANSPORT_PROFILE_HASH =
      IntegrityHashes.utf8ContentHash("pack010-v13-process-transport-v1");
  static final String PARSER_PROFILE_HASH =
      IntegrityHashes.utf8ContentHash("pack010-v13-process-parser-v1");
  static final String SCHEMA_PROFILE_HASH =
      IntegrityHashes.utf8ContentHash("pack010-v13-process-schema-v1");

  private static final Duration RELEASE_TIMEOUT = Duration.ofSeconds(20);

  private Pack010ProviderValidationAttestationHarnessMain() {}

  public static void main(String[] args) {
    try {
      int exitCode = run(args);
      if (exitCode != 0) {
        System.exit(exitCode);
      }
    } catch (IllegalArgumentException failure) {
      reject("INPUT_INVALID");
      System.exit(4);
    } catch (GraphAttemptIntegrityException failure) {
      reject("INTEGRITY");
      System.exit(5);
    } catch (Exception failure) {
      reject("HARNESS_FAILED");
      System.exit(3);
    }
  }

  private static int run(String[] args) throws Exception {
    if (args == null || args.length != 9) {
      throw new IllegalArgumentException("ARGUMENT_COUNT_INVALID");
    }
    Mode mode = Mode.parse(args[0]);
    String actor = Pack009ProcessSupport.requireBounded(args[1], "actor");
    if (!actor.matches("[a-z0-9-]{1,64}")) {
      throw new IllegalArgumentException("ACTOR_INVALID");
    }
    int repetition = Integer.parseInt(args[2]);
    if (repetition < 1
        || repetition > Pack010GraphEvalCatalog.GENERATION_REPETITIONS) {
      throw new IllegalArgumentException("REPETITION_INVALID");
    }
    String expectedHeadHash = args[3];
    if (expectedHeadHash == null
        || !expectedHeadHash.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("HEAD_HASH_INVALID");
    }
    String jdbcUrl = Pack009ProcessSupport.requireLoopbackPostgres(args[4]);
    String username =
        Pack009ProcessSupport.requireBounded(args[5], "username");
    if (!"emergeos_provider_attestor".equals(username)) {
      throw new IllegalArgumentException("ROLE_INVALID");
    }
    Path appJar = Pack009ProcessSupport.absoluteRegularFile(args[6]);
    Path testClasses = Pack009ProcessSupport.absoluteDirectory(args[7]);
    Path coordination = Pack009ProcessSupport.absoluteDirectory(args[8]);
    Pack009ProcessSupport.assertCodeSources(
        appJar,
        testClasses,
        Pack010ProviderValidationAttestationHarnessMain.class,
        Pack010ProviderValidationAttestationProcessIT.class,
        Pack010ProviderValidationAttestationHarnessMain.class,
        Pack010ProviderValidationAttestationTestAccessMarker.class,
        Pack010GraphTerminalFixture.class,
        Pack010GraphTerminalStoreBridge.class,
        PostgresProviderValidationAttestorTestAccess.class,
        Pack010CommitBeforeDelegateHardKillDataSource.class);

    DataInputStream input = new DataInputStream(System.in);
    String databasePassword =
        Pack009ProcessSupport.readSecretFrame(input, "databasePassword");
    byte[] privateKeyBytes =
        Pack009ProcessSupport.readSecretBytesFrame(input, "privateKey");
    PrivateKey privateKey;
    try {
      privateKey =
          KeyFactory.getInstance("Ed25519")
              .generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
    } finally {
      Arrays.fill(privateKeyBytes, (byte) 0);
    }
    Pack009ProcessSupport.requireEndOfInput(
        input, "provider validation secret input");

    DataSource base =
        new DriverManagerDataSource(jdbcUrl, username, databasePassword);
    Pack010CommitBeforeDelegateHardKillDataSource commitCutpoint =
        mode == Mode.PRECOMMIT_HALT
            ? new Pack010CommitBeforeDelegateHardKillDataSource(
                base, "PACK010_V13_PRECOMMIT_HARD_KILL")
            : null;
    DataSource effective = commitCutpoint == null ? base : commitCutpoint;
    PostgresProviderValidationAttestor attestor =
        mode == Mode.STAGE_HALT
            ? stageHaltingAttestor(
                effective, coordination, actor)
            : PostgresProviderValidationAttestor.open(effective);

    if (mode == Mode.RACE) {
      Pack009ProcessSupport.writeDurableCreateNew(
          coordination.resolve(actor + ".ready"),
          "PACK010_V13_RACE_READY actor=" + actor
              + " pid=" + ProcessHandle.current().pid());
      Pack009ProcessSupport.awaitFile(
          coordination.resolve("race.release"), RELEASE_TIMEOUT);
    }
    if (commitCutpoint != null) {
      commitCutpoint.arm(
          coordination.resolve(actor + ".pre-commit"), actor, 82);
    }

    var manifest = Pack010GraphEvalCatalog.manifest(repetition);
    GraphAttemptCursor expected =
        new GraphAttemptCursor(
            manifest.principalId(),
            manifest.attemptId(),
            manifest.manifestHash(),
            13,
            13,
            expectedHeadHash,
            GraphAttemptPhase.PROVIDER_PENDING);
    GraphProviderValidationStatement statement = statement(repetition);
    AtomicBoolean signerCalled = new AtomicBoolean();
    try {
      GraphAttemptCursor completed =
          attestor.completeValidation(
              manifest,
              expected,
              statement,
              transcript -> {
                signerCalled.set(true);
                return sign(privateKey, transcript);
              });
      if (mode == Mode.REPLAY) {
        throw new IllegalStateException("REPLAY_WAS_NOT_FENCED");
      }
      String receipt =
          "PACK010_V13_PROVIDER_VALIDATION_COMMITTED"
              + " actor=" + actor
              + " pid=" + ProcessHandle.current().pid()
              + " sequence=" + completed.lastSequence()
              + " decision=" + statement.decision()
              + " signerCalls=1";
      System.out.println(receipt);
      System.out.flush();
      Runtime.getRuntime().halt(83);
      throw new AssertionError("Runtime.halt returned");
    } catch (GraphAttemptConflictException failure) {
      System.out.println(
          "PACK010_V13_PROVIDER_VALIDATION_REJECTED"
              + " actor=" + actor
              + " pid=" + ProcessHandle.current().pid()
              + " reason=FENCED"
              + " signerCalls=" + (signerCalled.get() ? 1 : 0));
      System.out.flush();
      return 4;
    }
  }

  private static PostgresProviderValidationAttestor stageHaltingAttestor(
      DataSource dataSource, Path coordination, String actor) {
    return PostgresProviderValidationAttestorTestAccess.open(
        dataSource,
        point -> {
          if (point
              == PostgresProviderValidationAttestorTestAccess.ProbePoint
                  .AFTER_PROVIDER_VALIDATION_STAGED) {
            String backend =
                JdbcClient.create(dataSource)
                    .sql(
                        "SELECT pg_backend_pid()::text || ':'"
                            + " || pg_current_xact_id()::text")
                    .query(String.class)
                    .single();
            String receipt =
                "PACK010_V13_STAGE_HARD_KILL"
                    + " actor=" + actor
                    + " pid=" + ProcessHandle.current().pid()
                    + " backend=" + backend
                    + " signerCalls=0";
            try {
              Pack009ProcessSupport.writeDurableCreateNew(
                  coordination.resolve(actor + ".staged"), receipt);
            } catch (java.io.IOException failure) {
              throw new IllegalStateException("STAGE_MARKER_FAILED");
            }
            System.out.println(receipt);
            System.out.flush();
            Runtime.getRuntime().halt(81);
          }
        });
  }

  static GraphProviderValidationStatement statement(int repetition) {
    boolean failed = repetition == 3;
    return new GraphProviderValidationStatement(
        Pack010GraphTerminalFixture.catalogAttribution(repetition, 2),
        Pack010GraphEvalCatalog.manifest(repetition).manifestHash(),
        TRANSPORT_PROFILE_HASH,
        PARSER_PROFILE_HASH,
        SCHEMA_PROFILE_HASH,
        failed
            ? GraphProviderValidationDecision.FAILED
            : GraphProviderValidationDecision.STRUCTURED_FINAL,
        IntegrityHashes.utf8ContentHash(
            "pack010-v13-process-decision-v1|" + repetition),
        failed
            ? GraphAttributedFailureCode.MODEL_RESPONSE_MALFORMED
            : null);
  }

  private static GraphProviderValidationAttestation sign(
      PrivateKey privateKey,
      GraphProviderValidationTranscript transcript) {
    try {
      Signature signature = Signature.getInstance("Ed25519");
      signature.initSign(privateKey);
      signature.update(transcript.signatureMaterial());
      return new GraphProviderValidationAttestation(
          transcript, HexFormat.of().formatHex(signature.sign()));
    } catch (java.security.GeneralSecurityException failure) {
      throw new IllegalStateException("V13_SIGNING_FAILED");
    }
  }

  private static void reject(String reason) {
    System.out.println(
        "PACK010_V13_PROVIDER_VALIDATION_REJECTED"
            + " pid=" + ProcessHandle.current().pid()
            + " reason=" + reason);
    System.out.flush();
  }

  private enum Mode {
    STAGE_HALT,
    PRECOMMIT_HALT,
    COMPLETE_AND_HALT,
    RACE,
    REPLAY;

    private static Mode parse(String value) {
      return switch (value) {
        case "stage-halt" -> STAGE_HALT;
        case "precommit-halt" -> PRECOMMIT_HALT;
        case "complete-and-halt" -> COMPLETE_AND_HALT;
        case "race" -> RACE;
        case "replay" -> REPLAY;
        default -> throw new IllegalArgumentException("MODE_INVALID");
      };
    }
  }

  /** Marker type used only to prove test-classes code-source isolation. */
  static final class Pack010ProviderValidationAttestationTestAccessMarker {
    private Pack010ProviderValidationAttestationTestAccessMarker() {}
  }
}
