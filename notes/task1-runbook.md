# Task 1 runbook — the commands, with branches (fish)

Everything is fish. On-device shells are unaffected — `adb shell` runs the car's own `sh`.

Output goes to `~/software/Nemesis/dump/sambok/` so it can be pasted back. Ten minutes without the
lock check, fifteen with it.

## Step 0 — setup (30 s)

```fish
set A ~/Android/Sdk/platform-tools/adb
mkdir -p ~/software/Nemesis/dump/sambok
cd ~/software/Nemesis/dump/sambok
$A devices
$A logcat -c
$A logcat > session.log &
set LOGPID $last_pid
```

**If `adb devices` shows nothing:** ADB is off, or the cable is charge-only. Stop here.

**Reading `service call` output:** `Result: Parcel(00000000 00000015 '........')` — the first word
`0` means no exception; the second is the value, in hex. A non-zero first word is a failure.

## Step 1 — does the shortcut toggle Utility Mode? (1 min)

Utility Mode **off**, car in Park:

```fish
$A shell am broadcast -a com.mobis.intent.action.UTILITY_MODE_SHORTCUT
```

- **It turns on** → run it once more. If it turns off, the shortcut is our toggle; skip to Step 3.
- **A car menu or the EV settings screen opens** → shortcut only. Record what opened and say so:
  that is fallback three (no dog scene) and the design changes.
- **Nothing visible** → see whether anything received it:

  ```fish
  grep -iE "utility|shortcut" session.log | tail -30
  ```

  If a receiver logged, note its package — we can read that app from the dump. If the log is empty,
  the intent is not handled on this build.

## Step 2 — how is the state carried? (2 min)

Only if Step 1 gave no toggle. Turn Utility Mode on from the car's own menu while the capture runs:

```fish
grep -iE "utility|discharge|EV_SETTINGS" session.log | tail -40
```

Look for a broadcast action or a service call at the moment it changes; paste those lines.

**If it is a broadcast action:** note it — the app can register for it, but that may need Java
rather than `service call`, which changes the plan's shape.

## Step 3 — run the probes (2 min)

Note two numbers from the car's own screens first: the **set** temperature, and the **outside**
temperature.

```fish
fish ~/software/Sambok/notes/probe.fish | tee ~/software/Nemesis/dump/sambok/probe.txt
```

- **One DATC value tracks the cabin** (sits near the set temperature, moves when a window opens) →
  that is the getter. Note its unit: 21–30 is °C, 60–90 is °F, and whether the second word is tenths.
- **Everything matches set or ambient only** → cabin temperature is not exposed. Stop and report:
  Sambok's headline number has no source.
- **All zeros or exceptions** → those codes are wrong. Do not sweep neighbours on DATC; some write.
  Report and we will read the code instead.

## Step 4 — the countdown inputs (3 min)

Already collected by `probe.fish`. Compare `hev 46` against the car's consumption display, and the
`hev 3 i32 …` values against the battery percentage on the EV screen.

- **One id equals the SOC on screen** → note which.
- **None match** → keep the raw hex; we have the EV app in the dump and can decode it.
- **`46` looks implausible** (0, or huge) → keep the raw hex; the scale is worked out from the car's
  own consumption display.

Then the cutoff: set the V2X discharge limit to a distinctive **20 %** in the EV screen and note it.
There is no known getter — only the setter (57) — so if none turns up in the code, the app needs it
entered by hand or read from that screen once.

## Step 5 — does the display stay on while locked? (2 min)

Still in Utility Mode, lock with the physical key, wait two minutes without touching anything:

```fish
$A shell dumpsys power | grep -iE "mWakefulness|Display Power"
$A shell dumpsys display | grep -iE "mScreenState|state="
```

**If the screen blanked:** Sambok cannot work while locked and the project stops.

## Step 6 — clean up

```fish
kill $LOGPID
```

Leave Utility Mode off unless you want it running. Send back:

- `session.log` and `probe.txt`
- what Step 1 did, and the Step 2 lines if you had to run it
- the two climate numbers, and the cutoff you set
- the two `dumpsys` lines from Step 5
