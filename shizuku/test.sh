#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/tests
javac -encoding UTF-8 --release 8 -d build/tests \
  app/src/main/java/io/local/rootward/shizuku/{ChargePhase,ChargeBehaviour,HardwareCruise}.java \
  tests/{ChargePhaseTest,ChargeBehaviourTest,HardwareCruiseTest}.java
java -cp build/tests io.local.rootward.shizuku.ChargePhaseTest
java -cp build/tests io.local.rootward.shizuku.ChargeBehaviourTest
java -cp build/tests io.local.rootward.shizuku.HardwareCruiseTest
