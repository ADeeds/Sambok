package com.sambok;

/**
 * Pure state machine for Bluetooth proximity Auto-Unlock while Dog Mode is active.
 *
 * <p>Uses two RSSI thresholds with a 15-second away debounce so that parking 20-30 ft
 * in front of a storefront neither false-unlocks on a weak fringe reconnect nor fails
 * to arm if the phone stays weakly connected while away:
 * <ul>
 *   <li><b>Far ({@code isFar})</b>: disconnected OR {@code rssiDbm <= FAR_THRESHOLD_DBM} (-78 dBm).
 *       Must hold for {@link #AWAY_DEBOUNCE_MS} (15 s) before transitioning to {@link State#ARMED}.</li>
 *   <li><b>Near ({@code isNear})</b>: connected AND {@code rssiDbm >= NEAR_THRESHOLD_DBM} (-65 dBm).
 *       Once {@link State#ARMED}, doors unlock only when {@code isNear} is reached.</li>
 * </ul>
 */
public final class AutoUnlock {

    public enum State {
        /** No target Bluetooth device configured ("Off"). */
        DISABLED,
        /** Target device configured, waiting for Dog Mode to become active. */
        STANDBY,
        /** Dog Mode active and target device is nearby (or waiting to leave). */
        NEAR,
        /** Target device is far/disconnected; counting down {@link #AWAY_DEBOUNCE_MS}. */
        LEAVING,
        /** Target device has been far/disconnected for >= 15s; waiting for strong near RSSI to unlock. */
        ARMED
    }

    /** Minimum time the device must remain far/disconnected before arming auto-unlock. */
    public static final long AWAY_DEBOUNCE_MS = 15_000L;

    /** Duration after an unlock during which {@link #recentlyUnlocked(long)} returns true. */
    public static final long UNLOCK_BANNER_MS = 10_000L;

    /** Strong RSSI threshold (dBm) right next to the car required to trigger unlock. */
    public static final int NEAR_THRESHOLD_DBM = -65;

    /** Weak RSSI threshold (dBm) at ~20+ ft away that counts as leaving the car. */
    public static final int FAR_THRESHOLD_DBM = -78;

    /** Minimum RSSI (dBm) mapped to 0% on the signal strength bar. */
    public static final int BAR_MIN_DBM = -100;

    /** Maximum RSSI (dBm) mapped to 100% on the signal strength bar. */
    public static final int BAR_MAX_DBM = -40;

    /** Sentinel when disconnected or before the first {@code ACTION_RSSI} broadcast arrives. */
    public static final int NO_RSSI = Integer.MIN_VALUE;

    /**
     * Maps a dBm value linearly across {@code [BAR_MIN_DBM, BAR_MAX_DBM]} ({@code -100..-40 dBm})
     * to a fraction in {@code [0.0, 1.0]}.
     */
    public static double dbmToFraction(int dbm) {
        int clamped = Math.max(BAR_MIN_DBM, Math.min(BAR_MAX_DBM, dbm));
        return (clamped - BAR_MIN_DBM) / (double) (BAR_MAX_DBM - BAR_MIN_DBM);
    }

    /**
     * Maps the current connection state and RSSI reading to a bar fill fraction in
     * {@code [0.0, 1.0]}, returning {@code 0.0} when disconnected or when no RSSI sample has been
     * received yet.
     */
    public static double rssiToFraction(boolean connected, int rssiDbm) {
        if (!connected || rssiDbm == NO_RSSI) {
            return 0.0;
        }
        return dbmToFraction(rssiDbm);
    }

    private String targetAddress;
    private String targetName;
    private State state = State.DISABLED;
    private long awaySinceMs = -1L;
    private long lastUnlockedAtMs = -1L;

    /** Sets or clears ({@code null}) the target Bluetooth device MAC address and display name. */
    public void setTarget(String address, String name) {
        String normalized = (address != null && !address.trim().isEmpty()) ? address.trim() : null;
        this.targetAddress = normalized;
        if (normalized == null) {
            this.targetName = null;
            this.state = State.DISABLED;
        } else {
            this.targetName = (name != null && !name.trim().isEmpty()) ? name.trim() : normalized;
            this.state = State.STANDBY;
        }
        this.awaySinceMs = -1L;
        this.lastUnlockedAtMs = -1L;
    }

    public boolean isEnabled() {
        return targetAddress != null;
    }

    public String targetAddress() {
        return targetAddress;
    }

    public String targetName() {
        return targetName;
    }

    public State state() {
        return state;
    }

    public boolean recentlyUnlocked(long nowMs) {
        return lastUnlockedAtMs >= 0
                && nowMs >= lastUnlockedAtMs
                && (nowMs - lastUnlockedAtMs) < UNLOCK_BANNER_MS;
    }

    /**
     * Advances the state machine and returns {@code true} only on the {@code ARMED -> NEAR}
     * transition when the car doors should be unlocked.
     */
    public boolean update(boolean dogModeActive, boolean connected, int rssiDbm, long nowMs) {
        if (targetAddress == null) {
            state = State.DISABLED;
            awaySinceMs = -1L;
            return false;
        }
        if (!dogModeActive) {
            state = State.STANDBY;
            awaySinceMs = -1L;
            return false;
        }

        int effectiveRssi = connected ? rssiDbm : NO_RSSI;
        boolean isFar = !connected
                || (effectiveRssi != NO_RSSI && effectiveRssi <= FAR_THRESHOLD_DBM);
        boolean isNear = connected
                && effectiveRssi != NO_RSSI
                && effectiveRssi >= NEAR_THRESHOLD_DBM;

        switch (state) {
            case DISABLED:
            case STANDBY:
                if (isFar) {
                    state = State.LEAVING;
                    awaySinceMs = nowMs;
                } else {
                    state = State.NEAR;
                    awaySinceMs = -1L;
                }
                return false;

            case NEAR:
                if (isFar) {
                    state = State.LEAVING;
                    awaySinceMs = nowMs;
                }
                return false;

            case LEAVING:
                if (!isFar) {
                    state = State.NEAR;
                    awaySinceMs = -1L;
                    return false;
                }
                if (nowMs - awaySinceMs >= AWAY_DEBOUNCE_MS) {
                    state = State.ARMED;
                }
                return false;

            case ARMED:
                if (isNear) {
                    state = State.NEAR;
                    awaySinceMs = -1L;
                    lastUnlockedAtMs = nowMs;
                    return true;
                }
                return false;

            default:
                return false;
        }
    }
}
