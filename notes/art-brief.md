# Sambok — art brief

Hand this to whoever draws the scene. It is self-contained; nothing here depends on reading the
app's code.

## What this is

A full-screen "dog mode" for a car head unit. The screen is split: a dark information panel on the
left (3/8 of the width) and the illustrated scene on the right (5/8). The scene shows a small desk
fan blowing on a sleeping dog. It is looked at from outside the car, through glass, in daylight and
at dusk, by someone standing a metre or two away.

Everything is drawn in flat shapes on a dark ground. It should read as calm and cared-for, not as a
cartoon title card.

## Canvas

- **Scene canvas: 1200 × 720 px**, transparent background, 1× density (the app scales it).
- Deliver a second copy at 2× (2400 × 1440) if convenient; otherwise the app will upscale.
- **Safe area:** keep all art inside a 40 px inset. Nothing may cross the left edge — the panel
  starts exactly there.
- The ground band occupies the bottom ~120 px.

## Palette (already in the app; use these exact values)

| role | hex |
|---|---|
| backdrop | `#0E1216` |
| ground band | `#23301F` at 50 % |
| dog body | `#C08A55` |
| dog head | `#CE9860` |
| dog ear | `#9E6C3F` |
| dog inner ear | `#7E5530` |
| dog muzzle | `#E6D2B4` |
| dog linework (eyes, nose) | `#24170E` |
| cheek warmth | `#D98C7A` at ~28 % |
| fan metal | `#6E7C8A` |
| fan cage fill | `#1B232B` |
| fan blades | `#9DAAB8` |
| fan hub, base | `#2A333D`, `#3A4652` |
| breeze | `#BFCBD8` at ~50 % |

No other hues. No red, no amber — amber is reserved for the app's one warning strip.

## Composition

- **Fan:** bottom-left of the scene, roughly 110 px wide including base, sitting on the ground band.
  It points right, at the dog.
- **Dog:** centre-right, lying down, **facing left into the fan**. Head lowered, eyes closed,
  content. The tail must grow out of the body's rear — connected, not a separate shape floating
  beside it.
- **Breeze:** three short arcs leaving the fan and drifting right across the dog, fading out.
- Nothing overlaps the panel boundary. No text anywhere — the app draws its own.

## Layers required (so the app can animate them)

Deliver as separate PNG-24 files with alpha, each the full 1200 × 720 canvas unless noted:

1. `scene_bg.png` — ground band and anything static behind everything.
2. `dog.png` — the whole dog, static, including the tail.
3. `fan_base.png` — base and stem.
4. `fan_blades.png` — **just the four blades**, on a square 256 × 256 canvas, with the hub at the
   centre of that square, transparent elsewhere. The app spins this file.
5. `fan_cage.png` — the cage rings, drawn *over* the blades, transparent in the middle.
6. `breeze_1.png`, `breeze_2.png`, `breeze_3.png` — optional. If omitted, the app draws the arcs.

**Alternative if a static illustration is not enough:** a dog spritesheet instead of `dog.png`, as a
2 × 4 grid of 8 frames, each 600 × 600, same palette, and the app will play it at 8 fps. Say which
option you took.

## Motion the app applies (draw for this)

- Blades: one full turn every **2.86 s**, linear.
- Dog: bobs **7 px** upward and back over **1.6 s** each way, ease-in-out.
- Breeze: each arc drifts right over **2.4 s**, staggered by 0.8 s, fading in and out.
- All motion stops dead — a single still frame — whenever the amber strip is visible.

Design the still frame first: it is what people see most of the time, and the one the car shows
when something is wrong.

## Technical constraints

- Target is Android 4.4 (API 19). **No vector drawables, no animated vectors, no WebP, no APNG.**
  PNG-24 with alpha only, plus optional frame-by-frame play.
- Keep each PNG under ~400 KB. Flat colour compresses well; avoid photographic texture.
- Avoid gradients that band on a dark ground; if you need one, dither it.
- No fonts, no text, no logos, no watermarks.
- Keep a soft contact shadow under the dog and the fan; nothing else.

## What "good" looks like

- Legible at 1.2 m through a window in daylight, and still readable at dusk.
- The dog reads as asleep and comfortable, not dead and not excited.
- The fan reads as blowing *on* the dog — the arcs point at it, the dog faces it.
- No element touches the left edge or the bottom edge.

## Acceptance

1. Dog faces left, tail attached, eyes closed, muzzle light.
2. Fan at bottom-left, blades a separate centred square, cage drawn over them.
3. Palette matches the table exactly; no red or amber anywhere.
4. All motion stops cleanly on the still frame.
5. Files named and sized as above; 1× set complete, 2× optional.
