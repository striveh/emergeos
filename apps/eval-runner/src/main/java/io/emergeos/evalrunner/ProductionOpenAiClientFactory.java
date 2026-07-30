package io.emergeos.evalrunner;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.LogLevel;
import java.net.Proxy;
import java.time.Duration;

final class ProductionOpenAiClientFactory
    implements SyntheticEvalExecutor.OpenAiClientFactory {

  static final String PRODUCTION_BASE_URL =
      "https://api.openai.com/v1";

  @Override
  public OpenAIClient create(String apiKey) {
    return OpenAIOkHttpClient.builder()
        .apiKey(apiKey)
        .baseUrl(PRODUCTION_BASE_URL)
        .proxy(Proxy.NO_PROXY)
        .maxRetries(0)
        .timeout(
            Duration.ofMillis(
                SyntheticEvalCatalog.profile().deadlineMs()))
        .logLevel(LogLevel.OFF)
        .build();
  }
}
