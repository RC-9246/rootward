package io.local.rootward.shizuku;

public final class OutletPolicyTest {
  private static void check(
      OutletPolicy.Step expected, String request, String mode, String changed, String owned) {
    if (OutletPolicy.decide(request, mode, changed, owned) != expected)
      throw new AssertionError(request + " " + mode + " " + owned);
  }

  public static void main(String[] args) {
    check(OutletPolicy.Step.OFF, "pause", "on", "before", "");
    check(OutletPolicy.Step.NONE, "pause", "off", "after", "after");
    check(OutletPolicy.Step.ON, "allow", "off", "after", "after");
    check(OutletPolicy.Step.ON, "stop", "off", "after", "after");
    check(OutletPolicy.Step.NONE, "allow", "off", "external", "");
    check(OutletPolicy.Step.NONE, "unknown", "off", "after", "after");
    check(OutletPolicy.Step.UNCERTAIN, "allow", "off", "after", "?");
    check(OutletPolicy.Step.CLEAR, "allow", "off", "external", "after");
    check(OutletPolicy.Step.CLEAR, "pause", "on", "reset", "after");
    check(OutletPolicy.Step.NONE, "allow", "unknown", "x", "");
    check(OutletPolicy.Step.NONE, "pause", "unavailable", "x", "");
    check(OutletPolicy.Step.NONE, "pause", "on", "", "");
    System.out.println("External outlet policy and ownership tests passed");
  }
}
