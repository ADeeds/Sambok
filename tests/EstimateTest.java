import com.sambok.Estimate;

public final class EstimateTest {

    public static void main(String[] args) {
        // socPercent (HEVFlag.DISPLAY_SOC, flag 25: 0.5% per LSB, 255 = invalid)
        Check.t("socPercent 46 is 23%", Estimate.socPercent(46), 23.0);
        Check.t("socPercent 0 is 0%", Estimate.socPercent(0), 0.0);
        Check.t("socPercent 200 is 100%", Estimate.socPercent(200), 100.0);
        Check.t("socPercent 255 sentinel is NaN", Double.isNaN(Estimate.socPercent(255)), true);
        Check.t("socPercent -1 is NaN", Double.isNaN(Estimate.socPercent(-1)), true);

        // cutoffPercent (HEVFlag.CHRG_V2XDISCHRGLIMSOCVAL_SYNC, flag 76: 0.5% per LSB, 20..80%)
        Check.t("cutoffPercent 40 is 20%", Estimate.cutoffPercent(40), 20.0);
        Check.t("cutoffPercent 60 is 30%", Estimate.cutoffPercent(60), 30.0);
        Check.t("cutoffPercent 160 is 80%", Estimate.cutoffPercent(160), 80.0);
        Check.t("cutoffPercent 39 (<20%) falls back to 20%", Estimate.cutoffPercent(39), 20.0);
        Check.t("cutoffPercent 161 (>80%) falls back to 20%", Estimate.cutoffPercent(161), 20.0);
        Check.t("cutoffPercent 0 falls back to 20%", Estimate.cutoffPercent(0), 20.0);
        Check.t("cutoffPercent 255 falls back to 20%", Estimate.cutoffPercent(255), 20.0);

        // climateWatts (HEVFlag.DATC_CLMTPWRCNSMPTN_W, flag 79: 20 W per LSB)
        Check.t("climateWatts 14 is 280W", Estimate.climateWatts(14), 280.0);
        Check.t("climateWatts 15 is 300W", Estimate.climateWatts(15), 300.0);
        Check.t("climateWatts 0 is 0W", Estimate.climateWatts(0), 0.0);
        Check.t("climateWatts -1 is NaN", Double.isNaN(Estimate.climateWatts(-1)), true);

        // otherWatts (HEVFlag.PWR_OTHER_ECU, flag 36: 10 W per LSB)
        Check.t("otherWatts 40 is 400W", Estimate.otherWatts(40), 400.0);
        Check.t("otherWatts 41 is 410W", Estimate.otherWatts(41), 410.0);
        Check.t("otherWatts 0 is 0W", Estimate.otherWatts(0), 0.0);
        Check.t("otherWatts -1 is NaN", Double.isNaN(Estimate.otherWatts(-1)), true);

        // dischargeWatts (HEVFlag.VCMS_PWRDIS, flag 108: 100 W per LSB, 0x7fff = invalid)
        Check.t("dischargeWatts 18 is 1800W", Estimate.dischargeWatts(18), 1800.0);
        Check.t("dischargeWatts 0 is 0W", Estimate.dischargeWatts(0), 0.0);
        Check.t("dischargeWatts 32767 (0x7fff) sentinel is NaN",
                Double.isNaN(Estimate.dischargeWatts(32767)), true);
        Check.t("dischargeWatts 65535 (16-bit negative) is NaN",
                Double.isNaN(Estimate.dischargeWatts(65535)), true);
        Check.t("dischargeWatts -1 is NaN", Double.isNaN(Estimate.dischargeWatts(-1)), true);

        // drawWatts: discharge 0 falls back to climateW + otherW
        Check.t("discharge 0 falls back",
                Estimate.drawWatts(0.0, Estimate.climateWatts(14), Estimate.otherWatts(40)),
                680.0);

        // drawWatts: discharge NaN (e.g. 0x7fff sentinel) falls back to climateW + otherW
        Check.t("discharge NaN falls back",
                Estimate.drawWatts(Estimate.dischargeWatts(32767),
                        Estimate.climateWatts(15), Estimate.otherWatts(40)),
                700.0);

        // drawWatts: discharge positive wins over climateW + otherW
        Check.t("discharge positive wins",
                Estimate.drawWatts(Estimate.dischargeWatts(18),
                        Estimate.climateWatts(15), Estimate.otherWatts(40)),
                1800.0);

        // drawWatts: all unknown -> NaN
        Check.t("all unknown",
                Double.isNaN(Estimate.drawWatts(Double.NaN, Double.NaN, Double.NaN)), true);

        // drawWatts: fallback non-positive -> NaN
        Check.t("fallback non-positive",
                Double.isNaN(Estimate.drawWatts(0.0, 0.0, 0.0)), true);

        // cutoffRawFromPercent (IHEVService.setV2XDischrgLimSocVal: 0.5% per LSB, 40..160)
        Check.t("cutoffRawFromPercent 20% is 40", Estimate.cutoffRawFromPercent(20), 40);
        Check.t("cutoffRawFromPercent 30% is 60", Estimate.cutoffRawFromPercent(30), 60);
        Check.t("cutoffRawFromPercent 50% is 100", Estimate.cutoffRawFromPercent(50), 100);
        Check.t("cutoffRawFromPercent 80% is 160", Estimate.cutoffRawFromPercent(80), 160);
        Check.t("cutoffRawFromPercent clamps <20% to 40", Estimate.cutoffRawFromPercent(10), 40);
        Check.t("cutoffRawFromPercent clamps >80% to 160", Estimate.cutoffRawFromPercent(90), 160);
        Check.t("cutoff presets count", Estimate.CUTOFF_PRESETS_PERCENT.length, 7);
        Check.t("cutoff presets min", Estimate.CUTOFF_PRESETS_PERCENT[0], 20);
        Check.t("cutoff presets max", Estimate.CUTOFF_PRESETS_PERCENT[6], 80);

        // usableSocPercent: max(0, round(soc) - round(cutoff)), or -1 if unknown
        Check.t("usableSocPercent 53 - 20 = 33", Estimate.usableSocPercent(53.0, 20.0), 33);
        Check.t("usableSocPercent 20 - 20 = 0", Estimate.usableSocPercent(20.0, 20.0), 0);
        Check.t("usableSocPercent 18 - 20 = 0", Estimate.usableSocPercent(18.0, 20.0), 0);
        Check.t("usableSocPercent NaN soc is -1",
                Estimate.usableSocPercent(Double.NaN, 20.0), -1);
        Check.t("usableSocPercent NaN cutoff is -1",
                Estimate.usableSocPercent(50.0, Double.NaN), -1);

        // isLowRuntime: true when 0 <= minutes < 60 (< 1 hour)
        Check.t("isLowRuntime -1 (unknown) is false", Estimate.isLowRuntime(-1), false);
        Check.t("isLowRuntime 0 is true", Estimate.isLowRuntime(0), true);
        Check.t("isLowRuntime 30 is true", Estimate.isLowRuntime(30), true);
        Check.t("isLowRuntime 59 is true", Estimate.isLowRuntime(59), true);
        Check.t("isLowRuntime 60 is false", Estimate.isLowRuntime(60), false);
        Check.t("isLowRuntime 120 is false", Estimate.isLowRuntime(120), false);

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
