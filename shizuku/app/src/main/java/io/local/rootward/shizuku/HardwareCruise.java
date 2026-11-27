package io.local.rootward.shizuku;

/** Selects a fixed driver operation for one observation. Cable state is deliberately absent. */
final class HardwareCruise {
  static final int NONE = -1;

  enum Hold {
    NONE,
    OWNED,
    UNCERTAIN;

    Hold reconcile(int observed, boolean sameBoot) {
      if (observed == ChargeBehaviour.AUTO || observed == ChargeBehaviour.EXTERNAL) return NONE;
      if (this == OWNED && !sameBoot) return UNCERTAIN;
      return this;
    }
  }

  static boolean hot(boolean previous, int temperature, int limit) {
    if (temperature == Integer.MIN_VALUE) return previous;
    if (temperature >= limit * 10) return true;
    if (temperature <= (limit - 2) * 10) return false;
    return previous;
  }

  static int operation(
      boolean enabled,
      int observed,
      Hold hold,
      int phase,
      int level,
      int temperature,
      boolean hot) {
    if (hold == Hold.UNCERTAIN
        || (observed != ChargeBehaviour.AUTO && observed != ChargeBehaviour.INHIBITED)) return NONE;
    if (!enabled)
      return hold == Hold.OWNED && observed == ChargeBehaviour.INHIBITED
          ? ChargeBehaviour.RESUME
          : NONE;
    if (hot || phase == ChargePhase.WAIT)
      return observed == ChargeBehaviour.AUTO ? ChargeBehaviour.ACQUIRE : NONE;
    if (phase != ChargePhase.FILL && phase != ChargePhase.MANUAL) return NONE;
    if (level < 0 || level > 100 || temperature == Integer.MIN_VALUE) return NONE;
    return hold == Hold.OWNED && observed == ChargeBehaviour.INHIBITED
        ? ChargeBehaviour.RESUME
        : NONE;
  }
}
