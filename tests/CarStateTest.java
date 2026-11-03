public final class CarStateTest {

    public static void main(String[] args) {
        Check.t("fresh under 30s", new CarState(0).isFresh(29_000), true);
        Check.t("stale at 30s", new CarState(0).isFresh(30_000), false);
        Check.t("fresh at once", new CarState(0).isFresh(0), true);
        Check.t("unknown stays NaN", Double.isNaN(new CarState(0).cabinCelsius), true);
        Check.t("unknown ambient stays NaN", Double.isNaN(new CarState(0).ambientCelsius), true);
        Check.t("unknown soc stays NaN", Double.isNaN(new CarState(0).socPercent), true);
        Check.t("unknown draw stays NaN", Double.isNaN(new CarState(0).drawKw), true);
        Check.t("utility unknown", String.valueOf(new CarState(0).utilityOn), "null");

        Check.done("CarStateTest");
    }
}
