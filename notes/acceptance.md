# Sambok — acceptance run (owner)

The first time any of the binder code executes. Everything before this was read out of the
firmware dump; the car is the only place the codes are real.

Package: `~/software/Sambok/dist/sambok.zip`
Plan step: Task 6, step 5.

## Before you start

```fish
set A ~/Android/Sdk/platform-tools/adb
cd ~/software/Nemesis/dump/sambok
$A logcat -c
$A logcat > sambok-run.txt &
set LOGPID $last_pid
```

## The run

1. **Install** `dist/sambok.zip` through NEMESIS, reboot, open Sambok.
   Expect: **"waiting for the car"** with the toggle greyed out for a moment, then either the
   dog screen (Utility Mode already on) or "tap to start". If it stays on "waiting" for 10 s and
   then shows "tap to start", the HEV listener is not delivering `VCU_UTILMODUSMSTA`.

2. **Tap the toggle.**
   Expect: the toggle locks, then the dog screen with a countdown. Nothing should happen on a
   long press or a second tap while it is starting.

3. **Read the countdown.** Compare with the EV screen's battery percentage and the V2X limit you
   set. If the number is absurd, `onPwrConsumptionChanged`'s unit is not what we assumed — note
   the raw value you see and stop.

4. **Lock with the physical key**, wait two minutes, look in through the window.
   Expect: the screen still on, still showing the countdown.

5. **Turn Utility Mode off from the car's own menu**, with Sambok still open.
   Expect: the scene stays, the amber strip appears, the countdown goes to `—`.

6. **Tap the toggle again** to bring it back.
   Expect: it starts again, or fails with the "could not start" line and a re-enabled toggle.

## What to send back

- `sambok-run.txt`
- What step 1 showed, and how long the waiting state lasted.
- What the countdown said, next to the car's own numbers.
- Whether the screen stayed on while locked.
- Anything that crashed.

## If it crashes at launch

That is the most likely failure, and it means a transaction code is wrong. `logcat` will carry
the exception and the service name. Nothing is written to the car before the toggle is tapped,
so a launch crash is harmless.
