#!/usr/bin/env fish
# Sambok — Utility Mode via the V2X setters. THIS WRITES TO THE CAR.
#
#     fish ~/software/Sambok/notes/v2x-test.fish
#
# Parked, ignition on, Utility Mode currently OFF. It sends
# `IHEVService.setV2XInstantRun` (56) — the call the EV app uses when you tap
# Utility Mode — and reads the state ids before and after.
#
# If anything looks wrong, the stop command is printed at the end of the start step.

set A ~/Android/Sdk/platform-tools/adb
set DIR ~/software/Nemesis/dump/sambok
set STAMP (date +%Y%m%d-%H%M%S)
set REPORT $DIR/v2x-$STAMP.txt

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
    echo -n "  [press Enter when ready] "
    read ignored
end

function ids
    for i in 50 51 52 53 61 62
        echo "  hev 3 i32 $i" | tee -a $REPORT
        $A shell service call com.hkmc.micom.hev 3 i32 $i 2>&1 | tee -a $REPORT
    end
end

note "# sambok v2x test "(date)

if not $A devices | grep -q "device"
    note "no adb device — stopping"
    exit 1
end

note ""
note "Car parked, ignition on, Utility Mode OFF."
pause

note ""
note "## before"
ids

note ""
note "## start — setV2XInstantRun(1)"
$A shell service call com.hkmc.micom.hev 56 i32 1 2>&1 | tee -a $REPORT
note "   (to abort: $A shell service call com.hkmc.micom.hev 56 i32 0)"
sleep 8
set started (ask "Did Utility Mode come on? [y / n / other]")
note "start result: $started"

note ""
note "## after start"
ids

note ""
note "## stop — setV2XInstantRun(0)"
$A shell service call com.hkmc.micom.hev 56 i32 0 2>&1 | tee -a $REPORT
sleep 8
note "stop result: "(ask "Did Utility Mode go off? [y / n / other]")

note ""
note "## after stop"
ids

note ""
note "## read"
note "   Which id changed between before and after? "(ask "Any id clearly on/off? [id or none]")
note "   Did the car do anything unexpected? "(ask "Anything odd? [none / describe]")

note ""
note "report: $REPORT"
note "Leave Utility Mode off when you are done."
