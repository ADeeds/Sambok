# Sambok — car test plan

Ordered so the two things that can kill the design fail first. About 40 minutes at the car.

**Two risks up front:**

1. **Utility Mode has no `service call`.** No `TRANSACTION_*Utility*` exists in the framework.
   Only `com.mobis.intent.action.UTILITY_MODE_SHORTCUT`, `ACTION_SHORTCUT_UTILITY_MODE` and an
   `IsEvUtilityMode` string. So "Sambok activates Utility Mode" may only be possible via a
   broadcast that opens the car's own menu.
2. **No cabin-temperature getter.** `getInsideTemperature()` exists only in the nav engine's
   native code. The DATC transactions expose `getClimateInfo` (38), `getDriverOnlyValue` (39) and
   `getVRClimateStatus` (41, returns a float). The cabin reading may be inside one of those, or
   unavailable.

If either fails, Sambok changes shape, so do these before anything else.

## Phase 1 — read-only inventory (~8 min)

```sh
A=~/Android/Sdk/platform-tools/adb
mkdir -p ~/software/Nemesis/dump/sambok && cd ~/software/Nemesis/dump/sambok
$A devices
$A logcat -c
$A logcat > session.log &
```

```sh
$A shell service list | grep -iE "datc|usm|bcm|hev|vehicleoption|cluster"
```

Write down the exact service names. Then probe the DATC getters, in this order, recording each
raw reply:

```sh
$A shell service call <datc> 3     # temperature unit
$A shell service call <datc> 6     # isUnitCelsius
$A shell service call <datc> 38    # getClimateInfo
$A shell service call <datc> 39    # getDriverOnlyValue
$A shell service call <datc> 41    # getVRClimateStatus (float)
```

Compare each against what the climate screen shows: the set temperature, and the cabin
temperature if the car displays one.

**Decides:** whether a cabin temperature is readable at all, and in which unit/scale. If none of
these track the cabin, stop here and we redesign the temperature display.

Then the countdown inputs, from the EV service:

```sh
$A shell service call <hev> 46          # getPwrConsumption — units and scale unknown
$A shell service call <hev> 3 i32 94    # getHEVStatus(94) — try neighbouring ids too
$A shell service call <hev> 3 i32 44
$A shell service call <hev> 3 i32 115
```

For the cutoff, look for a getter for the V2X discharge limit (only the setter, 57, is known).
If there is none, note the value the car's own EV screen shows so the estimate can be checked
against it.

**Decides:** whether `(SOC − cutoff) × capacity ÷ draw` can be computed at all, and in what units.
Compare the draw reading against the car's own consumption display, and watch it change for a
minute to judge how much smoothing it needs.

## Phase 2 — Utility Mode (~10 min)

1. Note the car's current state and the exact menu path to Utility Mode.
2. Try the broadcast and watch what the screen does:

   ```sh
   $A shell am broadcast -a com.mobis.intent.action.UTILITY_MODE_SHORTCUT
   ```

3. If that does nothing, enter Utility Mode from the car's own menu, and watch what appears in the
   log:

   ```sh
   grep -iE "utility|IsEvUtilityMode|evmode" session.log | tail -40
   ```

4. Look for a call that reads the state. Grep the log for the moment the mode turns on and see
   which service or broadcast carried it.

**Decides:** whether Sambok can enable, disable or read Utility Mode at all, and by which route
(service call, broadcast, or only the car's menu). This picks one of the three toggle fallbacks in
the design.

## Phase 3 — display while locked (~5 min)

Only if Phase 2 left you in Utility Mode. There is no lock control in Sambok, so this is about the
screen, not the doors.

1. Lock the car with the physical key, as usual.
2. Wait two minutes without touching anything. Look at the screen: did it blank?

```sh
$A shell dumpsys power | grep -iE "mWakefulness|Display Power"
$A shell dumpsys display | grep -iE "mScreenState|state="
```

**Decides:** whether the display stays on while the car is locked in Utility Mode. If it blanks,
Sambok cannot work while locked and we stop.

## Phase 4 — approach and fob (~10 min)

Follow `notes/approach-unlock-test.md` as written: log the walk-away and return, poll
`getFOBBatteryStatusInfo` (BCM 54) at 1 m, 5 m and 15 m, and restore Approach Unlock afterwards.

**Decides:** whether any approach/fob signal survives Utility Mode, which is the only route to an
automatic unlock.

## Phase 5 — clean up

- Unlock, leave Utility Mode, restore Approach Unlock and any setting changed in Phase 3.
- Stop the capture (`kill %1` or Ctrl-C), keep `session.log` and `~/approach-test.txt`.
- Note the Utility Mode menu path and anything the car did on lock.

## What to bring back

- The service names from Phase 1.
- The raw reply for each DATC getter, next to what the screen showed.
- What the Utility Mode broadcast did.
- Whether the display stayed on, and the `dumpsys` lines.
- `session.log` and the approach log.
