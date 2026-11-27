package io.local.rootward.shizuku;

final class OutletPolicy {
  enum Step {
    NONE,
    OFF,
    ON,
    UNCERTAIN,
    CLEAR
  }

  static final String PENDING = "?";

  static Step decide(String request, String mode, String changed, String ownership) {
    if (!(mode.equals("on") || mode.equals("off")) || changed.isEmpty()) return Step.NONE;
    if (mode.equals("on")) {
      if (!ownership.isEmpty()) return Step.CLEAR;
      return request.equals("pause") ? Step.OFF : Step.NONE;
    }
    if (ownership.isEmpty()) return Step.NONE;
    if (ownership.equals(PENDING)) return Step.UNCERTAIN;
    if (!ownership.equals(changed)) return Step.CLEAR;
    return request.equals("allow") || request.equals("stop") ? Step.ON : Step.NONE;
  }
}
