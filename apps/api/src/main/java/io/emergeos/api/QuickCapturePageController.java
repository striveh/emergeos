package io.emergeos.api;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the loopback-only Quick Capture entry from the packaged application. */
@RestController
final class QuickCapturePageController {

  private static final Resource PAGE =
      new ClassPathResource("static/capture/index.html");

  @GetMapping(value = {"/capture", "/capture/"}, produces = MediaType.TEXT_HTML_VALUE)
  ResponseEntity<Resource> capturePage() {
    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(PAGE);
  }
}
