package io.local.rootward.shizuku;

/** A reminder cycle, independent of cable and process lifecycle. */
final class ChargePhase {
  static final int FILL = 0, WAIT = 1, MANUAL = 2;

  static int toggleManual(int phase, int level, int lower, int upper) {
    int current = sample(phase, level, lower, upper);
    if (level <= lower || level >= upper) return current;
    if (current == WAIT) return MANUAL;
    if (current == MANUAL) return WAIT;
    return current;
  }

  static int sample(int phase, int level, int lower, int upper) {
    if (level < 0 || level > 100) return phase;
    if (level >= upper) return WAIT;
    if (level <= lower) return FILL;
    return phase;
  }
}
