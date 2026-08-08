#!/usr/bin/env bash
#
# Every hardware-blocked task left in specs/, as one command.
#
# Five tasks across three features need what nothing else in this repository needs: AirPods
# Pro 3 in the ears, an open AAP channel, and a person willing to hold four poses.
#
#   004 T060, T061, T062  calibration on a real head, the gesture thresholds after it, the write-up
#   003 T070a             heart rate quickstart sections 4 to 7, never walked
#   005 T038              the live-surface walk, whose record does not exist
#
# Everything those features can be asked without hardware has been walked and recorded. This
# script exists so the part that cannot be automated is the *only* part left to do.
#
#   ./scripts/hardware-walk.sh                 # guided run, prompts per pose
#   ./scripts/hardware-walk.sh --out run.txt   # and keep the transcript
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

  say "Done with 004. Paste the export and the response matrix into docs/protocol-research.md,"
  say "and tick T060 to T062 in specs/004-head-tracking-calibration/tasks.md."

  hr_sections_4_to_7
  live_activity_walk

  say "Every hardware-blocked task in specs/ has now been driven once:"
  say "  004 T060, T061, T062   — the calibration run above"
  say "  003 T070a              — heart rate quickstart sections 4 to 7"
  say "  005 T038               — the live-surface walk"
  say "Tick each only where its output actually says what its task claims."
}

# 003 T070a: quickstart sections 4 to 7. Never walked — the hardware went away in 2026-08-04's
# session after the defects found in section 3 had consumed it.
hr_sections_4_to_7() {
  say "003 §4 — heart rate stops when it should"
  step --es cmd hr --es value on
  read -r -p "Take one bud out, then press Enter. " _
  step --es cmd hr --es value status      # expect UNAVAILABLE, lastStop=notWorn
  read -r -p "Put it back in, then press Enter. " _
  sleep 20
  step --es cmd hr --es value status      # expect STARTING, then SETTLING, then MEASURING

  say "003 §5 — the health store"
  step --es cmd health --es value status
  step --es cmd set --es key hrHealthConnect --es value on
  step --es cmd hr --es value on
  step --es cmd hr --es value status
  step --es cmd health --es value count --el minutes 10

  say "003 §6 — nothing leaks. No command may print a heart rate."
  step --es cmd dump
  echo "Check by eye: the dump above carries heartRate state and counters, and no BPM."

  say "003 §7 — locked, not hidden"
  step --es cmd probe
  step --es cmd hr --es value status
  step --es cmd inject --es model 0x1420 --es address DE:B0:60:00:00:01
  step --es cmd hr --es value status      # expect UNSUPPORTED on a model with no sensor
}

# 005 T038: the walk itself may well have happened; the record it asks for does not exist.
# Running it again is cheaper than arguing about what was done in August.
live_activity_walk() {
  say "005 — the live surface, walked for the record"
  step --es cmd live
  step --es cmd monitor --es value on
  echo "Lock the screen and look at it."
  read -r -p "Press Enter when you have. " _
  step --es cmd live --es action cycle
  step --es cmd live --es action stop
  step --es cmd live --es action dismiss
  step --es cmd monitor --es value off
  say "Write which of these steps you actually ran into 005's quickstart, the way"
  say "004's \"What was actually run\" section does. That record is T038's deliverable."
}

if [ -n "$OUT" ]; then
  main 2>&1 | tee "$OUT"
  say "Transcript written to $OUT"
else
  main
fi
