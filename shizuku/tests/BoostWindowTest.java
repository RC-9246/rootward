package io.local.rootward.shizuku;

import static io.local.rootward.shizuku.BoostWindow.Stage.*;

public class BoostWindowTest {
  private static final long START = 1800000000000L;
  static BoostWindow.Stage at(long delta, int level) {
    return BoostWindow.stage(START, START + delta, true, ChargePhase.WAIT, level, 50, 75);
  }
  static void check(boolean x) { if (!x) throw new AssertionError(); }
  public static void main(String[] args) {
    check(at(-1, 60) == PENDING && at(0, 60) == OPEN);
    check(at(3599999, 60) == OPEN && at(3600000, 60) == FINISHED);
    check(at(0, 50) == FINISHED && at(-1, 49) == FINISHED && at(0, 75) == FINISHED);
    check(BoostWindow.stage(0, START, true, ChargePhase.WAIT, 60, 50, 75) == NONE);
    check(BoostWindow.stage(START, START, false, ChargePhase.WAIT, 60, 50, 75) == FINISHED);
    check(BoostWindow.stage(START, START, true, ChargePhase.MANUAL, 60, 50, 75) == FINISHED);
    check(BoostWindow.stage(START, START, true, ChargePhase.FILL, 60, 50, 75) == FINISHED);
    check(!BoostWindow.permits(OPEN, 60, 50, 75, 430, true));
    check(!BoostWindow.permits(OPEN, -1, 50, 75, 340, false));
    check(!BoostWindow.permits(OPEN, 60, 50, 75, Integer.MIN_VALUE, false));
    check(BoostWindow.permits(OPEN, 60, 50, 75, 340, false));
    check(!BoostWindow.permits(PENDING, 60, 50, 75, 340, false));
    check(!BoostWindow.permits(FINISHED, 60, 50, 75, 340, false));
    System.out.println("One-off boost window, thermal override and natural cycle boundaries passed");
  }
}
