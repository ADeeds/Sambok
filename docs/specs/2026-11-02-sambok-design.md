# Sambok — design

*2026-11-02. Owner: 2023 Ioniq 5 (US), firmware `NEEV23.USA.S5W_M.V015.012.260507`.*

## Intent and why

Utility Mode keeps the car's climate running while parked, which is what makes leaving a dog in
the car survivable. Two things stop it from being usable as a dog mode: the fob's remote lock
doesn't work, so you have to lock with the physical key, and nothing on screen tells a passer-by
that the dog is fine and the car is comfortable.

Sambok puts the setup behind one action and takes over the screen: it turns on Utility Mode,
locks on request, and shows a full-screen scene with the cabin temperature and a warning colour
when the cabin gets warm.

It is a **best-effort convenience**, not a life-support system. It is built for ordinary weather,
it depends on the car's own climate working, and it can be beaten by a heatwave or a bug.

## Goals

- One action from the driver's seat: Utility Mode on, dog screen up, doors locked.
- A screen that reads from outside the car: dogs, the cabin temperature in the car's own units,
  and an obvious state when something is wrong.
- A rough countdown of how long Utility Mode will last at the current draw, measured against the
  car's own discharge cutoff.

## Non-goals

- Guaranteeing the dog's safety, in extreme heat or otherwise.
- Horn, lights, windows, unlocking, or phone notifications. Colour and text only.
- Turning Utility Mode off when the screen closes. Recorded as a later exploration.
- **Locking or unlocking the car.** Dropped for this version: the control would live on an in-car
  screen, and opening the door to get out undoes a lock tapped from the seat. The physical key
  stays the way in and out.
- Unlocking by phone proximity. Deferred; it depends on the approach test in
  `notes/approach-unlock-test.md`.
- Any vehicle other than this one on this firmware.

## Anti-goals

- Showing a calm screen while the climate has stopped.
- Writing the car's state on launch, before the driver asks for it.
- Locking the car without an explicit tap.
- Pretending a temperature is known when it isn't.

## Decided behaviour

These are the owner's decisions.

- **Sambok activates Utility Mode itself.**
- **No lock control.** Dropped for this version. Locking stays with the physical key, and
  proximity unlock remains deferred behind the approach test.
- **Warning is display only.** Amber from 85 °F, red from 100 °F, and an amber strip across the
  top when Utility Mode drops — independent of temperature, so the two failures never look alike.
- **Leaving dog mode leaves Utility Mode running.**
- **Units follow the car's setting**, read each session; thresholds are stored in °C so they don't
  drift when the car's unit changes.

## Constraints and what the car already provides

Verified on this firmware during the Hogsheads work:

- Gen5W `S5W_M`, Android 4.4.2 (API 19), `ro.build.product` `NEEV23.USA.0000.V015.012.260507`.
- No CAN access from Android. Vehicle functions are binder services in `system_server`, callable
  as root with `service call <name> <code> [i32 <arg>]`; a clean reply's first word is `0`.
- Root from an ordinary app: `/system/xbin/nemesis root -c "…"`.
- **Lock:** `IBCMService.lockDoor` (5), readback `getDoorLockStatus` (9).
- **Units:** `IDATCService.isUnitCelsius` (6) and `getTemperatureUnit` (3); USM equivalents 491
  and 145.
- **Temperature:** the DATC service carries cabin temperature getters (the nav calls
  `getInsideTemperature`). Discovery pins the transaction **and its return encoding** — unit,
  scale, offset — because the colour rule depends on knowing the source unit.
- **Utility Mode:** `com.mobis.intent.action.UTILITY_MODE_SHORTCUT` / `ACTION_SHORTCUT_UTILITY_MODE`
  and an `IsEvUtilityMode` check exist. Discovery pins, in this order: the **state getter**, the
  **enable** call, the **disable** call. Those shortcuts may only open the car's own menu, and the
  fallback for that case is below.

## Approaches considered

- **A single APK that binds the car's services directly.** Chosen. The sensor values Sambok needs
  are not getters at all — they are listener callbacks (`IDATCListener.onTemperatureInformation`,
  `IHEVListener.onPwrConsumptionChanged`, `onBatteryState`), so the app has to hold a live binder
  listener anyway. Once it is bound, the Utility Mode write is one more call on the same binder.
  No root channel, no polling, no shell.
- **A shell-caller using `service call`.** Rejected: it can reach the *settings* getters, but every
  sensor read returns `-1` because the values only arrive through callbacks.
- **APK plus a resident shell service.** Rejected: with the screen gone there is nothing to watch.

## Before any building: one platform check

One premise is unverified **under Utility Mode**, and it changes the shape of the plan rather than
its details. It is the plan's first step, done parked, with a log read — no new code.

**Does the head unit's display stay on while the car is locked in Utility Mode?** If the car blanks
it, the screen never reaches the person the feature exists for, and Sambok stops rather than
shipping something that only works unlocked. (The lock itself is the physical key, so nothing here
writes to the car.)

## How it works

One Android app, installed through NEMESIS. Launching it opens a full-screen Activity that hides
the status bar and keeps the display awake.

The Activity binds `com.hkmc.micom.datc` and `com.hkmc.micom.hev` through `ServiceManager` and
registers a listener on each. Sensor values arrive as callbacks — cabin temperature from
`onTemperatureInformation`, draw from `onPwrConsumptionChanged`, SOC from `onBatteryState` — and
the screen paints from the last value each callback delivered. Nothing polls, and nothing needs
root: these services are reachable from an ordinary app, as the sibling projects already show.

API 19 has no screen pinning, so the app does not try to trap the user: Home and Back behave
normally and simply leave the dog screen. The car is locked, so the only person who can press them
is the driver. *(My call; a kiosk mode would mean becoming the launcher or using a NEMESIS hook,
and it buys nothing here.)*

Opening the app writes nothing. The panel's Utility Mode toggle is the action and it is two-way:
with it off the screen shows a "tap to start" line; tapping turns Utility Mode on through
`setV2XInstantRun` and the screen becomes the dog scene; tapping again turns it off and returns to
that state. A one-way control would not be drawn as a toggle. *(My call.)*

Engagement is confirmed, not assumed: after the enable call the app waits for the state to read as
on, within a 10-second deadline. *(My call; the deadline is tunable on the car.)*

On launch the app reads the state before drawing. If Utility Mode is already on — the usual case,
since leaving dog mode leaves it running — the dog scene opens directly with the toggle on. Reads
on launch are allowed; writes are not. *(My call.)*

What the control can be is decided by discovery, in this order:

- **state getter + enable + disable** → the two-way toggle above.
- **state getter + enable only** → a one-way "Start Utility Mode" button, not drawn as a toggle;
  turning it off is done from the car's own settings.
- **shortcut only** → a button that opens the car's own Utility Mode screen, with no dog scene,
  because Sambok cannot confirm the climate is running.

*(My call; the fallbacks keep the screen honest instead of pretending to control something the
platform does not expose.)*

While running, the app:

- re-reads Utility Mode state and cabin temperature every few seconds;
- colours the panel from the thresholds, in the car's current unit;
- shows the amber strip and stops claiming comfort when Utility Mode goes off, or when the
  temperature can't be read.

Closing the Activity leaves Utility Mode as it is.

### The countdown

`time left = (SOC − cutoff) × pack capacity ÷ draw`, where SOC comes from the keyed
`getHEVStatus` getter, the cutoff is the car's V2X discharge limit (the owner confirms it is the
same setting Utility Mode stops at), and draw is `getPwrConsumption` (46).

- Draw is smoothed: a rolling mean of the samples taken on each tick, over roughly five minutes.
  Instantaneous draw swings hard when the compressor cycles, and a countdown that jumps reads as
  broken.
- The result is rounded to ten minutes and shown with a `≈`. Coarse and honest beats precise and
  wrong.
- Above 24 hours it shows `> 1 day`; at or below zero it shows `0`.
- Pack capacity is a constant for this car (77.4 kWh), confirmed during discovery rather than
  read from the car. *(My call; reading capacity costs a lookup that may not exist.)*

## Failure handling

- **Utility Mode refuses to engage:** the toggle returns to off, no dog scene is shown, and the
  panel carries a short generic line — the platform may not report a reason, so the copy says what
  to check (car on, in Park) rather than pretending to know.
- **Temperature unreadable:** show `—` rather than a number, and raise the amber strip. Silence
  is the dangerous failure, so an unknown temperature is treated as a warning, not as calm.
- **Utility Mode drops mid-session:** keep the dog scene, keep the strip up, and keep showing the
  temperature if it can still be read — the number is the most useful thing on the screen once the
  climate has stopped. If it cannot be read, show `—` and force the panel to amber: there is no
  calm state with the strip up.
- **The countdown's inputs are unreadable** (no SOC, no cutoff, no draw): show `—` in place of the
  time and leave everything else working. A wrong number is worse than a dash — a driver could
  plan a walk around it.
- **A car call fails or times out:** no retry storm; log it, show the strip, keep the screen
  alive. The driver is outside the car and a frozen screen is worse than a truthful one.

## Screen

Split layout, at the head unit's 8:3 shape:

- **Left (driver's side):** cabin temperature, the estimated Utility Mode time left, the Utility
  Mode toggle, and a status line.
- **Right:** the dog scene.
- Screen states, mutually exclusive except for the strip:
  1. **off** — Utility Mode off, toggle off, "tap to start", no dog scene.
  2. **starting** — enable sent, waiting for the state to read on (10 s deadline).
  3. **calm** — running, cabin below the amber line.
  4. **warm** — running, cabin at or above 85 °F.
  5. **hot** — running, cabin at or above 100 °F.
  The **amber strip** overlays 3–5 when Utility Mode drops or the temperature is unreadable.
  Re-enabling after a drop that fails returns to **1**, not to a kept dog scene.
- Colour is decided from the value **as displayed**: convert the reading to the car's unit, round
  it to a whole degree, and compare against the threshold in that same unit, so the screen never
  shows 84 °F while tinting amber. Thresholds are stored once in °C — **29.44** and **37.78** —
  and rendered as 85 °F / 100 °F or 29 °C / 38 °C by that same rounding. *(My call; one stored
  value per threshold removes the 29.4-versus-29 drift.)*

Copy, type sizes and the dog assets are left to the builder.

## What the builder still needs

- Discovery, in this order: the Utility Mode **state getter**, then enable and disable; the
  cabin-temperature getter **and its encoding**.
- The countdown's inputs, all from `IHEVListener` callbacks: SOC from `onBatteryState`, draw from
  `onPwrConsumptionChanged`, and the cutoff from the V2X discharge limit — only its setter (57) is
  known, so it is read once from the car's own screen or entered by hand.
- The dog animation assets and how the scene is drawn at 8:3.
- Keep-awake details. Screen pinning is decided against.
- Whether to keep the thresholds configurable or fixed at the two values above.

## Decided: no lock control

The lock control lived on the in-car screen, so it would be tapped from the driver's seat — and
opening a door to get out undoes a lock tapped from there. Rather than build an arm-and-relock, the
owner dropped locking from this version: the physical key stays the way in and out. Nothing in the
design writes to the car except Utility Mode.
