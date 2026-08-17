package io.emergeos.core.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GraphExactPicoProviderSignatureVerifierTest {

  @Test
  void verifiesOnlyTheCanonicalFramedTranscriptAndRedactsDetails()
      throws Exception {
    KeyPair trusted =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    String keyFingerprint =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(trusted.getPublic().getEncoded()));
    GraphExactPicoProviderValidationChallenge challenge =
        challenge(keyFingerprint);
    byte[] expected =
        GraphProviderValidationCanonical.frame(
            "emergeos.exact-provider-validation-transcript.v16",
            challenge.challengeHash(),
            challenge.statementHash(),
            challenge.attributionHash(),
            challenge.eventHash(),
            challenge.overlayHeadHash(),
            "STRUCTURED_FINAL",
            challenge.decisionHash(),
            "");
    assertArrayEquals(expected, challenge.signatureMaterial());
    String signature = sign(trusted, expected);
    GraphExactPicoProviderSignatureVerifier.verifyOrThrow(
        challenge, trusted.getPublic().getEncoded(), signature);

    KeyPair foreign =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    assertThrows(
        GraphAttemptIntegrityException.class,
        () ->
            GraphExactPicoProviderSignatureVerifier.verifyOrThrow(
                challenge,
                trusted.getPublic().getEncoded(),
                sign(foreign, expected)));
    byte[] wrongAnchor = foreign.getPublic().getEncoded();
    assertThrows(
        GraphAttemptIntegrityException.class,
        () ->
            GraphExactPicoProviderSignatureVerifier.verifyOrThrow(
                challenge, wrongAnchor, signature));
    assertThrows(
        GraphAttemptIntegrityException.class,
        () ->
            GraphExactPicoProviderSignatureVerifier.verifyOrThrow(
                challenge, new byte[] {1, 2, 3}, signature));
    assertThrows(
        GraphAttemptIntegrityException.class,
        () ->
            GraphExactPicoProviderSignatureVerifier.verifyOrThrow(
                challenge, new byte[129], signature));
    assertTrue(!challenge.toString().contains(keyFingerprint));
    assertTrue(!challenge.toString().contains(challenge.validationNonce().toString()));
    assertTrue(challenge.toString().contains("details=<redacted>"));
  }

  @Test
  void rejectsCanonicalIdentityOrTranscriptDrift() throws Exception {
    KeyPair key =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    String fingerprint =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(key.getPublic().getEncoded()));
    GraphExactPicoProviderValidationChallenge challenge =
        challenge(fingerprint);
    IllegalArgumentException drift =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new GraphExactPicoProviderValidationChallenge(
                    challenge.protocolVersion(),
                    challenge.databaseName(),
                    challenge.databaseOid(),
                    challenge.schemaOid(),
                    challenge.attestorRoleOid(),
                    challenge.principalId(),
                    challenge.attemptId(),
                    challenge.manifestHash(),
                    challenge.requirementHash(),
                    challenge.baseValidationPolicyHash(),
                    challenge.keyId(),
                    challenge.keyFingerprint(),
                    challenge.validationNonce(),
                    challenge.issuedAt(),
                    challenge.expiresAt(),
                    challenge.statementHash(),
                    challenge.attributionHash(),
                    challenge.eventHash(),
                    challenge.overlayHeadHash(),
                    challenge.decision(),
                    hash("drift-decision"),
                    challenge.failureCode(),
                    challenge.challengeHash(),
                    challenge.transcriptHash()));
    assertEquals(
        "exact provider validation challenge hash is inconsistent",
        drift.getMessage());
  }

  @Test
  void acceptsTheBoundedPostgresNameDomainUsedByV16() throws Exception {
    KeyPair key =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    String fingerprint =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(key.getPublic().getEncoded()));
    GraphExactPicoProviderValidationChallenge challenge =
        challenge(fingerprint, "本地 V16 数据库");
    GraphExactPicoProviderSignatureVerifier.verifyOrThrow(
        challenge,
        key.getPublic().getEncoded(),
        sign(key, challenge.signatureMaterial()));
  }

  private static GraphExactPicoProviderValidationChallenge challenge(
      String keyFingerprint) {
    return challenge(keyFingerprint, "pack010_v17");
  }

  private static GraphExactPicoProviderValidationChallenge challenge(
      String keyFingerprint, String database) {
    String protocol = "PICO_OVERLAY_V1";
    long databaseOid = 16_001L;
    long schemaOid = 2_200L;
    long roleOid = 16_002L;
    String principal = "pack010-v17-principal";
    String attempt = hash("attempt");
    String manifest = hash("manifest");
    String requirement = hash("requirement");
    String policy = hash("policy");
    String keyId = "pack010-v17-test-key";
    UUID nonce = UUID.fromString("5f3a7c7f-5c4c-44ac-98d4-74d51335c160");
    Instant issued = Instant.parse("2026-08-12T02:00:00Z");
    Instant expires = Instant.parse("2026-08-12T02:00:10Z");
    String statement = hash("statement");
    String attribution = hash("attribution");
    String event = hash("event");
    String head = hash("head");
    String decision = hash("decision");
    String challengeHash =
        GraphProviderValidationCanonical.hash(
            "emergeos.exact-provider-validation-challenge.v16",
            protocol,
            database,
            Long.toString(databaseOid),
            Long.toString(schemaOid),
            Long.toString(roleOid),
            principal,
            attempt,
            manifest,
            requirement,
            policy,
            keyId,
            keyFingerprint,
            nonce.toString(),
            "1786500000000000",
            "1786500010000000");
    String transcriptHash =
        GraphProviderValidationCanonical.hash(
            "emergeos.exact-provider-validation-transcript.v16",
            challengeHash,
            statement,
            attribution,
            event,
            head,
            "STRUCTURED_FINAL",
            decision,
            "");
    return new GraphExactPicoProviderValidationChallenge(
        protocol,
        database,
        databaseOid,
        schemaOid,
        roleOid,
        principal,
        attempt,
        manifest,
        requirement,
        policy,
        keyId,
        keyFingerprint,
        nonce,
        issued,
        expires,
        statement,
        attribution,
        event,
        head,
        GraphProviderValidationDecision.STRUCTURED_FINAL,
        decision,
        null,
        challengeHash,
        transcriptHash);
  }

  private static String sign(KeyPair pair, byte[] material)
      throws Exception {
    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(pair.getPrivate());
    signer.update(material);
    return HexFormat.of().formatHex(signer.sign());
  }

  private static String hash(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes()));
    } catch (Exception impossible) {
      throw new AssertionError(impossible);
    }
  }
}
