import com.sambok.CarState;

public final class CarStateTest {

    public static void main(String[] args) {
        Check.t("fresh under 30s", new CarState(0).isFresh(29_000), true);
        Check.t("stale at 30s", new CarState(0).isFresh(30_000), false);
        Check.t("fresh at once", new CarState(0).isFresh(0), true);
        Check.t("unknown stays NaN", Double.isNaN(new CarState(0).cabinCelsius), true);
        Check.t("unknown ambient stays NaN", Double.isNaN(new CarState(0).ambientCelsius), true);
        Check.t("unknown soc stays NaN", Double.isNaN(new CarState(0).socPercent), true);
        Check.t("cutoff defaults to 20%", new CarState(0).cutoffPercent, 20.0);
        Check.t("unknown draw stays NaN", Double.isNaN(new CarState(0).drawW), true);
        Check.t("utility unknown", String.valueOf(new CarState(0).utilityOn), "null");
        Check.t("charging starts false", new CarState(0).charging, false);
        Check.t("vcms idle not charging", CarState.isCharging(1, 0, 0), false);
        Check.t("vcms ignores spurious chg_sts", CarState.isCharging(1, 0, 1), false);
        Check.t("vcms ac charging (2)", CarState.isCharging(1, 2, 0), true);
        Check.t("vcms dc charging (3)", CarState.isCharging(1, 3, 0), true);
        Check.t("vcms alarm 7 charging", CarState.isCharging(1, 7, 0), true);
        Check.t("vcms alarm 8 charging", CarState.isCharging(1, 8, 0), true);
        Check.t("vcms alarm 10 charging", CarState.isCharging(1, 10, 0), true);
        Check.t("vcms alarm 11 charging", CarState.isCharging(1, 11, 0), true);
        Check.t("legacy chg_sts 1 charging", CarState.isCharging(0, 0, 1), true);
        Check.t("legacy chg_sts 0 not charging", CarState.isCharging(0, 0, 0), false);

        Check.t("evReady 1 is true", String.valueOf(CarState.evReadyFromFlag(1)), "true");
        Check.t("evReady 0 is false", String.valueOf(CarState.evReadyFromFlag(0)), "false");
        Check.t("evReady -1 is null", String.valueOf(CarState.evReadyFromFlag(-1)), "null");
        Check.t("gear P (0) is in Park", String.valueOf(CarState.inParkFromGear(0)), "true");
        Check.t("gear D (5) is not in Park", String.valueOf(CarState.inParkFromGear(5)), "false");
        Check.t("gear N (6) is not in Park", String.valueOf(CarState.inParkFromGear(6)), "false");
        Check.t("gear R (7) is not in Park", String.valueOf(CarState.inParkFromGear(7)), "false");
        Check.t("gear NONE (255) is null", String.valueOf(CarState.inParkFromGear(255)), "null");

        CarState s = new CarState(0);
        Check.t("readyAndParked starts null", String.valueOf(s.isReadyAndParked()), "null");
        s.evReady = Boolean.TRUE;
        Check.t("readyAndParked null when park unknown",
                String.valueOf(s.isReadyAndParked()), "null");
        s.inPark = Boolean.TRUE;
        Check.t("readyAndParked true when ready and in Park",
                String.valueOf(s.isReadyAndParked()), "true");
        s.inPark = Boolean.FALSE;
        Check.t("readyAndParked false when not in Park",
                String.valueOf(s.isReadyAndParked()), "false");
        s.inPark = null;
        s.evReady = Boolean.FALSE;
        Check.t("readyAndParked false when not ready",
                String.valueOf(s.isReadyAndParked()), "false");

        Check.done("CarStateTest");
    }
}
