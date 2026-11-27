package io.local.rootward.shizuku;

public final class ChargePhaseTest {
  private static final int LOW = 50, HIGH = 75;

  private static void expect(int actual, int expected, String step) {
    if (actual != expected)
      throw new AssertionError(step + " was " + actual + ", expected " + expected);
  }

  public static void main(String[] args) {
    int wait = ChargePhase.sample(ChargePhase.FILL, 75, LOW, HIGH);
    expect(wait, ChargePhase.WAIT, "reach upper limit");
    // Each sample can be from USB connect/disconnect, activity restart or a regular timer.
    for (int level : new int[] {75, 73, 74, 70, 70, 66, -1, 58, 51}) {
      wait = ChargePhase.sample(wait, level, LOW, HIGH);
      expect(wait, ChargePhase.WAIT, "hold through reconnect at " + level);
    }
    int manual = ChargePhase.toggleManual(wait, 58, LOW, HIGH);
    expect(manual, ChargePhase.MANUAL, "manual continue");
    expect(ChargePhase.sample(manual, 71, LOW, HIGH), manual, "manual survives reconnect");
    expect(ChargePhase.toggleManual(manual, 71, LOW, HIGH), wait, "cancel restores hold");
    expect(ChargePhase.sample(manual, 75, LOW, HIGH), wait, "manual ends at original cap");
    expect(
        ChargePhase.sample(manual, 49, LOW, HIGH),
        ChargePhase.FILL,
        "manual resets below lower limit");
    expect(ChargePhase.toggleManual(wait, 75, LOW, HIGH), wait, "no manual at cap");
    expect(ChargePhase.toggleManual(wait, -1, LOW, HIGH), wait, "unknown reading");
    int fill = ChargePhase.sample(wait, 50, LOW, HIGH);
    expect(fill, ChargePhase.FILL, "at exact lower limit");
    expect(ChargePhase.sample(fill, 68, LOW, HIGH), fill, "resume until original cap");
    System.out.println("ChargePhase tests passed");
  }
}
