# Approach / fob-presence test — run before designing Sambok's unlock

**Why:** Utility Mode kills the fob's remote lock, and the car does not auto-unlock on approach
with the physical key. If the car still *detects* the fob approaching — even though it doesn't
act on it — Sambok can listen for that and unlock through BCM, which is the closest thing to
making the fob work again.

**What we already know:**

- No "key in range" getter exists in any interface. The only fob getter is
  `getFOBBatteryStatusInfo` (BCM 54).
- Approach detection exists for other purposes: `setConvenience_EnableOnDriverApproach`
  (USM 129 / getter 464), the welcome mirror light, and `com.mobis.welcome.WelcomeService`.
- Lock/unlock: BCM `lockDoor` (5), `getDoorLockStatus` (9).

## Before you start

- Car parked somewhere safe; about ten minutes.
- ADB working.
- Key fob, plus the physical key (for locking).
- Pen, to write down the two values in step 1.

## Steps

1. **Read the current settings** and write both numbers down:

   ```sh
   adb shell service call com.hkmc.micom.bcm 399   # getDoor_ApproachUnlock
   adb shell service call com.hkmc.micom.bcm 9     # getDoorLockStatus
   ```

2. **Turn Approach Unlock on** (skip if it was already on):

   ```sh
   adb shell service call com.hkmc.micom.bcm 82 i32 1
   ```

3. **Start the capture** and leave it running:

   ```sh
   adb logcat -c
   adb logcat > ~/approach-test.txt
   ```

4. **Enter Utility Mode.** Write down the exact menu path — Sambok will need it.

5. **Lock the car with the physical key.** Note whether it beeps, folds the mirrors, or flashes.

6. **Walk away** for about 30 seconds, fob in pocket, well out of range.

7. **Walk back and stand at the driver's door** for about 10 seconds. Watch for an unlock, the
   mirror light, or a welcome animation.

8. **Poll the fob value** at the door, then around 5 m, then around 15 m:

   ```sh
   adb shell service call com.hkmc.micom.bcm 54   # getFOBBatteryStatusInfo
   ```

   Record each number and the distance.

9. **Stop the capture** with Ctrl-C, then look for events:

   ```sh
   grep -iE "welcome|approach|smartkey|fob|unlock" ~/approach-test.txt | head -50
   ```

10. **Put Approach Unlock back** to the value from step 1:

    ```sh
    adb shell service call com.hkmc.micom.bcm 82 i32 <the original number>
    ```

## What counts as a pass

- A welcome / approach / smart-key event shows up in the log when you come back → Sambok can
  listen for it and unlock. Best outcome.
- Or `getFOBBatteryStatusInfo` changes with distance → a pollable signal, good enough.
- Neither → manual lock only; unlock with the physical key. Sambok ships without unlock.

## Also record

- The exact Utility Mode menu path (Sambok will activate it itself).
- Whether the screen stayed on while locked.
- Anything else the car did on lock (beep, mirrors, lights).

## Decisions so far

- Sambok activates Utility Mode itself.
- Lock is on demand; if no key signal turns up, unlocking is the physical key.
- Over-temperature response is the screen turning red, and nothing else. Horn, windows and phone
  notifications are explicit non-goals for the first version.
