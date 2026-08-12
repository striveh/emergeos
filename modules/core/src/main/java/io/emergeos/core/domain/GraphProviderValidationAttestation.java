package io.emergeos.core.domain;

import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;
import java.util.Objects;

/** Ephemeral-key signature over one bounded V13 validation transcript. */
public record GraphProviderValidationAttestation(
    GraphProviderValidationTranscript transcript,
    String signatureHex) {

  public GraphProviderValidationAttestation {
    transcript = Objects.requireNonNull(transcript, "transcript");
    if (signatureHex == null
        || !signatureHex.matches("[a-f0-9]{128}")) {
      throw new IllegalArgumentException(
          "provider validation signature is invalid");
    }
  }

  public boolean verifiesWith(byte[] publicKeyDer) {
    try {
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(
          KeyFactory.getInstance("Ed25519")
              .generatePublic(
                  new X509EncodedKeySpec(
                      Objects.requireNonNull(
                          publicKeyDer, "publicKeyDer"))));
      verifier.update(transcript.signatureMaterial());
      return verifier.verify(HexFormat.of().parseHex(signatureHex));
    } catch (RuntimeException | java.security.GeneralSecurityException failure) {
      throw new IllegalArgumentException(
          "provider validation public anchor is invalid");
    }
  }

  @Override
  public String toString() {
    return "GraphProviderValidationAttestation[transcriptHash="
        + transcript.transcriptHash()
        + ", signature=<redacted>]";
  }
}
