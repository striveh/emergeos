package io.emergeos.core.domain;

import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;
import java.util.Objects;

/** Reviewed V16 public-key verifier; contains no signing or key custody API. */
public final class GraphExactPicoProviderSignatureVerifier {

  private GraphExactPicoProviderSignatureVerifier() {}

  public static void verifyOrThrow(
      GraphExactPicoProviderValidationChallenge challenge,
      byte[] publicKeyDer,
      String signatureHex) {
    try {
      GraphExactPicoProviderValidationChallenge checked =
          Objects.requireNonNull(challenge, "challenge");
      byte[] anchor =
          Objects.requireNonNull(publicKeyDer, "publicKeyDer").clone();
      if (anchor.length < 32
          || anchor.length > 128
          || signatureHex == null
          || !signatureHex.matches("[a-f0-9]{128}")) {
        throw new GraphAttemptIntegrityException();
      }
      byte[] expectedFingerprint =
          HexFormat.of().parseHex(checked.keyFingerprint());
      byte[] observedFingerprint =
          MessageDigest.getInstance("SHA-256").digest(anchor);
      if (!MessageDigest.isEqual(
          expectedFingerprint, observedFingerprint)) {
        throw new GraphAttemptIntegrityException();
      }
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(
          KeyFactory.getInstance("Ed25519")
              .generatePublic(new X509EncodedKeySpec(anchor)));
      verifier.update(checked.signatureMaterial());
      if (!verifier.verify(HexFormat.of().parseHex(signatureHex))) {
        throw new GraphAttemptIntegrityException();
      }
    } catch (GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (RuntimeException | java.security.GeneralSecurityException failure) {
      throw new GraphAttemptIntegrityException();
    }
  }
}
