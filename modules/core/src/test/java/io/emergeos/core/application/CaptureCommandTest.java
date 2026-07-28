package io.emergeos.core.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.contracts.DataClass;
import org.junit.jupiter.api.Test;

class CaptureCommandTest {

  @Test
  void computesAStableServerSideHashFromSemanticPayloadFields() {
    var command =
        new CaptureCommand(
            "owner-a",
            "nonce-1",
            "synthetic thought",
            "TEXT",
            "focused-red",
            DataClass.PERSONAL);

    assertEquals(
        "6524fe45ae0564eeb9d6fe9305d677e90641d56c9a59463621c765861a456a45",
        command.requestHash());

    assertEquals(
        command.requestHash(),
        new CaptureCommand(
                "owner-b",
                "nonce-2",
                "synthetic thought",
                "TEXT",
                "focused-red",
                DataClass.PERSONAL)
            .requestHash());
    assertNotEquals(
        command.requestHash(),
        new CaptureCommand(
                "owner-a",
                "nonce-1",
                "different thought",
                "TEXT",
                "focused-red",
                DataClass.PERSONAL)
            .requestHash());
    assertNotEquals(
        command.requestHash(),
        new CaptureCommand(
                "owner-a",
                "nonce-1",
                "synthetic thought",
                "LINK",
                "focused-red",
                DataClass.PERSONAL)
            .requestHash());
    assertNotEquals(
        command.requestHash(),
        new CaptureCommand(
                "owner-a",
                "nonce-1",
                "synthetic thought",
                "TEXT",
                "different-ref",
                DataClass.PERSONAL)
            .requestHash());
    assertNotEquals(
        command.requestHash(),
        new CaptureCommand(
                "owner-a",
                "nonce-1",
                "synthetic thought",
                "TEXT",
                "focused-red",
                DataClass.PUBLIC)
            .requestHash());
  }

  @Test
  void validatesNonceAndSupportedCaptureSourceTypes() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CaptureCommand(
                "owner-a", " ", "thought", "TEXT", "focused-red", DataClass.PERSONAL));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CaptureCommand(
                "owner-a",
                "n".repeat(129),
                "thought",
                "TEXT",
                "focused-red",
                DataClass.PERSONAL));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CaptureCommand(
                "owner-a",
                "nonce-1",
                "thought",
                "REMOTE_AUDIO_URL",
                "focused-red",
                DataClass.PERSONAL));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CaptureCommand(
                "owner-a",
                "nonce-1",
                "thought\0with-nul",
                "TEXT",
                "focused-red",
                DataClass.PERSONAL));
  }
}
