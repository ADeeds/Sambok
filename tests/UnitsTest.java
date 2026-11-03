import com.sambok.Units;

public final class UnitsTest {

    public static void main(String[] args) {
        Check.t("21C shows 21", Units.display(21.0, true), 21);
        Check.t("21C shows 70F", Units.display(21.0, false), 70);
        Check.t("29.44C is 85F", Units.display(29.44, false), 85);
        Check.t("29.4C displays 85F", Units.display(29.4, false), 85);
        Check.t("29.3C displays 85F", Units.display(29.3, false), 85);

        Check.t("format celsius", Units.format(21, true), "21\u00B0C");
        Check.t("format fahrenheit", Units.format(70, false), "70\u00B0F");

        Check.done("UnitsTest");
    }
}
