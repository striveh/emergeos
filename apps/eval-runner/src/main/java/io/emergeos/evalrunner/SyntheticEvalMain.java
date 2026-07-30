package io.emergeos.evalrunner;

public final class SyntheticEvalMain {

  private SyntheticEvalMain() {}

  public static void main(String[] args) {
    System.out.println(new SyntheticEvalRunner().preflightReceipt());
  }
}
