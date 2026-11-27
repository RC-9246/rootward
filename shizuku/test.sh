#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/tests
javac -encoding UTF-8 --release 8 -d build/tests \
  app/src/main/java/io/local/rootward/shizuku/{ChargePhase,ChargeBehaviour,SystemBattery,NativeLimits,UsageWindow,HardwareCruise}.java \
  tests/{ChargePhaseTest,ChargeBehaviourTest,NativeLimitsTest,UsageWindowTest,HardwareCruiseTest}.java
java -cp build/tests io.local.rootward.shizuku.ChargePhaseTest
java -cp build/tests io.local.rootward.shizuku.ChargeBehaviourTest
java -cp build/tests io.local.rootward.shizuku.HardwareCruiseTest
java -cp build/tests io.local.rootward.shizuku.NativeLimitsTest

java -cp build/tests io.local.rootward.shizuku.UsageWindowTest

javac -encoding UTF-8 --release 8 -cp build/tests -d build/tests \
  app/src/main/java/io/local/rootward/shizuku/{ThresholdPace,BatteryAnalysis}.java tests/BatteryAnalysisTest.java
java -cp build/tests io.local.rootward.shizuku.BatteryAnalysisTest

javac -encoding UTF-8 --release 8 -cp build/tests -d build/tests \
  app/src/main/java/io/local/rootward/shizuku/BoostWindow.java tests/BoostWindowTest.java
java -cp build/tests io.local.rootward.shizuku.BoostWindowTest
javac -encoding UTF-8 --release 8 -d build/tests \
  app/src/main/java/io/local/rootward/shizuku/PowerCareReport.java tests/PowerCareReportTest.java
java -cp build/tests io.local.rootward.shizuku.PowerCareReportTest

javac -encoding UTF-8 --release 8 -d build/tests \
  app/src/main/java/io/local/rootward/shizuku/OutletPolicy.java tests/OutletPolicyTest.java
java -cp build/tests io.local.rootward.shizuku.OutletPolicyTest

javac -encoding UTF-8 --release 8 -d build/tests \
  app/src/main/java/io/local/rootward/shizuku/AppBatteryUsage.java tests/AppBatteryUsageTest.java
java -cp build/tests io.local.rootward.shizuku.AppBatteryUsageTest
