package com.sambok;

/**
 * How long Utility Mode can keep running at the current draw.
 *
 * <p>The draw is a rolling mean of the samples taken on each poll: instantaneous draw swings
 * hard when the compressor cycles, and a countdown that jumps reads as broken. The window is
 * counted in samples, so the caller's poll interval sets the span of time it covers; at the
 * app's five-second poll, {@link #WINDOW} samples are five minutes.
 */
public final class Countdown {

    /** Samples kept for the rolling mean; at a five-second poll this covers five minutes. */
    private static final int WINDOW = 60;

    /** The estimate is rounded to this many minutes. */
    private static final int STEP_MINUTES = 10;

    /** Above this many minutes the screen shows {@code > 1 day}. */
    private static final int DAY_MINUTES = 24 * 60;

    private static final double[] draws = new double[WINDOW];
    private static int next;
    private static int count;

    private Countdown() {
    }

    /** Appends a draw sample; a missing or non-positive reading is dropped, not averaged in. */
    public static void sample(double drawKw) {
        if (!isKnown(drawKw) || drawKw <= 0) {
            return;
        }
        draws[next] = drawKw;
        next = (next + 1) % WINDOW;
        if (count < WINDOW) {
            count++;
        }
    }

    /**
     * The smoothed minutes left between {@code socPercent} and {@code cutoffPercent} on a pack of
     * {@code capacityKwh}, rounded to ten minutes. {@code -1} when an input is unknown,
     * {@code 0} when nothing is left.
     */
    public static int remainingMinutes(double socPercent, double cutoffPercent, double capacityKwh) {
        if (!isKnown(socPercent) || !isKnown(cutoffPercent)
                || !isKnown(capacityKwh) || capacityKwh <= 0) {
            return -1;
        }
        double kwhLeft = (socPercent - cutoffPercent) / 100.0 * capacityKwh;
        if (kwhLeft <= 0) {
            return 0;
        }
        double meanDraw = meanDraw();
        if (!isKnown(meanDraw) || meanDraw <= 0) {
            return -1;
        }
        double raw = kwhLeft / meanDraw * 60.0;
        long tens = Math.round(raw / STEP_MINUTES);
        if (tens > Integer.MAX_VALUE / STEP_MINUTES) {
            return Integer.MAX_VALUE / STEP_MINUTES * STEP_MINUTES;
        }
        int minutes = (int) tens * STEP_MINUTES;
        return minutes < STEP_MINUTES ? STEP_MINUTES : minutes;
    }

    /** A countdown as the screen shows it. */
    public static String format(int minutes) {
        if (minutes < 0) {
            return "\u2014";
        }
        if (minutes == 0) {
            return "0";
        }
        if (minutes >= DAY_MINUTES) {
            long days = Math.round(minutes / (double) DAY_MINUTES);
            if (days < 1) {
                days = 1;
            }
            return "\u2248 " + days + (days == 1 ? " day" : " days");
        }
        return "\u2248 " + minutes / 60 + " h " + minutes % 60 + " m";
    }

    private static double meanDraw() {
        if (count == 0) {
            return Double.NaN;
        }
        double sum = 0;
        for (int i = 0; i < count; i++) {
            sum += draws[i];
        }
        return sum / count;
    }

    private static boolean isKnown(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
