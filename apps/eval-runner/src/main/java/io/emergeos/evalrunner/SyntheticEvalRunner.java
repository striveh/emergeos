package io.emergeos.evalrunner;

final class SyntheticEvalRunner {

  static final String CASE_ID = "openai-public-draft-003-r1";

  String preflightReceipt() {
    return "EVAL_PREFLIGHT_RECEIPT status=PREFLIGHT_READY"
        + " caseId="
        + CASE_ID
        + " keyReads=0 clientFactories=0 modelFactories=0"
        + " runStarts=0 httpRequests=0 markerCreated=false";
  }
}
