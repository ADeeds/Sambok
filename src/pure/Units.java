/**
 * Temperature display rules.
 *
 * <p>One reading, one displayed whole degree: the colour and the number always agree because
 * both come from {@link #display}.
 */
public final class Units {

    private Units() {
    }

    /** The whole degree the screen shows for a reading given in celsius. */
    public static int display(double celsius, boolean isCelsius) {
        return (int) Math.round(toDisplayed(celsius, isCelsius));
    }

    /** A whole displayed degree back as a celsius reading; the inverse of {@link #display}. */
    public static double celsiusFor(int displayed, boolean isCelsius) {
        return isCelsius ? displayed : (displayed - 32) * 5.0 / 9.0;
    }

    /** The displayed degree with its unit, e.g. {@code 21°C}. */
    public static String format(int value, boolean isCelsius) {
        return value + (isCelsius ? "\u00B0C" : "\u00B0F");
    }

    private static double toDisplayed(double celsius, boolean isCelsius) {
        return isCelsius ? celsius : celsius * 9.0 / 5.0 + 32.0;
    }
}
