package io.emergeos.offlineharness;

import java.lang.ProcessBuilder;
import java.nio.channels.AsynchronousSocketChannel;

final class ForbiddenJdkEscapeFixture {

  private AsynchronousSocketChannel socketChannel;
  private ProcessBuilder processBuilder;

  Class<?> reflectedSocket() throws ClassNotFoundException {
    return Class.forName("java.net.Socket");
  }

  Class<?> reflectedProcessBuilder() throws ClassNotFoundException {
    return Class.forName("java.lang.ProcessBuilder");
  }
}
