package io.emergeos.api;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

public final class LoopbackOnlyEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    String serverAddress = environment.getProperty("server.address");
    requireLoopback("server.address", serverAddress);

    String principalId = environment.getProperty("emerge.prototype.principal-id");
    if (principalId == null || principalId.isBlank() || principalId.length() > 200) {
      throw new IllegalStateException(
          "emerge.prototype.principal-id must be configured with 1-200 non-blank characters");
    }

    String managementAddress = environment.getProperty("management.server.address");
    String managementPort = environment.getProperty("management.server.port");
    if (managementPort != null && (managementAddress == null || managementAddress.isBlank())) {
      throw new IllegalStateException(
          "a separate management.server.port requires an explicit loopback management.server.address");
    }
    if (managementAddress != null) {
      requireLoopback("management.server.address", managementAddress);
    }
  }

  @Override
  public int getOrder() {
    return ConfigDataEnvironmentPostProcessor.ORDER + 1;
  }

  static void requireLoopback(String propertyName, String configuredAddress) {
    if (configuredAddress == null || configuredAddress.isBlank()) {
      throw new IllegalStateException(
          "unauthenticated prototype requires explicit loopback-only " + propertyName);
    }
    try {
      InetAddress[] resolved = InetAddress.getAllByName(configuredAddress);
      for (InetAddress address : resolved) {
        if (!address.isLoopbackAddress() || address.isAnyLocalAddress()) {
          throw new IllegalStateException(
              "unauthenticated prototype requires loopback-only "
                  + propertyName
                  + ": "
                  + configuredAddress);
        }
      }
    } catch (UnknownHostException invalidAddress) {
      throw new IllegalStateException(
          "unable to resolve loopback-only " + propertyName + ": " + configuredAddress,
          invalidAddress);
    }
  }
}
