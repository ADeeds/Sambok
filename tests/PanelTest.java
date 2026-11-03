public final class PanelTest {

    public static void main(String[] args) {
        // colour follows the displayed number
        Check.t("84F is calm", Panel.state(Units.celsiusFor(84, false), false), Panel.State.CALM);
        Check.t("85F is warm", Panel.state(Units.celsiusFor(85, false), false), Panel.State.WARM);
        Check.t("100F is hot", Panel.state(Units.celsiusFor(100, false), false), Panel.State.HOT);
        Check.t("29C metric warm", Panel.state(Units.celsiusFor(29, true), true), Panel.State.WARM);
        Check.t("NaN is unknown", Panel.state(Double.NaN, true), Panel.State.UNKNOWN);
        Check.t("strip on drop", Panel.strip(false, true), true);
        Check.t("strip on unknown", Panel.strip(true, false), true);
        Check.t("no strip calm", Panel.strip(true, true), false);

        Check.done("PanelTest");
    }
}
