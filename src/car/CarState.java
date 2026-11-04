package com.sambok;

/**
 * The last readings the car's binder services delivered.
 *
 * <p>A plain holder: {@link CarClient} writes it from the callback threads and the screen reads
 * it, so every field is volatile. The values start unknown (NaN / {@code null}) and stay that
 * way until a callback carries them; nothing here guesses a value.
 */
public final class CarState {

    /** A reading older than this is stale; the screen stops trusting it. */
    static final long FRESH_MS = 30_000;

    /** Cabin air temperature in celsius; {@code NaN} when unknown. */
    public volatile double cabinCelsius = Double.NaN;

    /** Whether the car shows temperatures in celsius; the car's setting, read once per session. */
    public volatile boolean isCelsius;

    /** Outside air temperature in celsius; {@code NaN} when unknown. */
    public volatile double ambientCelsius = Double.NaN;

    /** Battery state of charge in percent; {@code NaN} when unknown. */
    public volatile double socPercent = Double.NaN;

    /** What Utility Mode is drawing now in kW; {@code NaN} when unknown. */
    public volatile double drawKw = Double.NaN;

    /** Utility Mode state; {@code null} when unknown. */
    public volatile Boolean utilityOn;

    /**
     * Maps the car's Utility Mode flag to a tri-state.
     *
     * <p>Measured on the unit: <b>1 is off, 2 is on</b>. Reading it as a boolean makes the screen
     * claim the opposite of the car, which is what the first on-car run showed.
     */
    public static Boolean utilityFromFlag(int value) {
        if (value == 2) {
            return Boolean.TRUE;
        }
        if (value == 1) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** `SystemClock.uptimeMillis()` of the last callback. Pass the same clock to isFresh(). */
    public volatile long lastUpdateMs;

    public CarState(long nowMs) {
        this.lastUpdateMs = nowMs;
    }

    /** True while the last callback is less than {@link #FRESH_MS} before {@code nowMs}. */
    public boolean isFresh(long nowMs) {
        return nowMs - lastUpdateMs < FRESH_MS;
    }
}
