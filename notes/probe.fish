#!/usr/bin/env fish
# Sambok Task 1 — read-only probes.
#
# Every command here only reads. Nothing changes the car.
#
# Run it and keep the output:
#     fish ~/software/Sambok/notes/probe.fish | tee ~/software/Nemesis/dump/sambok/probe.txt
#
# Then compare the numbers against what the car's own screens show.

set A ~/Android/Sdk/platform-tools/adb

function hr
    echo ""
    echo "## $argv"
    echo ""
end

echo "# sambok probe — "(date)

hr "device"
$A devices

hr "datc getters (unit, isUnitCelsius, climateInfo, driverOnly, VRClimateStatus)"
for c in 3 6 38 39 41
    echo "--- datc $c"
    $A shell service call com.hkmc.micom.datc $c 2>&1
end

hr "hev draw"
echo "--- hev 46 (getPwrConsumption)"
$A shell service call com.hkmc.micom.hev 46 2>&1

hr "hev status ids"
for i in 94 44 115
    echo "--- hev 3 i32 $i"
    $A shell service call com.hkmc.micom.hev 3 i32 $i 2>&1
end

hr "display state"
$A shell dumpsys power 2>&1 | grep -iE "mWakefulness|Display Power"
$A shell dumpsys display 2>&1 | grep -iE "mScreenState|state="

echo ""
echo "# done — paste this whole output back"
