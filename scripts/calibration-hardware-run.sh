#!/usr/bin/env bash
#
# Section 7 of specs/004-head-tracking-calibration/quickstart.md, as one command.
#
# This is the run that closes T060, and with it T061 and T062. It needs what nothing else in
# this repository needs: AirPods Pro 3 in the ears, an open AAP channel, and a person willing
# to hold four poses. Everything the wizard can be asked without those has already been walked
# and is recorded in that quickstart — this script exists so the part that cannot be automated
# is the *only* part left to do.
#
#   ./scripts/calibration-hardware-run.sh                 # guided run, prompts per pose
#   ./scripts/calibration-hardware-run.sh --out run.txt   # and keep the transcript
#
# What comes out is a transcript, a `cal export` JSON, and a labelled fixture. Paste the export
# and the response matrix into docs/protocol-research.md — the section "What the calibration
# wizard has and has not measured" is written to receive them.
#
# If the run reports CROSS_COUPLED on all three axes, **that is the result.** It is the outcome
# the evidence in docs/protocol-research.md predicts, it is worth more than three scales, and it
# must be written down rather than tuned away. Do not raise the tolerance until numbers appear.

set -uo pipefail

PKG=io.github.andrewkomkov.greenpods.debug
CMP=$PKG/io.github.andrewkomkov.greenpods.debug.GreenPodsDebugReceiver
OUT=""

while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

gp() {
  adb shell am broadcast --receiver-foreground -n "$CMP" \
     -a io.github.andrewkomkov.greenpods.DEBUG "$@" >/dev/null 2>&1
}

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }

# Prints only what the app said since the previous call, so each step's output is its own.
SEEN=0
drain() {
  local all total
  all=$(adb logcat -d -s GreenPodsDebug 2>/dev/null | sed -n 's/^.*GreenPodsDebug *: //p')
  total=$(printf '%s\n' "$all" | grep -c '')
  printf '%s\n' "$all" | tail -n +$((SEEN + 1))
  SEEN=$total
}

step() {
  printf '$ gp %s\n' "$*"
  gp "$@"
  sleep 2
  drain
}

hold() {
  local pose="$1" instruction="$2"
  say "$pose — $instruction"
  read -r -p "Get into the pose, then press Enter to start the countdown. " _
  step --es cmd cal --es value advance
  say "Hold it..."
  sleep 3
  step --es cmd cal --es value status
  read -r -p "Enter to continue, or type 'r' to repeat this pose: " again
  if [ "$again" = "r" ]; then
    step --es cmd cal --es value repeat
    hold "$pose" "$instruction"
  fi
}

main() {
  if [ -z "$(adb devices | sed -n '2p')" ]; then
    echo "No device. Connect the phone with the accessory paired and connected." >&2
    exit 1
  fi

  # A sleeping screen breaks a long run and looks like a stalled wizard.
  adb shell svc power stayon usb >/dev/null 2>&1
  adb logcat -c >/dev/null 2>&1

  say "1. Is the transport actually open? Blame this before blaming the wizard."
  step --es cmd probe --ez force true
  step --es cmd hid

  say "2. What is stored now, so the run can be compared against it."
  step --es cmd cal --es value show

  say "3. The run. Neutral first — every scale is a difference against it."
  step --es cmd cal --es value start

  hold "NEUTRAL" "Look straight ahead and keep still."
  hold "YAW"     "Turn your head about 90 degrees, chin toward your shoulder."
  hold "PITCH"   "Tip your chin down about 45 degrees."
  hold "ROLL"    "Tilt one ear toward that shoulder, about 45 degrees, without turning."

  say "4. The verdicts. A refusal here is a result, not a failure."
  step --es cmd cal --es value status

  say "5. Store it, then read it back."
  step --es cmd cal --es value finish
  step --es cmd cal --es value show

  say "6. The export — this is what goes into docs/protocol-research.md."
  step --es cmd cal --es value export

  say "7. The fixture. Labelled CAPTURED when it came off a live stream."
  step --es cmd cal --es value fixture

  say "8. FR-024: do the gesture thresholds still fire? This is T061."
  echo "Turn head gestures on, then nod and shake with the calibration stored."
  echo "A nod that no longer registers is a finding to record, not a bug in the wizard."
  step --es cmd set --es key headGestures --es value on
  read -r -p "Try a nod and a shake, then press Enter. " _
  step --es cmd dump

  say "Done. Next: paste the export and the response matrix into docs/protocol-research.md,"
  say "and tick T060 to T062 in specs/004-head-tracking-calibration/tasks.md."
}

if [ -n "$OUT" ]; then
  main 2>&1 | tee "$OUT"
  say "Transcript written to $OUT"
else
  main
fi
