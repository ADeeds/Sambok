import com.sambok.Estimate;

public final class EstimateTest {

    public static void main(String[] args) {
        // drawWatts: discharge 0 falls back to climate + other
        Check.t("discharge 0 falls back", Estimate.drawWatts(0.0, 15.0, 10.0, 100.0), 250.0);

        // drawWatts: discharge NaN falls back to climate + other
        Check.t("discharge NaN falls back", Estimate.drawWatts(Double.NaN, 15.0, 10.0, 100.0), 250.0);

        // drawWatts: discharge positive wins over climate + other
        Check.t("discharge positive wins", Estimate.drawWatts(500.0, 15.0, 10.0, 100.0), 500.0);

        // drawWatts: all unknown -> NaN
        Check.t("all unknown", Double.isNaN(Estimate.drawWatts(Double.NaN, Double.NaN, 10.0, Double.NaN)), true);

        // drawWatts: fallback non-positive -> NaN
        Check.t("fallback non-positive", Double.isNaN(Estimate.drawWatts(0.0, -10.0, 10.0, 50.0)), true);

        // plausibleCarMinutes: -1, 0, 1, 10080, 10081, 65535
        Check.t("plausibleCarMinutes -1", Estimate.plausibleCarMinutes(-1), false);
        Check.t("plausibleCarMinutes 0", Estimate.plausibleCarMinutes(0), false);
        Check.t("plausibleCarMinutes 1", Estimate.plausibleCarMinutes(1), true);
        Check.t("plausibleCarMinutes 10080", Estimate.plausibleCarMinutes(10080), true);
        Check.t("plausibleCarMinutes 10081", Estimate.plausibleCarMinutes(10081), false);
        Check.t("plausibleCarMinutes 65535", Estimate.plausibleCarMinutes(65535), false);

        Check.done("EstimateTest");
    }
}
