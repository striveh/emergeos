package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class LoopbackOnlyEnvironmentPostProcessorTest {

  private final LoopbackOnlyEnvironmentPostProcessor guard =
      new LoopbackOnlyEnvironmentPostProcessor();

  @Test
  void acceptsIpv4AndIpv6LoopbackAddresses() {
    assertDoesNotThrow(() -> process("127.0.0.1", "owner-a", null, null));
    assertDoesNotThrow(() -> process("::1", "owner-a", null, null));
  }

  @Test
  void rejectsWildcardLanAndInvalidPrincipalConfigurationBeforeBinding() {
    assertThrows(
        IllegalStateException.class, () -> process("0.0.0.0", "owner-a", null, null));
    assertThrows(IllegalStateException.class, () -> process("::", "owner-a", null, null));
    assertThrows(
        IllegalStateException.class, () -> process("192.168.1.20", "owner-a", null, null));
    assertThrows(IllegalStateException.class, () -> process("127.0.0.1", " ", null, null));
  }

  @Test
  void requiresAnExplicitLoopbackAddressForASeparateManagementServer() {
    assertThrows(
        IllegalStateException.class, () -> process("127.0.0.1", "owner-a", "9090", null));
    assertThrows(
        IllegalStateException.class,
        () -> process("127.0.0.1", "owner-a", "9090", "0.0.0.0"));
    assertDoesNotThrow(
        () -> process("127.0.0.1", "owner-a", "9090", "127.0.0.1"));
  }

  private void process(
      String serverAddress, String principalId, String managementPort, String managementAddress) {
    var environment =
        new MockEnvironment()
            .withProperty("server.address", serverAddress)
            .withProperty("emerge.prototype.principal-id", principalId);
    if (managementPort != null) {
      environment.setProperty("management.server.port", managementPort);
    }
    if (managementAddress != null) {
      environment.setProperty("management.server.address", managementAddress);
    }
    guard.postProcessEnvironment(environment, null);
  }
}
