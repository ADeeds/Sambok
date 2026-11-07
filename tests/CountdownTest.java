import com.sambok.Countdown;

public final class CountdownTest {

    public static void main(String[] args) {
        Check.t("one minute", Countdown.format(1), "\u2248 1 m");
        Check.t("forty five minutes", Countdown.format(45), "\u2248 45 m");
        Check.t("one hour", Countdown.format(60), "\u2248 1 h 0 m");
        Check.t("ten min rounding", Countdown.format(70), "\u2248 1 h 10 m");
        Check.t("just over a day", Countdown.format(1500), "\u2248 25 h 0 m");
        Check.t("two days", Countdown.format(2880), "\u2248 2 days");
        Check.t("three days", Countdown.format(4320), "\u2248 3 days");
        Check.t("zero", Countdown.format(0), "0");
        Check.t("unknown", Countdown.format(-1), "\u2014");

        Countdown model = new Countdown();
        Check.t("no samples", model.remainingMinutes(80, 20, 77.4), -1);

        // 60% of a 77.4 kWh pack is 46.44 kWh; at 1 kW that is 2786.4 minutes, rounded to 2790.
        model.sample(1.0);
        model.sample(1.0);
        model.sample(1.0);
        Check.t("steady 1 kW", model.remainingMinutes(80, 20, 77.4), 2790);

        // Three more samples at 3 kW: the mean must sit between 1 and 3 kW, not at either end.
        // Steady 3 kW would be 928.8 minutes, rounded to 930.
        model.sample(3.0);
        model.sample(3.0);
        model.sample(3.0);
        int smoothed = model.remainingMinutes(80, 20, 77.4);
        Check.t("smoothed below 1 kW", smoothed < 2790, true);
        Check.t("smoothed above 3 kW", smoothed > 930, true);

        Check.t("unknown soc", model.remainingMinutes(Double.NaN, 20, 77.4), -1);
        Check.t("unknown cutoff", model.remainingMinutes(80, Double.NaN, 77.4), -1);
        Check.t("unknown capacity", model.remainingMinutes(80, 20, Double.NaN), -1);
        Check.t("at cutoff", model.remainingMinutes(20, 20, 77.4), 0);
        Check.t("below cutoff", model.remainingMinutes(10, 20, 77.4), 0);

        // Battery icon level by remaining runtime: >4h full, <=4h half, <=2h amber, <=1h red.
        Check.t("battery icon unknown", Countdown.batteryIcon(-1), Countdown.BatteryIcon.FULL);
        Check.t("battery icon >4h", Countdown.batteryIcon(250), Countdown.BatteryIcon.FULL);
        Check.t("battery icon 4h", Countdown.batteryIcon(240), Countdown.BatteryIcon.HALF);
        Check.t("battery icon 3h", Countdown.batteryIcon(180), Countdown.BatteryIcon.HALF);
        Check.t("battery icon >2h", Countdown.batteryIcon(130), Countdown.BatteryIcon.HALF);
        Check.t("battery icon 2h", Countdown.batteryIcon(120), Countdown.BatteryIcon.AMBER);
        Check.t("battery icon 90m", Countdown.batteryIcon(90), Countdown.BatteryIcon.AMBER);
        Check.t("battery icon >1h", Countdown.batteryIcon(70), Countdown.BatteryIcon.AMBER);
        Check.t("battery icon 1h", Countdown.batteryIcon(60), Countdown.BatteryIcon.RED);
        Check.t("battery icon 30m", Countdown.batteryIcon(30), Countdown.BatteryIcon.RED);
        Check.t("battery icon 0m", Countdown.batteryIcon(0), Countdown.BatteryIcon.RED);

        Check.done("CountdownTest");
    }
}
