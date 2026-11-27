package io.local.rootward.shizuku;

import static io.local.rootward.shizuku.ChargeBehaviour.*;
import static io.local.rootward.shizuku.ChargePhase.*;
import static io.local.rootward.shizuku.HardwareCruise.NONE;

import io.local.rootward.shizuku.HardwareCruise.Hold;

/** Combines persisted phase transitions with the operations proposed for a compatible driver. */
public final class HardwareCruiseTest {
  private static void expect(int expected, int actual, String label) {
    if (expected != actual)
      throw new AssertionError(label + ": expected " + expected + ", got " + actual);
  }

  private static int next(int driver, boolean owned, int phase, int level, boolean hot) {
    return HardwareCruise.operation(
        true, driver, owned ? Hold.OWNED : Hold.NONE, phase, level, 285, hot);
  }

  public static void main(String[] args) {
    if (Hold.OWNED.reconcile(INHIBITED, false) != Hold.UNCERTAIN
        || Hold.OWNED.reconcile(INHIBITED, true) != Hold.OWNED
        || Hold.UNCERTAIN.reconcile(INHIBITED, true) != Hold.UNCERTAIN
        || Hold.OWNED.reconcile(EXTERNAL, true) != Hold.NONE
        || Hold.OWNED.reconcile(DENIED, true) != Hold.OWNED
        || Hold.UNCERTAIN.reconcile(AUTO, false) != Hold.NONE
        || Hold.NONE.reconcile(INHIBITED, true) != Hold.NONE)
      throw new AssertionError("ownership across restart, disconnect and OEM mode changes");
    int phase = sample(FILL, 75, 50, 75);
    expect(ACQUIRE, next(AUTO, false, phase, 75, false), "75% stops charging");
    for (int level : new int[] {74, 67, 58, 58, 51}) {
      phase = sample(phase, level, 50, 75);
      expect(NONE, next(INHIBITED, true, phase, level, false), "natural fall and cable changes");
    }
    expect(ACQUIRE, next(AUTO, false, phase, 51, false), "driver reset across cable reconnect");
    int manual = toggleManual(phase, 62, 50, 75);
    expect(RESUME, next(INHIBITED, true, manual, 62, false), "single-cycle override");
    expect(NONE, next(AUTO, false, sample(manual, 73, 50, 75), 73, false), "fill continues");
    expect(
        ACQUIRE, next(AUTO, false, sample(manual, 75, 50, 75), 75, false), "override ends at cap");
    expect(
        ACQUIRE, next(AUTO, false, toggleManual(manual, 62, 50, 75), 62, false), "cancel override");
    expect(RESUME, next(INHIBITED, true, sample(phase, 50, 50, 75), 50, false), "resume at 50");
    expect(ACQUIRE, next(AUTO, false, manual, 62, true), "heat overrides manual resume");
    expect(NONE, next(INHIBITED, true, FILL, 49, true), "hot at the lower limit");
    expect(RESUME, next(INHIBITED, true, FILL, 49, false), "cool and allowed to fill");
    if (!HardwareCruise.hot(false, 400, 40)
        || !HardwareCruise.hot(true, 385, 40)
        || HardwareCruise.hot(true, 380, 40)
        || HardwareCruise.hot(false, 399, 40)
        || !HardwareCruise.hot(true, Integer.MIN_VALUE, 40))
      throw new AssertionError("heat hysteresis");
    expect(NONE, next(INHIBITED, false, FILL, 49, false), "leave existing external inhibition");
    expect(NONE, next(EXTERNAL, true, WAIT, 77, true), "leave OEM-selected mode");
    expect(NONE, next(DENIED, true, FILL, 49, false), "cannot write after access revoked");
    expect(
        NONE,
        HardwareCruise.operation(false, INHIBITED, Hold.NONE, WAIT, -1, 0, true),
        "stopping with external hold");
    expect(
        RESUME,
        HardwareCruise.operation(false, INHIBITED, Hold.OWNED, WAIT, -1, 0, true),
        "stopping releases only our hold to OEM control");
    expect(
        NONE,
        HardwareCruise.operation(false, INHIBITED, Hold.UNCERTAIN, FILL, 49, 280, false),
        "unknown owner after a lost write acknowledgement");
    expect(
        NONE,
        HardwareCruise.operation(true, INHIBITED, Hold.OWNED, MANUAL, 69, Integer.MIN_VALUE, false),
        "missing temperature cannot authorize a resume");
    expect(
        NONE, next(INHIBITED, true, FILL, -1, false), "missing capacity cannot authorize a resume");
    System.out.println("hardware cruise policy tests passed");
  }
}
