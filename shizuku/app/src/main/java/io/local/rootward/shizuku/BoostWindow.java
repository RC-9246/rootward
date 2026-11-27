package io.local.rootward.shizuku;

/** Evaluates a one-off appointment against an observed strict hold. No cable input by design. */
final class BoostWindow {
  static final long HOUR = 3600000L;
  enum Stage { NONE, PENDING, OPEN, FINISHED }

  static Stage stage(long start, long now, boolean sameLimits, int phase,
      int level, int lower, int upper) {
    if (start <= 0) return Stage.NONE;
    if (!sameLimits || phase != ChargePhase.WAIT || now - start >= HOUR
        || (level >= 0 && level <= 100 && (level <= lower || level >= upper))) return Stage.FINISHED;
    return now < start ? Stage.PENDING : Stage.OPEN;
  }

  static boolean permits(Stage stage, int level, int lower, int upper, int temp, boolean hot) {
    return stage == Stage.OPEN && level > lower && level < upper
        && temp != Integer.MIN_VALUE && !hot;
  }
}
