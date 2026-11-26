#!/system/bin/sh
# Rootward plug-in for ACC v2023.10.16 (Android mksh).
# ACC sources plug-ins before assigning isAccd and versionCode. Only its daemon
# defines these two capacity predicates and already owns acc.lock at this point.
command -v _ge_pause_cap >/dev/null 2>&1 && command -v _le_resume_cap >/dev/null 2>&1 || return 0
grep -qx 'versionCode=202310160' "$execDir/module.prop" || return 0
IFS= read -r rootward_owner < "$TMPDIR/acc.lock" && [ "$rootward_owner" = "$$" ] || return 0
command -v rootward_original_pause >/dev/null 2>&1 && return 0

# Copy the definitions from this daemon, retaining ACC's exact voltage,
# thermal override and device-specific behaviour outside a percentage hold.
rootward_copy() {
  local body
  body=$(typeset -f "$1") || return 1
  case "$body" in
    "$1"*) eval "$2${body#"$1"}" ;;
    *) return 1 ;;
  esac
}
rootward_copy _ge_pause_cap rootward_original_pause &&
  rootward_copy _le_resume_cap rootward_original_resume &&
  rootward_copy src_cfg rootward_original_config || return 0

rootward_dir="$dataDir/rootward-hold"
rootward_loaded=false
rootward_strict=false
rootward_waiting=false

rootward_percent() {
  case "$1" in 0|[1-9]|[1-9][0-9]|100) return 0 ;; *) return 1 ;; esac
}

rootward_refresh() {
  rootward_strict=false
  rootward_waiting=false
  [ "${isAccd:-false}" = true ] && [ -d "$rootward_dir" ] && [ ! -L "$rootward_dir" ] || return 0
  # misc_stuff can select a custom profile after plug-ins were sourced.
  if [ "$config" != "$dataDir/config.txt" ]; then
    return 0
  fi

  if ! $rootward_loaded; then
    [ "$(stat -c '%u:%g:%a' "$rootward_dir")" = '0:0:700' ] || return 0
    # Publish inside the root-owned directory rather than ACC's shared tmpfs.
    (umask 077; printf '%s\n' "$$" > "$rootward_dir/loaded-pid.new" &&
      mv -f "$rootward_dir/loaded-pid.new" "$rootward_dir/loaded-pid") || return 0
    rootward_loaded=true
  fi

  [ -f "$rootward_dir/enabled" ] || return 0
  local lower=${capacity[2]-} upper=${capacity[3]-} level
  if ! rootward_percent "$lower" || ! rootward_percent "$upper" || [ "$lower" -ge "$upper" ]; then
    # An external switch to voltage mode invalidates the old percentage phase.
    [ ! -f "$rootward_dir/holding" ] || rm -f "$rootward_dir/holding"
    return 0
  fi

  rootward_strict=true
  offMid=false   # ACC otherwise infers a stop from a mid-band daemon restart.
  [ ! -f "$rootward_dir/holding" ] || rootward_waiting=true
  IFS= read -r level 2>/dev/null < "$battCapacity" && rootward_percent "$level" || return 0
  if [ "$level" -ge "$upper" ] && ! $rootward_waiting; then
    rootward_waiting=true
    (umask 077; : > "$rootward_dir/holding") || :
  elif [ "$level" -le "$lower" ] && $rootward_waiting; then
    if rm -f "$rootward_dir/holding"; then rootward_waiting=false; fi
  fi
}

src_cfg() {
  rootward_original_config || return $?
  # is_charging calls set_dp -> src_cfg on every poll, including unplugged
  # samples and when ACC's temperature check short-circuits the upper predicate.
  rootward_refresh
}

_ge_pause_cap() {
  if $rootward_strict && $rootward_waiting; then return 0; fi
  rootward_original_pause || return 1
  # A new upper-threshold sample can arrive between config refresh and this
  # predicate. Record the very decision that tells ACC to cut charging.
  if $rootward_strict && [ -f "$rootward_dir/enabled" ]; then
    rootward_waiting=true
    (umask 077; : > "$rootward_dir/holding") || :
  fi
  return 0
}

_le_resume_cap() {
  if $rootward_strict && $rootward_waiting; then return 1; fi
  rootward_original_resume
}
