# Sambok — acceptance run (owner)

The first time any of the binder code executes, and the first time the WebView artwork renders on
the unit. Everything before this came out of the firmware dump.

Package: `~/software/Sambok/dist/sambok.zip`

## Before you go

```fish
set A ~/Android/Sdk/platform-tools/adb
```

Copy `dist/sambok.zip` to the root of a blank FAT32 or exFAT stick. Do not extract it. Install it
through NEMESIS Manager, with Android running. There is no launcher icon — launch the app from
Manager.

## Start the log first

```fish
cd ~/software/Nemesis/dump/sambok
$A logcat -c
$A logcat > sambok-run.txt &
set LOGPID $last_pid
```

Keep it running for the whole session; stop it with `kill $LOGPID` at the end.

## The run

1. **Open Sambok.** Expect the panel with "waiting for the car", the toggle greyed, and the
   **right-hand section blank**. A crash here is harmless — nothing is written before a tap — and
   the stack will be in the log.
2. **Tap the toggle.** Expect Utility Mode to come on, the countdown to appear, and the scene to
   appear **and animate** (breathing, ears, tail, vent).
3. **If the panel works but the scene is blank**, that is the WebView, not the binder: grep the log
   for `chromium`, `WebView`, `Console`.
4. **Compare the countdown** with the EV screen's battery percentage and the V2X limit.
5. **Lock with the physical key**, wait two minutes, look in through the window: screen still on,
   dog still moving.
6. **Turn Utility Mode off from the car's own menu.** Expect the amber strip, the dog frozen on a
   still frame, and the countdown at `—`.
7. **Tap the toggle again.** It should start again, or fail with the "could not start" line and a
   re-enabled button.

## Greps when you are back

```fish
grep -iE "sambok|chromium|webview|AndroidRuntime|FATAL" sambok-run.txt | head -60
```

## What to send back

- `sambok-run.txt`
- Whether the app opened, and what the right-hand section did in each state.
- What the countdown said, next to the car's own numbers.
- Whether the dog moved, and whether it stopped when the strip appeared.
- Anything that crashed.
