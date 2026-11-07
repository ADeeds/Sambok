package com.sambok;

/**
 * Pure calculation helpers for energy draw, state of charge, and time estimates.
 *
 * <p>Separated from the Android activity so the logic can be thoroughly unit-tested on the host
 * workstation without Android runtime dependencies.
 *
 * <h3>How the raw {@code HEVFlag} units were verified (breadcrumbs for future vehicles)</h3>
 *
 * <p>The head unit's stock EV app ({@code /system/app/HKMC_TAppEV3.odex}) reads the same
 * {@code com.hkmc.hev.HEVManager.getHEVStatus(int)} flags and formats them for the
 * <b>EV &rarr; Energy Information &rarr; Electricity Use</b> screen. To inspect an {@code .odex}
 * from a head unit dump:
 * <ol>
 *   <li>Disassemble with {@code baksmali d HKMC_TAppEV3.odex -o tap-ev3-smali} (or strip the
 *       40-byte {@code dey\n036\0} header at the start of the file to get the embedded DEX).</li>
 *   <li>Look up {@code com.hkmc.daudio.app.ev3.a.f.cu()}, which populates
 *       {@code com.hkmc.daudio.app.ev3.data.ElectricPowerData} (formatted as {@code "%.2f"} kW in
 *       {@code com.hkmc.daudio.app.ev3.ui.h.a.jr()}).</li>
 *   <li>On the car, {@code logcat} also prints {@code D/TAppEV3} lines such as
 *       {@code isSameData() PwrConCli}, {@code PwrConOther}, and {@code HEVFlag.DISPLAY_SOC}
 *       directly alongside raw {@code onHEVStatusChanged} flag values.</li>
 * </ol>
 *
 * <p>In {@code f.cu()}, the EV app branches on {@code HEVManager.getCanDBGenerationInfo()}:
 * <ul>
 *   <li><b>CAN DB Gen &ge; 2</b> (this vehicle logs {@code CAN DB Gen: 2}):
 *     <ul>
 *       <li><b>Climate ({@code PwrConCli})</b>: {@code HEVFlag.DATC_CLMTPWRCNSMPTN_W} (flag 79 /
 *           {@code 0x4f}) &rarr; {@code (raw * 2) / 100.0} kW = <b>20 W per LSB</b>.</li>
 *       <li><b>Electronics ({@code PwrConOther})</b>: {@code HEVFlag.PWR_OTHER_ECU} (flag 36 /
 *           {@code 0x24}) &rarr; {@code raw / 100.0} kW = <b>10 W per LSB</b>.</li>
 *       <li><b>Battery Care ({@code PwrConBat})</b>: {@code (flag51 * 2 + flag67 * 10) / 100.0}
 *           kW (flag 51 is 20 W/LSB, flag 67 is 100 W/LSB).</li>
 *       <li><b>Drivetrain ({@code PwrConMot})</b>: flags 69 ({@code 0x45}) and 70 ({@code 0x46}),
 *           10-bit signed values summed in kW.</li>
 *     </ul>
 *   </li>
 *   <li><b>CAN DB Gen &lt; 2</b> (older vehicles):
 *     <ul>
 *       <li><b>Climate</b>: {@code (getHEVStatus(0x2a) + getLastEconomicChargeTime()[10]
 *           - getHEVStatus(0x33) * 4) / 100.0} kW.</li>
 *       <li><b>Electronics</b>: {@code getHEVStatus(0x24) / 100.0} kW (still <b>10 W per LSB</b>).</li>
 *     </ul>
 *   </li>
 *   <li><b>V2L / Total Discharge</b> ({@code com.hkmc.daudio.app.ev3.ui.energy_information.e.jd()}):
 *       {@code HEVFlag.VCMS_PWRDIS} (flag 108 / {@code 0x6c}) is a 16-bit signed value where
 *       {@code 0x7fff} ({@code 32767}) is the invalid sentinel ({@code "-- kW"}); valid positive
 *       values are {@code raw / 10.0} kW = <b>100 W per LSB</b>.</li>
 *   <li><b>Display SOC</b> ({@code com.hkmc.daudio.app.ev3.a.f}, {@code DISPLAY_SOC} handler):
 *       {@code HEVFlag.DISPLAY_SOC} (flag 25 / {@code 0x19}) has invalid sentinel {@code 0xff}
 *       ({@code 255}); valid values {@code 0 <= raw < 255} are multiplied by {@code 0.5f} =
 *       <b>0.5% per LSB</b> (e.g. raw {@code 46} = {@code 23.0%}). The V2X discharge limit flag
 *       {@code CHRG_V2XDISCHRGLIMSOCVAL_SYNC} uses the same {@code 0.5%} step ({@code 40} =
 *       {@code 20%}).</li>
 * </ul>
 */
public final class Estimate {

    /**
     * Watts per LSB for {@code HEVFlag.DATC_CLMTPWRCNSMPTN_W} (flag 79):
     * {@code (raw * 2) / 100.0} kW = 20 W.
     */
    public static final double CLIMATE_W_SCALE = 20.0;

    /**
     * Watts per LSB for {@code HEVFlag.PWR_OTHER_ECU} (flag 36):
     * {@code raw / 100.0} kW = 10 W.
     */
    public static final double OTHER_W_SCALE = 10.0;

    /**
     * Watts per LSB for {@code HEVFlag.VCMS_PWRDIS} (flag 108):
     * {@code raw / 10.0} kW = 100 W.
     */
    public static final double DISCHARGE_W_SCALE = 100.0;

    /** Invalid sentinel for {@code HEVFlag.VCMS_PWRDIS} (flag 108): {@code 0x7fff} (32767). */
    public static final int DISCHARGE_INVALID = 0x7fff;

    /** Percent per LSB for {@code HEVFlag.DISPLAY_SOC} (flag 25): {@code raw * 0.5}% (0..200). */
    public static final double SOC_SCALE = 0.5;

    /** Invalid sentinel for {@code HEVFlag.DISPLAY_SOC} (flag 25): {@code 0xff} (255). */
    public static final int SOC_INVALID = 0xff;

    /**
     * Default discharge limit in percent when {@code HEVFlag.CHRG_V2XDISCHRGLIMSOCVAL_SYNC} is
     * uninitialized or outside {@code [20.0, 80.0]}. Matches {@code HKMC_TAppEV3.odex}
     * ({@code f.cv()}).
     */
    public static final double DEFAULT_CUTOFF_PERCENT = 20.0;

    /** Minimum valid discharge cutoff in percent in {@code HKMC_TAppEV3.odex} ({@code f.cv()}). */
    private static final double MIN_CUTOFF_PERCENT = 20.0;

    /** Maximum valid discharge cutoff in percent in {@code HKMC_TAppEV3.odex} ({@code f.cv()}). */
    private static final double MAX_CUTOFF_PERCENT = 80.0;

    /**
     * Preset discharge cutoff percentages supported by the head unit's EV V2X/Utility discharge
     * limit slider ({@code com.hkmc.daudio.app.ev3.ui.eco_discharge_v2x.g}: 7 steps of 10% from
     * {@code 20%} to {@code 80%}).
     */
    public static final int[] CUTOFF_PRESETS_PERCENT = {20, 30, 40, 50, 60, 70, 80};

    /** Runtime threshold in minutes below which the pre-start runtime is highlighted in red. */
    public static final int LOW_RUNTIME_MINUTES = 60;

    /** Maximum plausible remaining minutes from the car's own estimator (7 days). */
    private static final int MAX_PLAUSIBLE_MINUTES = 7 * 24 * 60;

    private Estimate() {
    }

    /**
     * Converts a raw {@code HEVFlag.DISPLAY_SOC} (flag 25) reading to percent (0.0..100.0), or
     * {@link Double#NaN} when negative or equal to/above the {@link #SOC_INVALID} (255) sentinel.
     */
    public static double socPercent(int raw) {
        if (raw < 0 || raw >= SOC_INVALID) {
            return Double.NaN;
        }
        return raw * SOC_SCALE;
    }

    /**
     * Converts a raw {@code HEVFlag.CHRG_V2XDISCHRGLIMSOCVAL_SYNC} (flag 76) reading to a
     * discharge cutoff in percent (20.0..80.0), or {@link #DEFAULT_CUTOFF_PERCENT} (20.0%) when
     * outside {@code [20.0, 80.0]}.
     *
     * <p>In {@code HKMC_TAppEV3.odex} ({@code com.hkmc.daudio.app.ev3.a.f.cv()}), flag 76 is
     * scaled by {@code 0.5f} (0.5% per LSB, e.g. {@code 40} = {@code 20.0%}, {@code 160} =
     * {@code 80.0%}) and clamped/reset to {@code 20.0%} if outside {@code [20.0, 80.0]}.
     */
    public static double cutoffPercent(int raw) {
        double pct = raw * SOC_SCALE;
        if (pct < MIN_CUTOFF_PERCENT || pct > MAX_CUTOFF_PERCENT) {
            return DEFAULT_CUTOFF_PERCENT;
        }
        return pct;
    }

    /**
     * Converts a discharge cutoff percentage ({@code 20..80}) to the raw {@code 0.5%}-step value
     * ({@code 40..160}) expected by {@code IHEVService.setV2XDischrgLimSocVal(int)}
     * ({@code com.hkmc.daudio.app.ev3.a.a.c.i(0x34, percent)}). Clamps out-of-range values to
     * {@code [20, 80]}.
     */
    public static int cutoffRawFromPercent(int percent) {
        double clamped = Math.max(MIN_CUTOFF_PERCENT, Math.min(MAX_CUTOFF_PERCENT, percent));
        return (int) Math.round(clamped / SOC_SCALE);
    }

    /**
     * Returns the rounded usable state of charge percentage above the discharge limit
     * ({@code max(0, round(socPercent) - round(cutoffPercent))}), or {@code -1} when either input
     * is unknown.
     */
    public static int usableSocPercent(double socPercent, double cutoffPercent) {
        if (Double.isNaN(socPercent) || Double.isInfinite(socPercent)
                || Double.isNaN(cutoffPercent) || Double.isInfinite(cutoffPercent)) {
            return -1;
        }
        int soc = (int) Math.round(socPercent);
        int cutoff = (int) Math.round(cutoffPercent);
        return Math.max(0, soc - cutoff);
    }

    /**
     * Returns {@code true} when a known runtime estimate in minutes is strictly less than 1 hour
     * ({@code 0 <= minutes < 60}).
     */
    public static boolean isLowRuntime(int minutes) {
        return minutes >= 0 && minutes < LOW_RUNTIME_MINUTES;
    }

    /**
     * Converts a raw {@code HEVFlag.DATC_CLMTPWRCNSMPTN_W} (flag 79) reading to watts, or
     * {@link Double#NaN} when negative.
     */
    public static double climateWatts(int raw) {
        return raw < 0 ? Double.NaN : raw * CLIMATE_W_SCALE;
    }

    /**
     * Converts a raw {@code HEVFlag.PWR_OTHER_ECU} (flag 36) reading to watts, or
     * {@link Double#NaN} when negative.
     */
    public static double otherWatts(int raw) {
        return raw < 0 ? Double.NaN : raw * OTHER_W_SCALE;
    }

    /**
     * Converts a raw {@code HEVFlag.VCMS_PWRDIS} (flag 108) reading to watts, or
     * {@link Double#NaN} when negative, equal to the {@link #DISCHARGE_INVALID} ({@code 0x7fff})
     * sentinel, or negative in 16-bit two's-complement ({@code 0x8000..0xffff}).
     */
    public static double dischargeWatts(int raw) {
        if (raw < 0 || raw >= DISCHARGE_INVALID) {
            return Double.NaN;
        }
        return raw * DISCHARGE_W_SCALE;
    }

    /**
     * Resolves the current discharge power in watts from scaled car sensor readings.
     *
     * <p>Prefers the total discharge reading ({@code dischargeW}) if it is known and positive.
     * When discharge is missing or zero (common when V2L is inactive and {@code VCMS_PWRDIS}
     * reports {@code 0x7fff}), falls back to estimating total draw from climate consumption
     * ({@code climateW}) plus auxiliary electronics ({@code otherW}) if both are known. Returns
     * {@link Double#NaN} if neither source yields a positive draw.
     *
     * @param dischargeW total pack discharge in watts, or NaN
     * @param climateW   climate draw in watts, or NaN
     * @param otherW     draw of other electronic control units in watts, or NaN
     * @return draw in watts (> 0), or {@link Double#NaN} if unknown or non-positive
     */
    public static double drawWatts(double dischargeW, double climateW, double otherW) {
        double watts;
        if (!Double.isNaN(dischargeW) && dischargeW > 0) {
            watts = dischargeW;
        } else if (!Double.isNaN(climateW) && !Double.isNaN(otherW)) {
            watts = climateW + otherW;
        } else {
            return Double.NaN;
        }
        return watts <= 0 ? Double.NaN : watts;
    }

    /**
     * Checks whether a remaining minutes value reported by the car is plausible.
     *
     * <p>Rejects sentinels like {@code -1} (unsupported/calculating) and {@code 65535} (0xFFFF
     * uninitialized flag), as well as zero or implausibly large estimates beyond one week.
     *
     * @param remainingMin remaining minutes as reported by the car
     * @return true only if {@code 0 < remainingMin <= 7 * 24 * 60}
     */
    public static boolean plausibleCarMinutes(int remainingMin) {
        return remainingMin > 0 && remainingMin <= MAX_PLAUSIBLE_MINUTES;
    }
}
