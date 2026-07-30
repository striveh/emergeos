package io.emergeos.evalrunner;

import java.io.Console;

final class SystemInteractiveConsole
    implements OneShotOperatorGate.InteractiveConsole {

  private final Console console = System.console();

  @Override
  public boolean available() {
    return console != null;
  }

  @Override
  public String readLine(String prompt) {
    return console == null ? null : console.readLine("%s", prompt);
  }
}
