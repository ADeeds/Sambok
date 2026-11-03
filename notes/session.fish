#!/usr/bin/env fish
# Sambok Task 1 — guided session. Run it, answer the prompts, sit in the car.
#
#     fish ~/software/Sambok/notes/session.fish
#
# It starts a logcat capture, walks you through each step, and writes everything to
# ~/software/Nemesis/dump/sambok/session-<timestamp>.txt plus the matching logcat file.
#
# About 20 minutes, including two waits of two minutes.

set A ~/Android/Sdk/platform-tools/adb
set DIR ~/software/Nemesis/dump/sambok
set STAMP (date +%Y%m%d-%H%M%S)
set REPORT $DIR/session-$STAMP.txt
set LOG $DIR/logcat-$STAMP.txt

mkdir -p $DIR
cd $DIR

function note
    echo $argv | tee -a $REPORT
end

function ask
    while true
        echo -n "  ? $argv: "
        read ans
        if test -n "$ans"
            echo "    -> $ans" | tee -a $REPORT
            return
        end
        echo "    (type an answer, then Enter)"
    end
end

function pause
    echo -n "  [Enter when done] "
    read ignored
end

function call
    echo "\$ $argv" | tee -a $REPORT
    $A shell service call $argv 2>&1 | tee -a $REPORT
end

note "# sambok session "(date)

if not $A devices | grep -q "device"
    note "no adb device — stopping"
    exit 1
end

$A logcat -c
$A logcat > $LOG &
set LOGPID $last_pid
note "logcat -> $LOG"

# ---------------------------------------------------------------- 1. shortcut
note ""
note "## 1. does the shortcut toggle Utility Mode?"
note "   Make sure Utility Mode is OFF and the car is in Park."
pause
$A shell am broadcast -a com.mobis.intent.action.UTILITY_MODE_SHORTCUT 2>&1 | tee -a $REPORT
set outcome (ask "What happened? [on / off / menu / nothing]")
note "outcome: $outcome"

if test "$outcome" = on
    note "   Running it once more to see whether it toggles back off."
    pause
    $A shell am broadcast -a com.mobis.intent.action.UTILITY_MODE_SHORTCUT 2>&1 | tee -a $REPORT
    note "second outcome: "(ask "Now what? [off / menu / nothing]")
end

# ---------------------------------------------------------------- 2. state path
if test "$outcome" != on
    note ""
    note "## 2. what carries the state?"
    note "   Turn Utility Mode ON from the car's own menu while this is capturing."
    pause
    note "   lines logged at the change:"
    grep -iE "utility|discharge|EV_SETTINGS" $LOG | tail -40 | tee -a $REPORT
end

# ---------------------------------------------------------------- 3. probes
note ""
note "## 3. passive probes (datc, hev, display)"
note "   Before continuing, note from the car's screens: the SET temperature and the OUTSIDE temperature."
pause
fish ~/software/Sambok/notes/probe.fish 2>&1 | tee -a $REPORT

# ---------------------------------------------------------------- 4. cutoff
note ""
note "## 4. V2X discharge limit"
note "   In the EV screen, set the V2X discharge limit to 20% so the countdown has a known target."
pause
note "cutoff noted: "(ask "What percentage does the EV screen now show?")

# ---------------------------------------------------------------- 5. display
note ""
note "## 5. display while locked (still in Utility Mode)"
note "   Lock the car with the physical key, then wait two minutes without touching anything."
pause
sleep 120
$A shell dumpsys power 2>&1 | grep -iE "mWakefulness|Display Power" | tee -a $REPORT
$A shell dumpsys display 2>&1 | grep -iE "mScreenState|state=" | tee -a $REPORT

# ---------------------------------------------------------------- 6. approach
note ""
note "## 6. approach / fob (keep the fob with you for now)"
call com.hkmc.micom.bcm 54
pause

note "   Take the fob about 15 m away, leave it there, and come back WITHOUT it."
pause
note "   fob ~15 m away:"
call com.hkmc.micom.bcm 54

note "   Bring the fob back to the driver's door and stand there."
pause
note "   fob at the door:"
call com.hkmc.micom.bcm 54

note "   approach events in the log:"
grep -iE "welcome|approach|smartkey|fob" $LOG | tail -40 | tee -a $REPORT

# ---------------------------------------------------------------- finish
note ""
note "## 7. done"
kill $LOGPID 2>/dev/null
note "report: $REPORT"
note "log:    $LOG"
note ""
note "Paste both files back. Leave Utility Mode off unless you want it running."
note "If you unlocked with the physical key, put the V2X limit back where it was."
