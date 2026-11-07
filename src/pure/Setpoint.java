package com.sambok;

/**
 * The climate set-point line: what the A/C is set to, in the car's own unit.
 *
 * <p>The DATC service sends each set-point twice, once in celsius and once in fahrenheit, and the
 * car rounds each itself (22.5 °C arrives alongside 73 °F, not 72.5). Showing the car's own value
 * for the car's own unit means the screen agrees with the climate panel to the digit.
 *
 * <p>Each value is accompanied by a {@code DATCConstants$TemperatureDisplayState} int ({@code 65535}
 * when active, {@code 255} when blank such as the passenger zone in Driver Only mode), which
 * {@link CarClient} maps to {@code NaN} when not active. A plausible range check here also guards
 * against LO/HI or unset values.
 */
public final class Setpoint {

    /**
     * Plausible celsius set-points, inclusive: the fahrenheit range below, converted (16.7-26.7)
     * and widened to the half degrees the car steps in.
     */
    static final double MIN_CELSIUS = 16.5;
    static final double MAX_CELSIUS = 27.0;

    /**
     * Plausible fahrenheit set-points, inclusive: the owner's reading of the climate panel's
     * range. The car's own logs agree on the floor (the idle rear zone sits at 62 °F / 17 °C).
     * The ceiling is unconfirmed; a value above it hides the line rather than showing a wrong one.
     */
    static final double MIN_FAHRENHEIT = 62.0;
    static final double MAX_FAHRENHEIT = 80.0;

    /** Plausible outside (ambient) temperatures, inclusive. */
    static final double MIN_AMBIENT_CELSIUS = -40.0;
    static final double MAX_AMBIENT_CELSIUS = 60.0;
    static final double MIN_AMBIENT_FAHRENHEIT = -40.0;
    static final double MAX_AMBIENT_FAHRENHEIT = 140.0;

    private Setpoint() {
    }

    /**
     * The set-point as the screen shows it, e.g. {@code 73°F} or {@code 22.5°C}; {@code null}
     * when the value for the car's unit is unknown or implausible.
     */
    public static String format(double celsius, double fahrenheit, boolean isCelsius) {
        if (isCelsius) {
            if (!plausible(celsius, MIN_CELSIUS, MAX_CELSIUS)) {
                return null;
            }
            // Celsius set-points step in halves: show 22.5 as is, 22.0 as 22.
            double halves = Math.round(celsius * 2.0) / 2.0;
            String number = halves == Math.floor(halves)
                    ? String.valueOf((long) halves) : String.valueOf(halves);
            return number + "\u00B0C";
        }
        if (!plausible(fahrenheit, MIN_FAHRENHEIT, MAX_FAHRENHEIT)) {
            return null;
        }
        return Units.format((int) Math.round(fahrenheit), false);
    }

    /**
     * The outside (ambient) temperature in the car's own unit, e.g. {@code 70°F} or {@code 21°C};
     * {@code null} when the value for the car's unit is unknown or implausible.
     */
    public static String formatAmbient(double celsius, double fahrenheit, boolean isCelsius) {
        if (isCelsius) {
            if (!plausible(celsius, MIN_AMBIENT_CELSIUS, MAX_AMBIENT_CELSIUS)) {
                return null;
            }
            return Units.format((int) Math.round(celsius), true);
        }
        if (!plausible(fahrenheit, MIN_AMBIENT_FAHRENHEIT, MAX_AMBIENT_FAHRENHEIT)) {
            return null;
        }
        return Units.format((int) Math.round(fahrenheit), false);
    }

    private static boolean plausible(double value, double min, double max) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value >= min && value <= max;
    }
}
