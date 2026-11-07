import com.sambok.AutoUnlock;

public final class AutoUnlockTest {

    public static void main(String[] args) {
        // 1. Disabled by default when no target device is selected.
        AutoUnlock au = new AutoUnlock();
        Check.t("initial state DISABLED", au.state(), AutoUnlock.State.DISABLED);
        Check.t("initial isEnabled false", au.isEnabled(), false);
        Check.t("disabled update when disconnected",
                au.update(true, false, AutoUnlock.NO_RSSI, 1_000L), false);
        Check.t("still DISABLED", au.state(), AutoUnlock.State.DISABLED);

        // 2. Selecting a target device enters STANDBY while Dog Mode is off.
        au.setTarget("AA:BB:CC:DD:EE:FF", "Pixel 8");
        Check.t("isEnabled true", au.isEnabled(), true);
        Check.t("targetAddress", au.targetAddress(), "AA:BB:CC:DD:EE:FF");
        Check.t("targetName", au.targetName(), "Pixel 8");
        Check.t("state STANDBY", au.state(), AutoUnlock.State.STANDBY);

        // Leaving and returning while Dog Mode is off never arms or unlocks.
        Check.t("dog mode off disconnected",
                au.update(false, false, AutoUnlock.NO_RSSI, 10_000L), false);
        Check.t("dog mode off 30s later",
                au.update(false, false, AutoUnlock.NO_RSSI, 40_000L), false);
        Check.t("dog mode off reconnect",
                au.update(false, true, -55, 45_000L), false);
        Check.t("still STANDBY", au.state(), AutoUnlock.State.STANDBY);

        // 3. Dog Mode active + strong signal -> NEAR.
        Check.t("enter NEAR on Dog Mode start",
                au.update(true, true, -58, 50_000L), false);
        Check.t("state NEAR", au.state(), AutoUnlock.State.NEAR);

        // 4. Brief disconnect (< 15s) returns to NEAR without unlocking.
        Check.t("disconnect starts LEAVING",
                au.update(true, false, AutoUnlock.NO_RSSI, 60_000L), false);
        Check.t("state LEAVING", au.state(), AutoUnlock.State.LEAVING);
        Check.t("still LEAVING at 14.9s",
                au.update(true, false, AutoUnlock.NO_RSSI, 74_900L), false);
        Check.t("state still LEAVING", au.state(), AutoUnlock.State.LEAVING);
        Check.t("reconnect at 14.9s cancels LEAVING without unlock",
                au.update(true, true, -60, 74_950L), false);
        Check.t("back to NEAR", au.state(), AutoUnlock.State.NEAR);

        // 5. Parked in front of a store (20-30 ft away):
        //    Disconnects for >= 15s -> ARMED, then reconnects at weak RSSI (-82 dBm) inside store.
        //    Must NOT unlock until owner walks up to car (>= -65 dBm).
        Check.t("walk away at t=100s",
                au.update(true, false, AutoUnlock.NO_RSSI, 100_000L), false);
        Check.t("state LEAVING", au.state(), AutoUnlock.State.LEAVING);
        Check.t("15s elapsed -> ARMED",
                au.update(true, false, AutoUnlock.NO_RSSI, 115_000L), false);
        Check.t("state ARMED", au.state(), AutoUnlock.State.ARMED);

        // Reconnected before first RSSI callback arrives (NO_RSSI): stays ARMED, no unlock.
        Check.t("ACL reconnected but NO_RSSI yet",
                au.update(true, true, AutoUnlock.NO_RSSI, 120_000L), false);
        Check.t("still ARMED waiting for RSSI", au.state(), AutoUnlock.State.ARMED);

        // Fringe reconnect inside store at -82 dBm and -70 dBm: stays ARMED, no unlock!
        Check.t("weak RSSI -82 dBm in store",
                au.update(true, true, -82, 122_000L), false);
        Check.t("still ARMED at -82 dBm", au.state(), AutoUnlock.State.ARMED);
        Check.t("medium RSSI -70 dBm approaching",
                au.update(true, true, -70, 124_000L), false);
        Check.t("still ARMED at -70 dBm", au.state(), AutoUnlock.State.ARMED);

        // Owner reaches the car door (-64 dBm >= -65 dBm): UNLOCKS!
        Check.t("strong RSSI -64 dBm at car door triggers unlock",
                au.update(true, true, -64, 126_000L), true);
        Check.t("state NEAR after unlock", au.state(), AutoUnlock.State.NEAR);
        Check.t("recentlyUnlocked true right after unlock",
                au.recentlyUnlocked(128_000L), true);
        Check.t("recentlyUnlocked false after banner expires",
                au.recentlyUnlocked(140_000L), false);

        // 6. Storefront scenario where Bluetooth NEVER disconnects, but RSSI drops <= -78 dBm
        //    for >= 15s (re-arming during the same Dog Mode session).
        Check.t("signal drops to -80 dBm at t=200s",
                au.update(true, true, -80, 200_000L), false);
        Check.t("state LEAVING on weak RSSI", au.state(), AutoUnlock.State.LEAVING);
        Check.t("still weak -84 dBm at t=215s -> ARMED",
                au.update(true, true, -84, 215_000L), false);
        Check.t("state ARMED on sustained weak RSSI", au.state(), AutoUnlock.State.ARMED);

        // Fluctuating inside store (-72 dBm) keeps it ARMED without unlocking or disarming.
        Check.t("fluctuate to -72 dBm in store",
                au.update(true, true, -72, 220_000L), false);
        Check.t("still ARMED at -72 dBm", au.state(), AutoUnlock.State.ARMED);

        // Walking back up to the car (-65 dBm) triggers unlock again!
        Check.t("return to -65 dBm triggers second unlock",
                au.update(true, true, -65, 225_000L), true);
        Check.t("state NEAR after second unlock", au.state(), AutoUnlock.State.NEAR);

        // 7. Turning Dog Mode off resets ARMED back to STANDBY.
        au.update(true, false, AutoUnlock.NO_RSSI, 300_000L);
        au.update(true, false, AutoUnlock.NO_RSSI, 320_000L);
        Check.t("armed before dog mode off", au.state(), AutoUnlock.State.ARMED);
        Check.t("dog mode off resets to STANDBY",
                au.update(false, false, AutoUnlock.NO_RSSI, 322_000L), false);
        Check.t("state STANDBY", au.state(), AutoUnlock.State.STANDBY);

        // 8. Clearing target disables AutoUnlock.
        au.setTarget(null, null);
        Check.t("cleared state DISABLED", au.state(), AutoUnlock.State.DISABLED);
        Check.t("cleared isEnabled false", au.isEnabled(), false);

        // 9. Signal strength bar fraction mapping (-100 dBm .. -40 dBm -> 0.0 .. 1.0).
        Check.t("disconnected is 0.0", AutoUnlock.rssiToFraction(false, -55), 0.0);
        Check.t("connected NO_RSSI is 0.0",
                AutoUnlock.rssiToFraction(true, AutoUnlock.NO_RSSI), 0.0);
        Check.t("-100 dBm is 0.0", AutoUnlock.rssiToFraction(true, -100), 0.0);
        Check.t("-110 dBm clamps to 0.0", AutoUnlock.rssiToFraction(true, -110), 0.0);
        Check.t("-70 dBm is 0.5", AutoUnlock.rssiToFraction(true, -70), 0.5);
        Check.t("-40 dBm is 1.0", AutoUnlock.rssiToFraction(true, -40), 1.0);
        Check.t("-30 dBm clamps to 1.0", AutoUnlock.rssiToFraction(true, -30), 1.0);
        Check.t("arm tick < unlock tick",
                AutoUnlock.dbmToFraction(AutoUnlock.FAR_THRESHOLD_DBM)
                        < AutoUnlock.dbmToFraction(AutoUnlock.NEAR_THRESHOLD_DBM),
                true);

        Check.done("AutoUnlockTest");
    }
}
