#!/system/bin/sh
# Run on Android with toybox under an unprivileged UID. Arguments are the
# generated command directory and the released 1.3 plug-in. No real ACC calls.
set -eu
commands=$1
old=$2
fixture=$(mktemp -d "${TMPDIR:-/data/local/tmp}/rootward-migrate.XXXXXX") || exit 1
trap 'rm -rf "$fixture"' EXIT
mkdir -p "$fixture/data/rootward-hold" "$fixture/data/plugins" "$fixture/hardware/battery" "$fixture/runtime"
dir="$fixture/data/rootward-hold"; plugin="$fixture/data/plugins/rootward-hold.sh"
chmod 700 "$dir" "$fixture/data" "$fixture/data/plugins"
printf 'io.local.accpanel/cable-hold/v1\n' > "$dir/owner"
printf '60\n' > "$fixture/hardware/battery/capacity"
printf 'battCapacity=battery/capacity\n' > "$fixture/runtime/.batt-interface.sh"
printf '/data/adb/vr25/acc/accd.sh\000' > "$fixture/cmdline"
printf '%s\n' "$$" > "$fixture/runtime/acc.lock"
old_stamp=${3:-loaded-pid}
echo "$$" > "$dir/$old_stamp"; chmod 600 "$dir/$old_stamp"
touch "$dir/holding" "$dir/enabled"
cp "$old" "$plugin"; chmod 600 "$plugin"
# The real package uses root-owned resources; emulate ownership in this fixture.
stat() {
  case "$2" in
    %u) echo 0;;
    %u:%a) printf '0:%s\n' "$(command stat -c %a "$3")";;
    %u:%g:%a) printf '0:0:%s\n' "$(command stat -c %a "$3")";;
    *) command stat "$@";;
  esac
}
acc=test_acc
test_acc() {
  case "$1 ${2-}" in
    '-v ') echo 'v2023.10.16 (202310160)';;
    '-D ') return 0;;
    '-sp '*) printf 'resume_capacity=50\npause_capacity=75\nresume_temp=45\n';;
    '-D restart')
      echo restart >> "$fixture/restarts"
      if cmp -s "$plugin" "$old"; then return 0; fi
      [ ! -f "$fixture/fail-upgrade" ] || return 1
      echo "$$" > "$dir/loaded-v3-pid"; chmod 600 "$dir/loaded-v3-pid";;
    *) return 2;;
  esac
}
daemon_code=0
# No writes or migration on plain reads, and reject a tampered plugin.
(. "$commands/snapshot") | grep -qx '@hold_loaded=true'
echo unknown >> "$plugin"
if (. "$commands/resume" >/dev/null); then exit 1; fi
grep -qx unknown "$plugin"
cp "$old" "$plugin"
touch "$fixture/fail-upgrade"
if (. "$commands/resume" >/dev/null); then exit 1; fi
cmp "$old" "$plugin"
[ -f "$dir/holding" ]
[ ! -e "$dir/resume-once" ]
rm "$fixture/fail-upgrade"
(. "$commands/resume")
[ "$(cat "$dir/resume-once")" = 75 ]
[ -f "$dir/holding" ]
if cmp -s "$old" "$plugin"; then exit 1; fi
(. "$commands/snapshot") | grep -qx '@manual_resume=true'
(. "$commands/cancel")
[ ! -e "$dir/resume-once" ]
[ -f "$dir/holding" ]
(. "$commands/schedule")
(. "$commands/snapshot") | grep -Eq '^@scheduled_at=[0-9]{10}$'
if (. "$commands/schedule" >/dev/null); then exit 1; fi
(. "$commands/cancelSchedule")
(. "$commands/snapshot") | grep -qx '@scheduled_at=0'
rm "$dir/enabled"
if (. "$commands/resume" >/dev/null); then exit 1; fi
[ ! -e "$dir/enabled" ]
echo 'PASS: verified legacy migration, rollback, ownership checks and cancellation'
