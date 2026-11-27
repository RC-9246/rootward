#!/usr/bin/env bash
# Run with bash on a host or with Android mksh in an isolated emulator.
# Optional third argument: a checkout of ACC v2023.10.16's install/ directory.
set -eu
plugin=${1:-"$(dirname "$0")/../app/src/main/res/raw/rootward_hold.sh"}
scratch=$(mktemp -d "${2:-${TMPDIR:-/tmp}}/rootward-regression.XXXXXX")
trap 'rm -rf "$scratch"' EXIT
execDir="$scratch/module"
dataDir="$scratch/data"
TMPDIR="$scratch/runtime"
config="$dataDir/config.txt"
battCapacity="$scratch/capacity"
mkdir -p "$execDir" "$dataDir/rootward-hold" "$TMPDIR"
chmod 700 "$dataDir/rootward-hold"
echo versionCode=202310160 > "$execDir/module.prop"
echo "$$" > "$TMPDIR/acc.lock"
: > "$dataDir/rootward-hold/enabled"
printf 'capacity=(5 50 50 75 false false)\ntemperature=(35 50 45 55)\noffMid=true\n' > "$config"
echo 60 > "$battCapacity"
die() { echo "FAILED: $*" >&2; exit 1; }
yes_() { "$@" || die "expected success: $*"; }
no_() { if "$@"; then die "unexpected success: $*"; fi; }

# No root is necessary for the fixture. Emulate the private dir's Android owner.
stat() {
  [ "$1" = -c ] && [ "$2" = '%u:%g:%a' ] && [ "$3" = "$dataDir/rootward-hold" ] ||
    die "unexpected stat of $*"
  echo 0:0:700
}
t() { test "$@"; }
tt() { case "$1" in *r) return 0;; esac; return 1; }
volt_now() { echo 3990; }
src_cfg() { . "$config"; }
below_abs_lims() { src_cfg; return 0; }
_ge_pause_cap() {
  if [ "${capacity[3]}" -gt 3000 ]; then [ "$(volt_now)" -ge "${capacity[3]}" ]
  else [ "$(cat "$battCapacity")" -ge "${capacity[3]}" ]; fi
}
_lt_pause_cap() {
  if [ "${capacity[3]}" -gt 3000 ]; then [ "$(volt_now)" -lt "${capacity[3]}" ]
  else [ "$(cat "$battCapacity")" -lt "${capacity[3]}" ]; fi
}
_le_resume_cap() {
  if tt "${temperature[2]}" '*r' && _lt_pause_cap; then return 0
  elif [ "${capacity[2]}" -gt 3000 ]; then [ "$(volt_now)" -le "${capacity[2]}" ]
  else [ "$(cat "$battCapacity")" -le "${capacity[2]}" ]; fi
}
if [ -n "${3:-}" ]; then
  # Exercise unchanged upstream predicates rather than fixture substitutes.
  src="$3/accd.sh"
  [ -s "$src" ] || die "missing $src"
  for name in _ge_pause_cap _lt_pause_cap _le_resume_cap; do
    fn=$(sed -n "/^  ${name}() {/,/^  }/p" "$src")
    [ -n "$fn" ] || die "no $name in $src"
    eval "$fn"
  done
fi
hold="$dataDir/rootward-hold/holding"
loaded="$dataDir/rootward-hold/loaded-v3-pid"
# Guard against ACC CLI, an unrelated process and a different module revision.
(unset -f _ge_pause_cap; . "$plugin"; no_ command -v rootward_original_pause)
echo 111111 > "$TMPDIR/acc.lock"
(. "$plugin"; no_ command -v rootward_original_pause)
echo "$$" > "$TMPDIR/acc.lock"
echo versionCode=111111 > "$execDir/module.prop"
(. "$plugin"; no_ command -v rootward_original_pause)
echo versionCode=202310160 > "$execDir/module.prop"

isAccd=false
. "$plugin"
yes_ command -v rootward_original_pause
no_ test -e "$loaded"
isAccd=true
read_soc() { echo "$1" > "$battCapacity"; src_cfg; }
restart_at_midpoint() {
  local shell
  if [ -n "${KSH_VERSION:-}" ]; then shell=$(command -v mksh || command -v sh)
  else shell=$(command -v bash); fi
  # New shell, same persistent files, no inherited plug-in functions/globals.
  "$shell" -c '

set -eu
execDir="$2/module"; dataDir="$2/data"; TMPDIR="$2/runtime"
config="$dataDir/config.txt"; battCapacity="$2/capacity"
temp="$2/temp"
if [ -n "${4:-}" ]; then clock_after_restart=$4; date() { echo "$clock_after_restart"; }; fi
echo "$$" > "$TMPDIR/acc.lock"
stat() { echo 0:0:700; }
src_cfg() { . "$config"; }
_ge_pause_cap() { [ "$(cat "$battCapacity")" -ge "${capacity[3]}" ]; }
_le_resume_cap() { [ "$(cat "$battCapacity")" -le "${capacity[2]}" ]; }
. "$1"
isAccd=true
src_cfg
[ "$(cat "$dataDir/rootward-hold/loaded-v3-pid")" = "$$" ]
if _ge_pause_cap; then [ "$3" = holding ]; else [ "$3" = open ]; fi
[ "$offMid" = false ]
' restart "$plugin" "$scratch" "$1" "${2:-}"
  echo "$$" > "$TMPDIR/acc.lock"
}
read_soc 60
yes_ test "$(cat "$loaded")" = "$$"
no_ test -e "$hold"
yes_ test "$offMid" = false
# Upward 60 -> disconnect -> reconnect at 60, original charging proceeds.
no_ _ge_pause_cap
no_ _le_resume_cap
no_ _ge_pause_cap
read_soc 75
yes_ _ge_pause_cap
yes_ test -f "$hold"
# A driver may report Charging after each reconnect, or be absent temporarily.
# ACC's charging branch tests pause and its noncharging branch tests resume.
for x in 74 70 60 60 51; do
  read_soc "$x"
  no_ _le_resume_cap
  yes_ _ge_pause_cap
  yes_ test -f "$hold"
done
restart_at_midpoint holding
# A one-shot opens only the capacity gate. It is never an instruction to turn
# on a switch, and it cannot erase the persisted strict hold while unplugged.
once="$dataDir/rootward-hold/resume-once"
printf '75\n' > "$once"
read_soc 61
no_ _ge_pause_cap
yes_ _le_resume_cap
yes_ test -f "$hold"
restart_at_midpoint open
for x in 54 49 55 74; do
  read_soc "$x"
  yes_ _le_resume_cap
  yes_ test -f "$once"
done
yes_ below_abs_lims
rm "$once"  # cancellation between polls.
no_ below_abs_lims
yes_ _ge_pause_cap
no_ _le_resume_cap
printf '75\n' > "$once"
echo '?' > "$battCapacity"
yes_ _ge_pause_cap
no_ _le_resume_cap
echo 75 > "$battCapacity"  # crossed between config refresh and decision
no_ _le_resume_cap
no_ test -e "$once"
yes_ _ge_pause_cap
read_soc 68
no_ _le_resume_cap
printf '75\n' > "$once"
echo 'capacity=(5 50 50 80 false false)' >> "$config"
read_soc 68
no_ test -e "$once"
yes_ _ge_pause_cap
echo 'capacity=(5 50 50 75 false false)' >> "$config"
read_soc 61

# Scheduled windows use wall time across daemon restarts, stay bounded when the
# cable/temperature prevents charging, and are invalidated by threshold changes.
(
  clock=1900000000
  date() { [ "$1" = +%s ] && echo "$clock" || command date "$@"; }
  plan="$rootward_dir/plan-1900000010-50-75.fixture"
  temp="$scratch/temp"; echo 310 > "$temp"; touch "$plan"
  no_ _le_resume_cap
  clock=1900000010; yes_ _le_resume_cap; no_ _ge_pause_cap
  restart_at_midpoint open "$clock"
  read_soc 61; yes_ _le_resume_cap  # replug
  echo 500 > "$temp"; no_ _le_resume_cap
  echo unknown > "$temp"; no_ _le_resume_cap
  (temp="$TMPDIR/.dummy-temp"; echo 250 > "$temp"; no_ _le_resume_cap)
  echo 360 > "$temp"; yes_ _le_resume_cap
  clock=1900003610; no_ _le_resume_cap; no_ test -e "$plan"; yes_ _ge_pause_cap
  touch "$plan"; clock=1900000010; read_soc 75; no_ test -e "$plan"
  read_soc 61; touch "$plan"; read_soc 50; no_ test -e "$plan"; yes_ _le_resume_cap
  read_soc 75; read_soc 61; touch "$plan"; capacity[3]=76; no_ _le_resume_cap; no_ test -e "$plan"
)
read_soc 61

if [ -n "${3:-}" ]; then
  (
    # One iteration of the bundled upstream dispatcher, using the unmodified
    # accepted-charge and force-off branches. Stub hardware, not predicates.
    src="$3/accd.sh"
    for name in ctrl_charging force_off temp_ok _gt_resume_cap; do
      fn=$(sed -n "/^  ${name}() {/,/^  }/p" "$src")
      [ -n "$fn" ] || die "no $name in $src"
      case "$name" in ctrl_charging) fn=$(printf '%s\n' "$fn" | sed 's/^    while :; do$/    for single_poll in 1; do/') ;; esac
      eval "$fn"
    done
    temp="$scratch/temp"; loopDelay=(1 1); cooldown=false; capacitySync=false
    forceOff=true; allowIdleAbovePcap=true; chgStatusCode=1; resetBattStats=(false false false)
    cooldownCurrent=; cooldownRatio=(); cooldownCustom=(); count=0
    _uptime() { return 1; }
    is_charging() { src_cfg; test -f "$scratch/is-charging"; }
    sleep() { command sleep 0.12; }
    flip_sw() { test "$1" = off; echo off >> "$scratch/flips"; }
    enable_charging() {
      # The stock dispatcher has already reaped the force-off trigger.
      for f in "$TMPDIR"/.forceoff*; do [ ! -f "$f" ] || die "stale force-off"; done
      touch "$scratch/is-charging"
    }
    disable_charging() { rm -f "$scratch/is-charging"; }
    # Start the real force_off worker so user feedback is verified with a
    # previously active worker (the common strictly-held configuration).
    printf '75\n' > "$once"; echo 449 > "$temp"
    force_off 2>/dev/null
    ctrl_charging 2>/dev/null
    set +x
    yes_ test -f "$scratch/is-charging"
    # Heat pauses in the charging branch and the native resume threshold
    # remains effective until temperature returns to <=45 °C.
    echo 500 > "$temp"; ctrl_charging 2>/dev/null; set +x
    no_ test -f "$scratch/is-charging"
    echo 470 > "$temp"; ctrl_charging 2>/dev/null; set +x
    no_ test -f "$scratch/is-charging"
    echo 450 > "$temp"; ctrl_charging 2>/dev/null; set +x
    yes_ test -f "$scratch/is-charging"
    echo 75 > "$battCapacity"; ctrl_charging 2>/dev/null; set +x
    no_ test -f "$scratch/is-charging"
    no_ test -e "$once"
    yes_ test -f "$hold"
    rm -f "$TMPDIR"/.forceoff*
    wait
  )
  read_soc 61
fi
# Temperature's native 'r' override must not clear the capacity hold.
echo 'temperature=(35 50 45r 55)' >> "$config"
read_soc 61
no_ _le_resume_cap
# An unreadable or nonsensical sensor cannot open a held charging path.
for x in -1 255 '?'; do
  read_soc "$x"
  yes_ _ge_pause_cap
  no_ _le_resume_cap
done
rm "$battCapacity"
src_cfg
yes_ _ge_pause_cap
no_ _le_resume_cap
# Restart in the middle of a held phase uses the persisted marker.
(rootward_waiting=false; read_soc 59; yes_ _ge_pause_cap)
# Touching 50 offline releases; firmware and normal ACC thermal logic still
# decide whether charging is permissible. Rising past 50 doesn't re-latch.
read_soc 50
no_ test -e "$hold"
yes_ _le_resume_cap
read_soc 54
no_ _ge_pause_cap
yes_ _le_resume_cap  # Original 'r' behaviour is preserved outside HOLD.
read_soc 75
yes_ test -f "$hold"
# Changing the lower bound takes effect on ACC's next config poll.
echo 'capacity=(5 50 65 80 false false)' >> "$config"
read_soc 64
no_ test -e "$hold"
# An upper threshold crossed between polling config and reading the pause
# predicate must latch the same decision which made ACC call disable_charging.
echo 80 > "$battCapacity"
yes_ _ge_pause_cap
yes_ test -f "$hold"
# Millivolt profiles are delegated to ACC. No stale percentage latch returns.
echo 'capacity=(5 50 3800 4100 false false)' >> "$config"
src_cfg
no_ test -e "$hold"
yes_ test "$offMid" = true
no_ _ge_pause_cap
yes_ _le_resume_cap  # temp 'r' and volt_now < threshold
# External profiles must keep their own thresholds and startup behaviour.
original_config="$config"
config="$scratch/custom.txt"
printf 'capacity=(5 50 50 75 false false)\ntemperature=(35 50 45 55)\noffMid=true\n' > "$config"
read_soc 81
yes_ _ge_pause_cap
no_ test -e "$hold"
yes_ test "$offMid" = true
config="$original_config"
# Removing the opt-in leaves stock ACC in control, even while a marker exists.
rm "$dataDir/rootward-hold/enabled"
touch "$hold"
echo 'capacity=(5 50 50 75 false false)' >> "$config"
read_soc 65
no_ _ge_pause_cap
yes_ _le_resume_cap
yes_ test "$offMid" = true
# Disabled -> re-enabled in the middle starts open after the installer clears
# the old marker. A new process should preserve this upward phase as well.
rm "$hold"
touch "$dataDir/rootward-hold/enabled"
restart_at_midpoint open
echo 'PASS: ACC strict hold phase and compatibility regression'
