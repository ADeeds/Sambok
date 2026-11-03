/**
 * The left panel's colour and its warning strip.
 *
 * <p>The colour is decided from the number the screen shows, never from the raw reading, so a
 * panel can never show 84 °F while tinting amber.
 */
public final class Panel {

    /** What the cabin temperature does to the panel. */
    public enum State {
        CALM, WARM, HOT, UNKNOWN
    }

    /** Amber from this cabin temperature. */
    private static final double WARM_CELSIUS = 29.44;

    /** Red from this cabin temperature. */
    private static final double HOT_CELSIUS = 37.78;

    private Panel() {
    }

    /** The state for a reading given in celsius; {@code NaN} is an unreadable reading. */
    public static State state(double celsius, boolean isCelsius) {
        if (Double.isNaN(celsius)) {
            return State.UNKNOWN;
        }
        int shown = Units.display(celsius, isCelsius);
        if (shown >= Units.display(HOT_CELSIUS, isCelsius)) {
            return State.HOT;
        }
        if (shown >= Units.display(WARM_CELSIUS, isCelsius)) {
            return State.WARM;
        }
        return State.CALM;
    }

    /** Whether the amber strip covers the panel: Utility Mode dropped, or no reading. */
    public static boolean strip(boolean utilityOn, boolean tempKnown) {
        return !utilityOn || !tempKnown;
    }
}
