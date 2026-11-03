# Sambok — decisions so far

Dog mode built on Utility Mode: full-screen scene, cabin temperature, on-screen lock, and a
best-effort warning when the cabin gets warm.

## Behaviour

- **Sambok activates Utility Mode itself.** Dog mode is one action: enable Utility Mode, take the
  screen. It does not lock the car.
- **No lock control.** Dropped for this version — the control would sit on an in-car screen, and
  opening the door to get out undoes a lock tapped from the seat. The physical key stays the way in
  and out; phone-proximity unlock remains deferred behind the approach test.
- **Best effort, not infallible.** Built for ordinary weather, not heatwaves.
- **Warning is display only.** No horn, no windows, no unlocking, no phone notification. Those
  are explicit non-goals for the first version.

## Screen

- Split layout: info panel on the **driver's (left) side**, dogs on the right.
- Panel shows cabin temperature, a Utility Mode toggle, and a status line.
- **Toggle lives on the dog screen** inside the panel, not on a separate start screen.
- States:
  - normal — calm dark panel
  - **amber from 85 °F** — "getting warm" tint
  - **red from 100 °F** — the alarm state
  - **amber strip across the top when Utility Mode drops**, independent of temperature, so the
    two failures are never confused

## Units and thresholds

- Units follow the car's own setting, read per session: `IDATCService.isUnitCelsius()` (6) or
  `getTemperatureUnit()` (3); USM equivalents are 491 (get) and 145 (set).
- Thresholds are stored once in °C: amber **29.44 °C**, red **37.78 °C** (85 °F / 100 °F),
  rendered as 29 °C / 38 °C when the car is metric.

## Still open

- What happens when Utility Mode refuses to engage, or the temperature can't be read.
- Whether the screen needs an explicit keep-awake, and what the dogs actually are (assets).

## Decided later

- **Leaving dog mode does not turn Utility Mode off.** It stays on; turning it off automatically
  is worth exploring later, not in the first version.

