package io.emergeos.core.application;

import io.emergeos.core.domain.Capture;
import java.util.Objects;

public record CaptureOutcome(Capture capture, boolean created) {

  public CaptureOutcome {
    Objects.requireNonNull(capture, "capture");
  }
}
