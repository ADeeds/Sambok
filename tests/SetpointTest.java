import com.sambok.Setpoint;

public final class SetpointTest {

    public static void main(String[] args) {
        // The values the unit actually sent on the car: 22.5 °C alongside 73 °F.
        Check.t("fahrenheit from the car", Setpoint.format(22.5, 73.0, false), "73\u00B0F");
        Check.t("celsius half degree", Setpoint.format(22.5, 73.0, true), "22.5\u00B0C");
        Check.t("celsius whole degree", Setpoint.format(22.0, 72.0, true), "22\u00B0C");

        // Each unit reads only its own value; the other one may be junk.
        Check.t("fahrenheit ignores celsius", Setpoint.format(Double.NaN, 70.0, false), "70\u00B0F");
        Check.t("celsius ignores fahrenheit", Setpoint.format(21.0, Double.NaN, true), "21\u00B0C");

        // Unknown or implausible reads as no line.
        Check.t("NaN celsius", Setpoint.format(Double.NaN, 72.0, true) == null, true);
        Check.t("NaN fahrenheit", Setpoint.format(22.0, Double.NaN, false) == null, true);
        Check.t("unset zero", Setpoint.format(0.0, 0.0, true) == null, true);
        Check.t("ambient-like -40", Setpoint.format(-40.0, -40.0, true) == null, true);
        Check.t("too hot celsius", Setpoint.format(40.0, 104.0, true) == null, true);
        Check.t("too hot fahrenheit", Setpoint.format(40.0, 104.0, false) == null, true);

        // Range edges are inclusive: 62-80 °F, 16.5-27 °C.
        Check.t("fahrenheit floor", Setpoint.format(0.0, 62.0, false), "62\u00B0F");
        Check.t("fahrenheit ceiling", Setpoint.format(0.0, 80.0, false), "80\u00B0F");
        Check.t("below fahrenheit floor", Setpoint.format(0.0, 61.0, false) == null, true);
        Check.t("above fahrenheit ceiling", Setpoint.format(0.0, 81.0, false) == null, true);
        Check.t("celsius floor", Setpoint.format(16.5, 0.0, true), "16.5\u00B0C");
        Check.t("celsius ceiling", Setpoint.format(27.0, 0.0, true), "27\u00B0C");
        Check.t("below celsius floor", Setpoint.format(16.0, 0.0, true) == null, true);
        Check.t("above celsius ceiling", Setpoint.format(27.5, 0.0, true) == null, true);

        Check.done("SetpointTest");
    }
}
