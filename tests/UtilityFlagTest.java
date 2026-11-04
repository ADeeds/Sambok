import com.sambok.CarState;

/**
 * The Utility Mode flag is not a boolean on this unit: 1 is off and 2 is on. The first on-car run
 * showed the screen claiming the opposite of the car, so this mapping gets its own test.
 */
public final class UtilityFlagTest {

    public static void main(String[] args) {
        Check.t("1 is off", String.valueOf(CarState.utilityFromFlag(1)), "false");
        Check.t("2 is on", String.valueOf(CarState.utilityFromFlag(2)), "true");
        Check.t("0 is unknown", String.valueOf(CarState.utilityFromFlag(0)), "null");
        Check.t("3 is unknown", String.valueOf(CarState.utilityFromFlag(3)), "null");
        Check.t("-1 is unknown", String.valueOf(CarState.utilityFromFlag(-1)), "null");

        Check.done("UtilityFlagTest");
    }
}
