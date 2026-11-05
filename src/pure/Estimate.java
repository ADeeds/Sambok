package com.sambok;

/**
 * Pure calculation helpers for energy draw and time estimates.
 *
 * <p>Separated from the Android activity so the logic can be thoroughly unit-tested on the host
 * workstation without Android runtime dependencies.
 */
public final class Estimate {

    /** Maximum plausible remaining minutes from the car's own estimator (7 days). */
    private static final int MAX_PLAUSIBLE_MINUTES = 7 * 24 * 60;

    private Estimate() {
    }

    /**
     * Resolves the current discharge power in watts from car sensors.
     *
     * <p>Prefers the total discharge reading ({@code dischargeW}) if it is known and positive.
     * When discharge is missing or zero (common when idle or before first reading), falls back to
     * estimating total draw from climate consumption ({@code climateRaw * climateScale}) plus
     * auxiliary systems ({@code otherW}) if both are known. Returns {@link Double#NaN} if neither
     * source yields a positive draw.
     *
     * @param dischargeW   total pack discharge in watts, or NaN
     * @param climateRaw   raw climate consumption counter, or NaN
     * @param climateScale multiplier to scale climateRaw to watts
     * @param otherW       draw of other electronic control units in watts, or NaN
     * @return draw in watts (> 0), or {@link Double#NaN} if unknown or non-positive
     */
    public static double drawWatts(double dischargeW, double climateRaw, double climateScale,
            double otherW) {
        double watts;
        if (!Double.isNaN(dischargeW) && dischargeW > 0) {
            watts = dischargeW;
        } else if (!Double.isNaN(climateRaw) && !Double.isNaN(climateScale)
                && !Double.isNaN(otherW)) {
            watts = climateRaw * climateScale + otherW;
        } else {
            return Double.NaN;
        }
        return watts <= 0 ? Double.NaN : watts;
    }

    /**
     * Checks whether a remaining minutes value reported by the car is plausible.
     *
     * <p>Rejects sentinels like {@code -1} (unsupported/calculating) and {@code 65535} (0xFFFF
     * uninitialized flag), as well as zero or implausibly large estimates beyond one week.
     *
     * @param remainingMin remaining minutes as reported by the car
     * @return true only if {@code 0 < remainingMin <= 7 * 24 * 60}
     */
    public static boolean plausibleCarMinutes(int remainingMin) {
        return remainingMin > 0 && remainingMin <= MAX_PLAUSIBLE_MINUTES;
    }
}
