package com.sambok;

/**
 * The last readings the car's binder services delivered.
 *
 * <p>A plain holder: {@link CarClient} writes it from the callback threads and the screen reads
 * it, so every field is volatile. The values start unknown (NaN / {@code null}) and stay that
 * way until a callback carries them; nothing here guesses a value.
 */
public final class CarState {

    /** A reading older than this is stale; the screen stops trusting it. */
    static final long FRESH_MS = 30_000;

    /** Cabin air temperature in celsius; {@code NaN} when unknown. */
    public volatile double cabinCelsius = Double.NaN;

    /** Whether the car shows temperatures in celsius; the car's setting, read once per session. */
    public volatile boolean isCelsius;

    /** Outside air temperature in celsius; {@code NaN} when unknown. */
    public volatile double ambientCelsius = Double.NaN;

    /** Outside air temperature in fahrenheit; {@code NaN} when unknown. */
    public volatile double ambientFahrenheit = Double.NaN;

    /**
     * Driver climate set-point as the car sends it in celsius; {@code NaN} when unknown. The car
     * sends the fahrenheit value separately and rounds each itself, so both are kept rather than
     * converted.
     */
    public volatile double setpointCelsius = Double.NaN;

    /** Driver climate set-point as the car sends it in fahrenheit; {@code NaN} when unknown. */
    public volatile double setpointFahrenheit = Double.NaN;

    /** Passenger climate set-point as the car sends it in celsius; {@code NaN} when unknown. */
    public volatile double passengerSetpointCelsius = Double.NaN;

    /** Passenger climate set-point as the car sends it in fahrenheit; {@code NaN} when unknown. */
    public volatile double passengerSetpointFahrenheit = Double.NaN;

    /**
     * Battery state of charge in percent (0.0..100.0, scaled from {@code HEVFlag.DISPLAY_SOC} via
     * {@link Estimate#socPercent}); {@code NaN} when unknown.
     */
    public volatile double socPercent = Double.NaN;

    /**
     * Discharge cutoff state of charge in percent (20.0..80.0, scaled from
     * {@code HEVFlag.CHRG_V2XDISCHRGLIMSOCVAL_SYNC} via {@link Estimate#cutoffPercent}); defaults
     * to {@link Estimate#DEFAULT_CUTOFF_PERCENT} (20.0%) until the car reports a reading.
     */
    public volatile double cutoffPercent = Estimate.DEFAULT_CUTOFF_PERCENT;

    /** What Utility Mode is drawing now in watts; {@code NaN} when unknown. */
    public volatile double drawW = Double.NaN;
    /**
     * Climate draw in watts (scaled from {@code HEVFlag.DATC_CLMTPWRCNSMPTN_W} at 20 W/LSB via
     * {@link Estimate#climateWatts}); {@code NaN} when unknown.
     */
    public volatile double climateW = Double.NaN;
    /**
     * Everything else drawing on the pack in watts (scaled from {@code HEVFlag.PWR_OTHER_ECU} at
     * 10 W/LSB via {@link Estimate#otherWatts}); {@code NaN} when unknown.
     */
    public volatile double otherW = Double.NaN;
    /**
     * Total V2L discharge in watts (scaled from {@code HEVFlag.VCMS_PWRDIS} at 100 W/LSB via
     * {@link Estimate#dischargeWatts}); {@code NaN} when unknown or inactive ({@code 0x7fff}).
     */
    public volatile double dischargeW = Double.NaN;
    /** The car's own estimate of remaining discharge time, in minutes; -1 when it has none. */
    public volatile int remainingMin = -1;

    /** Utility Mode state; {@code null} when unknown. */
    public volatile Boolean utilityOn;

    /** Whether the car is in EV READY mode ({@code HEVFlag.EV_RDY}); {@code null} when unknown. */
    public volatile Boolean evReady;

    /** Whether the transmission is in Park ({@code GearStatus.P}); {@code null} when unknown. */
    public volatile Boolean inPark;

    /** Whether the car reports that it is actively charging (which blocks Utility Mode). */
    public volatile boolean charging;

    /**
     * Maps the car's Utility Mode flag to a tri-state.
     *
     * <p>Measured on the unit: <b>1 is off, 2 is on</b>. Reading it as a boolean makes the screen
     * claim the opposite of the car, which is what the first on-car run showed.
     */
    public static Boolean utilityFromFlag(int value) {
        if (value == 2) {
            return Boolean.TRUE;
        }
        if (value == 1) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Maps {@code HEVFlag.EV_RDY} (flag 44) to a tri-state READY indicator.
     *
     * <p>Verified in {@code automotive-service.odex} ({@code HEVService}),
     * {@code HKMC_TAppEV3.odex} ({@code updateEvRdy}), and on-car probe: {@code 1} is READY,
     * {@code 0} is off, and {@code -1} is uninitialized.
     */
    public static Boolean evReadyFromFlag(int value) {
        if (value == 1) {
            return Boolean.TRUE;
        }
        if (value == 0) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Maps {@code SystemProtocol$GearStatus} from {@code ISystemListener.onGearStatus} to a
     * tri-state Park indicator.
     *
     * <p>In {@code automotive.odex} ({@code com.hkmc.micom.protocol.SystemProtocol$GearStatus}),
     * {@code P = 0}, {@code D = 5}, {@code N = 6}, {@code R = 7}, and {@code NONE = 0xff}.
     */
    public static Boolean inParkFromGear(int gear) {
        if (gear == 0) {
            return Boolean.TRUE;
        }
        if (gear == 5 || gear == 6 || gear == 7) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Whether the car is both in READY mode and in Park ({@code P}).
     *
     * <p>Returns {@link Boolean#TRUE} when both {@link #evReady} and {@link #inPark} are true,
     * {@link Boolean#FALSE} when either is known to be false, or {@code null} when neither is
     * false and at least one is still unknown.
     */
    public Boolean isReadyAndParked() {
        if (Boolean.FALSE.equals(evReady) || Boolean.FALSE.equals(inPark)) {
            return Boolean.FALSE;
        }
        if (Boolean.TRUE.equals(evReady) && Boolean.TRUE.equals(inPark)) {
            return Boolean.TRUE;
        }
        return null;
    }

    /**
     * Determines whether the car is charging from its {@code HEVService} charging flags.
     *
     * <p>Matches {@code HKMC_TAppEV3.odex} ({@code com.hkmc.daudio.app.ev3.data.a.t.hL/hJ/hK}) and
     * {@code HKMC_Navi_ExSL.odex} ({@code com.mnsoft.navi.ao}):
     * <ul>
     *   <li>When {@code HEVFlag.VCMS_RDY} (flag 60) is {@code 1} (CAN DB Gen 2 + VCMS), the head
     *       unit ignores {@code CHG_STS} ("3rd CAN+VCMS. Ignore CHG_STS signal") and checks
     *       {@code HEVFlag.VCMS_CHGALARM_STA} (flag 62): values {@code 2} (AC), {@code 3} (DC),
     *       {@code 7}, {@code 8}, {@code 10}, and {@code 11} indicate charging; {@code 0} is idle.
     *   <li>When {@code VCMS_RDY != 1}, {@code HEVFlag.CHG_STS} (flag 29) {@code == 1} (or a known
     *       active {@code VCMS_CHGALARM_STA} value) indicates charging.
     * </ul>
     */
    public static boolean isCharging(int vcmsReady, int vcmsChargeAlarm, int chgSts) {
        boolean vcmsCharging = vcmsChargeAlarm == 2
                || vcmsChargeAlarm == 3
                || vcmsChargeAlarm == 7
                || vcmsChargeAlarm == 8
                || vcmsChargeAlarm == 10
                || vcmsChargeAlarm == 11;
        if (vcmsReady == 1) {
            return vcmsCharging;
        }
        return vcmsCharging || chgSts == 1;
    }

    /** `SystemClock.uptimeMillis()` of the last callback. Pass the same clock to isFresh(). */
    public volatile long lastUpdateMs;

    public CarState(long nowMs) {
        this.lastUpdateMs = nowMs;
    }

    /** True while the last callback is less than {@link #FRESH_MS} before {@code nowMs}. */
    public boolean isFresh(long nowMs) {
        return nowMs - lastUpdateMs < FRESH_MS;
    }
}
