package io.emergeos.adapters.openai;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Test-only, one-shot live compatibility entrypoint. */
public final class DeepSeekV4FlashResponsesProbeMain {

  private DeepSeekV4FlashResponsesProbeMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 0) {
      reject("ARGUMENTS_FORBIDDEN");
    }
    String credential =
        new BufferedReader(
                new InputStreamReader(
                    System.in, StandardCharsets.UTF_8))
            .readLine();
    if (credential == null
        || credential.length() < 8
        || credential.length() > 512
        || credential.indexOf('\0') >= 0) {
      reject("CREDENTIAL_INPUT_INVALID");
    }
    try {
      DeepSeekV4FlashResponsesProbe.Receipt receipt =
          DeepSeekV4FlashResponsesProbe.execute(credential);
      System.out.println(
          "DEEPSEEK_V4_FLASH_RESPONSES_PROBE"
              + " verdict=PASS"
              + " requests=1"
              + " modelRequested=deepseek-v4-flash"
              + " modelObservedHash="
              + receipt.modelObservedHash()
              + " requestHash="
              + receipt.requestHash()
              + " responseHash="
              + receipt.responseHash()
              + " requestBodyBytes="
              + receipt.requestBodyBytes()
              + " inputTokens="
              + receipt.inputTokens()
              + " cachedInputTokens="
              + receipt.cachedInputTokens()
              + " outputTokens="
              + receipt.outputTokens()
              + " reasoningOutputTokens="
              + receipt.reasoningOutputTokens()
              + " totalTokens="
              + receipt.totalTokens()
              + " listPriceProfileId="
              + receipt.listPriceProfileId()
              + " acceptedListPriceEstimateCeilingUsd="
              + receipt.acceptedListPriceEstimateCeilingUsd()
                  .toPlainString()
              + " listPriceEstimateUsd="
              + receipt.listPriceEstimateUsd().toPlainString()
              + " externalProviderNetwork=1"
              + " rawBodiesPersisted=0"
              + " credentialPersisted=0"
              + " shippingFirstPartyRouteWired=false");
    } catch (DeepSeekV4FlashResponsesProbe.ProbeRejected rejected) {
      reject(rejected.getMessage());
    } finally {
      credential = null;
    }
  }

  private static void reject(String reason) {
    System.out.println(
        "DEEPSEEK_V4_FLASH_RESPONSES_PROBE"
            + " verdict=REJECTED reason="
            + reason
            + " requestsAtMost=1"
            + " rawBodiesPersisted=0"
            + " credentialPersisted=0"
            + " shippingFirstPartyRouteWired=false");
    System.exit(4);
  }
}
