#!/usr/bin/env bash
# Host-only integration test. The fake su runs as the current account.
set -eu
project=$(cd "$(dirname "$0")/.." && pwd)
export ROOTWARD_FIXTURE=$(mktemp -d)
trap 'rm -rf "$ROOTWARD_FIXTURE"' EXIT
cd "$ROOTWARD_FIXTURE"
mkdir -p bin runtime supplies/gauge unexpected classes
printf '99\n' > unexpected/capacity
printf '61\n' > supplies/gauge/capacity
printf '376\n' > supplies/gauge/temp
printf 'Not charging\n' > supplies/gauge/status
echo 9 > daemon
cat > bin/acc <<'ACC'
#!/bin/sh
case "$*" in
    "-v") echo "v2023.10.16 (202310160)" ;;
    "-sp "*) printf 'resume_capacity=50\npause_capacity=75\nshutdown_capacity=1\nresume_temp=40\nmax_temp=45\n' ;;
    "-s "*) touch "$ROOTWARD_FIXTURE/settings-written" ;;
    "-D") exit "$(cat "$ROOTWARD_FIXTURE/daemon")" ;;
    "-D start") echo 0 > "$ROOTWARD_FIXTURE/daemon"; echo 1 >> "$ROOTWARD_FIXTURE/started" ;;
    *) exit 1 ;;
esac
ACC
cat > bin/su <<'SU'
#!/bin/sh
[ "$1" = -c ] || exit 1
printf '%s\n' "$2" | sed \
    -e 's/$(id -u)/0/g' \
    -e 's|^for acc in .*; do |for acc in "$ROOTWARD_FIXTURE/bin/acc"; do |' \
    -e 's|/sys/class/power_supply|"$ROOTWARD_FIXTURE/supplies"|g' \
    -e 's|/dev/.vr25/acc/|"$ROOTWARD_FIXTURE/runtime/"|g' \
    -e 's|/data/adb/vr25/acc-data|"$ROOTWARD_FIXTURE/data"|g' | /bin/sh
SU
chmod +x bin/acc bin/su
export PATH="$ROOTWARD_FIXTURE/bin:$PATH"
src="$project/app/src/main/java/io/local/accpanel"
javac -d classes "$src/StrictHold.java" "$src/AccBackend.java" "$project/tests/AccReadbackTest.java"
java -cp classes io.local.accpanel.AccReadbackTest
