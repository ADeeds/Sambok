import com.sambok.Countdown;

public final class CountdownTest {

    public static void main(String[] args) {
        Check.t("one hour", Countdown.format(60), "\u2248 1 h 0 m");
        Check.t("ten min rounding", Countdown.format(70), "\u2248 1 h 10 m");
        Check.t("over a day", Countdown.format(1500), "> 1 day");
        Check.t("zero", Countdown.format(0), "0");
        Check.t("unknown", Countdown.format(-1), "\u2014");

        Check.t("no samples", Countdown.remainingMinutes(80, 20, 77.4), -1);

        // 60% of a 77.4 kWh pack is 46.44 kWh; at 1 kW that is 2786.4 minutes, rounded to 2790.
        Countdown.sample(1.0);
        Countdown.sample(1.0);
        Countdown.sample(1.0);
        Check.t("steady 1 kW", Countdown.remainingMinutes(80, 20, 77.4), 2790);

        // Three more samples at 3 kW: the mean must sit between 1 and 3 kW, not at either end.
        // Steady 3 kW would be 928.8 minutes, rounded to 930.
        Countdown.sample(3.0);
        Countdown.sample(3.0);
        Countdown.sample(3.0);
        int smoothed = Countdown.remainingMinutes(80, 20, 77.4);
        Check.t("smoothed below 1 kW", smoothed < 2790, true);
        Check.t("smoothed above 3 kW", smoothed > 930, true);

        Check.t("unknown soc", Countdown.remainingMinutes(Double.NaN, 20, 77.4), -1);
        Check.t("unknown cutoff", Countdown.remainingMinutes(80, Double.NaN, 77.4), -1);
        Check.t("unknown capacity", Countdown.remainingMinutes(80, 20, Double.NaN), -1);
        Check.t("at cutoff", Countdown.remainingMinutes(20, 20, 77.4), 0);
        Check.t("below cutoff", Countdown.remainingMinutes(10, 20, 77.4), 0);

        Check.done("CountdownTest");
    }
}
