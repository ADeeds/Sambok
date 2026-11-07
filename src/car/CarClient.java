package com.sambok;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * The car boundary: binds the climate and EV binder services and keeps {@link CarState} current.
 *
 * <p>Every reading arrives as a binder callback, never as a getter, so the app registers a
 * listener on each service and decodes the callbacks itself. This class holds all binder access
 * and nothing else. The only thing it ever writes to the car is Utility Mode, through
 * {@link #setUtilityOn}.
 *
 * <p>Transaction codes, interface descriptors, the temperature pair order and the HEV status ids
 * were read off this unit's own framework (automotive.odex / automotive-service.odex) and are
 * recorded next to the constants below.
 */
public final class CarClient {

    private static final String TAG = "Sambok";

    private static final String DATC_SERVICE = "com.hkmc.micom.datc";
    private static final String HEV_SERVICE = "com.hkmc.micom.hev";
    private static final String SYSTEM_SERVICE = "com.hkmc.micom.system";
    private static final String BCM_SERVICE = "com.hkmc.micom.bcm";

    private static final String DATC_DESCRIPTOR = "com.hkmc.datc.IDATCService";
    private static final String DATC_LISTENER_DESCRIPTOR = "com.hkmc.datc.IDATCListener";
    private static final String HEV_DESCRIPTOR = "com.hkmc.hev.IHEVService";
    private static final String HEV_LISTENER_DESCRIPTOR = "com.hkmc.hev.IHEVListener";
    private static final String SYSTEM_DESCRIPTOR = "com.hkmc.micom.ISystemService";
    private static final String SYSTEM_LISTENER_DESCRIPTOR = "com.hkmc.micom.ISystemListener";
    private static final String BCM_DESCRIPTOR = "com.hkmc.bcm.IBCMService";

    /** {@code IDATCService$Stub.TRANSACTION_registerDATCListener}. */
    private static final int DATC_REGISTER_LISTENER = 1;
    /** {@code IDATCService$Stub.TRANSACTION_unregisterDATCListener}. */
    private static final int DATC_UNREGISTER_LISTENER = 2;
    /** {@code IDATCService$Stub.TRANSACTION_getTemperatureUnit}. */
    private static final int DATC_GET_TEMPERATURE_UNIT = 3;
    /** {@code IDATCListener$Stub.TRANSACTION_onTemperatureInformation}. */
    private static final int DATC_ON_TEMPERATURE = 1;
    /** {@code IDATCListener$Stub.TRANSACTION_onDatcStatus}. */
    private static final int DATC_ON_STATUS = 3;

    /** {@code IHEVService$Stub.TRANSACTION_addListener}. */
    private static final int HEV_ADD_LISTENER = 1;
    /** {@code IHEVService$Stub.TRANSACTION_removeListener}. */
    private static final int HEV_REMOVE_LISTENER = 2;
    /** {@code IHEVService$Stub.TRANSACTION_setV2XDischrgLimSocVal}. */
    private static final int HEV_SET_DISCHARGE_LIMIT_SOC = 57;
    /** {@code IHEVService$Stub.TRANSACTION_setUtilModeSet}. */
    private static final int HEV_SET_UTIL_MODE = 58;
    /** {@code IHEVListener$Stub.TRANSACTION_onHEVStatusChanged}. */
    private static final int HEV_ON_STATUS = 1;
    /** {@code IHEVListener$Stub.TRANSACTION_onPwrConsumptionChanged}. */
    private static final int HEV_ON_POWER = 14;

    /** {@code ISystemService$Stub.TRANSACTION_addListener}. */
    private static final int SYSTEM_ADD_LISTENER = 1;
    /** {@code ISystemService$Stub.TRANSACTION_removeListener}. */
    private static final int SYSTEM_REMOVE_LISTENER = 2;
    /** {@code ISystemListener$Stub.TRANSACTION_onGearStatus}. */
    private static final int SYSTEM_ON_GEAR_STATUS = 17;
    /** Highest oneway callback transaction code on {@code ISystemListener$Stub} (1..31). */
    private static final int SYSTEM_MAX_CALLBACK = 31;

    /** {@code IBCMService$Stub.TRANSACTION_lockDoor}: int 0 = unlock, int 1 = lock. */
    private static final int BCM_LOCK_DOOR = 5;

    /** {@code DATCFlag$DATCConstants.TEMP_UNIT_C}. */
    private static final int UNIT_CELSIUS = 1;
    /** {@code DATCFlag$DATCConstants.TEMP_UNIT_F}. */
    private static final int UNIT_FAHRENHEIT = 2;
    /**
     * {@code DATCConstants$TemperatureDisplayState.NORMAL}: the temperature display slot is active
     * and valid. Blank or inactive slots (such as the passenger zone when HVAC is in Driver Only
     * mode) carry {@code TemperatureDisplayState.INVALID} ({@code 0xff = 255}) while retaining the
     * last float temperature in {@code DatcStatus}.
     */
    private static final int TEMP_STATE_NORMAL = 0xffff;

    /**
     * {@code HEVManager$HEVFlag.DISPLAY_SOC}: battery state of charge in 0.5% steps (0..200 =
     * 0..100%; 255 = invalid). Scaled via {@link Estimate#socPercent}.
     */
    private static final int FLAG_DISPLAY_SOC = 25;
    /** {@code HEVFlag.CHG_STS}: legacy charging status (1 = charging, 0 = not charging). */
    private static final int FLAG_CHG_STS = 29;
    /**
     * {@code HEVFlag.PWR_OTHER_ECU}: non-climate electronics load in 10 W (0.01 kW) steps. Scaled
     * via {@link Estimate#otherWatts}.
     */
    private static final int FLAG_OTHER_W = 36;
    /** {@code HEVFlag.EV_RDY}: 1 when the EV is in READY mode, 0 when off. */
    private static final int FLAG_EV_RDY = 44;
    /** {@code HEVFlag.VCMS_RDY}: 1 when the car uses VCMS charging telemetry (CAN DB Gen 2). */
    private static final int FLAG_VCMS_RDY = 60;
    /**
     * {@code HEVFlag.VCMS_CHGALARM_STA}: VCMS charging status (0 = idle; 2, 3, 7, 8, 10, 11 =
     * charging). Decoded via {@link CarState#isCharging}.
     */
    private static final int FLAG_VCMS_CHG_ALARM = 62;
    /**
     * {@code HEVFlag.DATC_CLMTPWRSUM_W}: cumulative climate energy consumption in Wh
     * ({@code mFdClmtPwrSumWh}).
     */
    private static final int FLAG_CLIMATE_SUM_W = 66;
    /**
     * {@code HEVFlag.CHRG_V2XDISCHRGLIMSOCVAL_SYNC}: discharge limit SOC in 0.5% steps (40..160 =
     * 20..80%). Scaled via {@link Estimate#cutoffPercent}.
     */
    private static final int FLAG_DISCHARGE_LIMIT_SOC = 76;
    /**
     * {@code HEVFlag.DATC_CLMTPWRCNSMPTN_W}: instantaneous climate consumption in 20 W (0.02 kW)
     * steps. Scaled via {@link Estimate#climateWatts}.
     */
    private static final int FLAG_CLIMATE_W = 79;
    /** {@code HEVFlag.VCMS_V2L_EST_DISCHG_RMNTIME}: the car's own remaining-time estimate. */
    private static final int FLAG_V2L_REMAIN = 106;
    /**
     * {@code HEVFlag.VCMS_PWRDIS}: total V2L discharge in 100 W (0.1 kW) steps (16-bit signed;
     * 0x7fff = 32767 is invalid). Scaled via {@link Estimate#dischargeWatts}.
     */
    private static final int FLAG_DISCHARGE_W = 108;
    /** {@code IHEVService.getHEVStatus}: one int id in, one int out. */
    private static final int HEV_GET_STATUS = 3;
    /** {@code HEVManager$HEVFlag.VCU_UTILMODUSMSTA}: Utility Mode on/off. */
    private static final int FLAG_UTIL_MODE_STATE = 80;
    /** {@code HEVFlag.VCU_UTILMODWRNMSGSTA}: VCU warning when Utility Mode conditions are not met. */
    private static final int FLAG_UTIL_MODE_WARN = 81;

    /**
     * The eight (value, state) pairs of {@code onTemperatureInformation}, in the order the DATC
     * service sends them: driver celsius, driver fahrenheit, passenger celsius, passenger
     * fahrenheit, outside celsius, outside fahrenheit, rear celsius, rear fahrenheit. All of them
     * are climate set-points or the outside sensor; this unit exposes no cabin reading through
     * any callback, so {@link CarState#cabinCelsius} stays unknown.
     */
    private static final int PAIRS = 8;
    private static final int PAIR_AMBIENT_CELSIUS = 4;
    private static final int PAIR_AMBIENT_FAHRENHEIT = 5;
    private static final int PAIR_DRIVER_CELSIUS = 0;
    private static final int PAIR_DRIVER_FAHRENHEIT = 1;
    private static final int PAIR_PASSENGER_CELSIUS = 2;
    private static final int PAIR_PASSENGER_FAHRENHEIT = 3;

    /** How long a Utility Mode write waits for the car to report the new state. */
    private static final long CONFIRM_MS = 1500;

    /** Argument marker for a transaction that carries no int. */
    private static final int NO_ARG = Integer.MIN_VALUE;

    /** How long to wait before attempting to re-bind a dead service. */
    private static final long REBIND_INTERVAL_MS = 5000L;

    private final CarState state = new CarState(0L);
    private final Object lock = new Object();
    private final Object refreshLock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable repaint = new Runnable() {
        @Override
        public void run() {
            Runnable callback;
            synchronized (lock) {
                repaintPending = false;
                callback = onChange;
            }
            if (callback != null) {
                callback.run();
            }
        }
    };

    private Runnable onChange;
    private boolean repaintPending;
    private boolean refreshPending;
    private volatile boolean unbound;

    private volatile HandlerThread workerThread;
    private volatile Handler workerHandler;

    private volatile IBinder datc;
    private volatile IBinder hev;
    private volatile IBinder system;
    private volatile IBinder bcm;
    private volatile Binder datcListener;
    private volatile Binder hevListener;
    private volatile Binder systemListener;
    private final IBinder token = new Binder();

    private volatile int vcmsRdy;
    private volatile int vcmsChgAlarm;
    private volatile int chgSts;

    private final IBinder.DeathRecipient datcDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.w(TAG, "DATC service died");
            datc = null;
            datcListener = null;
            scheduleRebindDatc();
        }
    };

    private final IBinder.DeathRecipient hevDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.w(TAG, "HEV service died");
            hev = null;
            hevListener = null;
            scheduleRebindHev();
        }
    };

    private final IBinder.DeathRecipient systemDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.w(TAG, "System service died");
            system = null;
            systemListener = null;
            scheduleRebindSystem();
        }
    };

    private final IBinder.DeathRecipient bcmDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.w(TAG, "BCM service died");
            bcm = null;
            scheduleRebindBcm();
        }
    };

    private void scheduleRebindDatc() {
        if (unbound) {
            return;
        }
        Handler handler = workerHandler;
        if (handler != null) {
            handler.removeCallbacks(rebindDatcTask);
            handler.postDelayed(rebindDatcTask, REBIND_INTERVAL_MS);
        }
    }

    private void scheduleRebindHev() {
        if (unbound) {
            return;
        }
        Handler handler = workerHandler;
        if (handler != null) {
            handler.removeCallbacks(rebindHevTask);
            handler.postDelayed(rebindHevTask, REBIND_INTERVAL_MS);
        }
    }

    private void scheduleRebindSystem() {
        if (unbound) {
            return;
        }
        Handler handler = workerHandler;
        if (handler != null) {
            handler.removeCallbacks(rebindSystemTask);
            handler.postDelayed(rebindSystemTask, REBIND_INTERVAL_MS);
        }
    }

    private void scheduleRebindBcm() {
        if (unbound) {
            return;
        }
        Handler handler = workerHandler;
        if (handler != null) {
            handler.removeCallbacks(rebindBcmTask);
            handler.postDelayed(rebindBcmTask, REBIND_INTERVAL_MS);
        }
    }

    private final Runnable rebindDatcTask = new Runnable() {
        @Override
        public void run() {
            if (unbound || datc != null) {
                return;
            }
            Log.i(TAG, "attempting to re-bind " + DATC_SERVICE);
            bindDatc();
            if (datc == null) {
                scheduleRebindDatc();
            }
        }
    };

    private final Runnable rebindHevTask = new Runnable() {
        @Override
        public void run() {
            if (unbound || hev != null) {
                return;
            }
            Log.i(TAG, "attempting to re-bind " + HEV_SERVICE);
            bindHev();
            if (hev == null) {
                scheduleRebindHev();
            } else {
                refresh();
            }
        }
    };

    private final Runnable rebindSystemTask = new Runnable() {
        @Override
        public void run() {
            if (unbound || system != null) {
                return;
            }
            Log.i(TAG, "attempting to re-bind " + SYSTEM_SERVICE);
            bindSystem();
            if (system == null) {
                scheduleRebindSystem();
            }
        }
    };

    private final Runnable rebindBcmTask = new Runnable() {
        @Override
        public void run() {
            if (unbound || bcm != null) {
                return;
            }
            Log.i(TAG, "attempting to re-bind " + BCM_SERVICE);
            bindBcm();
            if (bcm == null) {
                scheduleRebindBcm();
            }
        }
    };

    private void bindDatc() {
        IBinder service = service(DATC_SERVICE);
        if (service != null) {
            try {
                service.linkToDeath(datcDeathRecipient, 0);
            } catch (RemoteException e) {
                Log.w(TAG, "DATC linkToDeath failed", e);
                scheduleRebindDatc();
                return;
            }
            datc = service;
            datcListener = new DatcListener();
            if (transactListener(service, DATC_DESCRIPTOR, DATC_REGISTER_LISTENER, datcListener,
                    token)) {
                Log.i(TAG, "registered DATC listener");
            } else {
                Log.w(TAG, "DATC listener not registered");
            }
            readTemperatureUnit();
        } else {
            Log.w(TAG, "no binder for " + DATC_SERVICE);
            scheduleRebindDatc();
        }
    }

    private void bindHev() {
        IBinder service = service(HEV_SERVICE);
        if (service != null) {
            try {
                service.linkToDeath(hevDeathRecipient, 0);
            } catch (RemoteException e) {
                Log.w(TAG, "HEV linkToDeath failed", e);
                scheduleRebindHev();
                return;
            }
            hev = service;
            hevListener = new HevListener();
            if (transactListener(service, HEV_DESCRIPTOR, HEV_ADD_LISTENER, hevListener, null)) {
                Log.i(TAG, "registered HEV listener");
            } else {
                Log.w(TAG, "HEV listener not registered");
            }
        } else {
            Log.w(TAG, "no binder for " + HEV_SERVICE);
            scheduleRebindHev();
        }
    }

    private void bindSystem() {
        IBinder service = service(SYSTEM_SERVICE);
        if (service != null) {
            try {
                service.linkToDeath(systemDeathRecipient, 0);
            } catch (RemoteException e) {
                Log.w(TAG, "System linkToDeath failed", e);
                scheduleRebindSystem();
                return;
            }
            system = service;
            systemListener = new SystemListener();
            if (transactListener(service, SYSTEM_DESCRIPTOR, SYSTEM_ADD_LISTENER, systemListener,
                    null)) {
                Log.i(TAG, "registered System listener");
            } else {
                Log.w(TAG, "System listener not registered");
            }
        } else {
            Log.w(TAG, "no binder for " + SYSTEM_SERVICE);
            scheduleRebindSystem();
        }
    }

    private void bindBcm() {
        IBinder service = service(BCM_SERVICE);
        if (service != null) {
            try {
                service.linkToDeath(bcmDeathRecipient, 0);
            } catch (RemoteException e) {
                Log.w(TAG, "BCM linkToDeath failed", e);
                scheduleRebindBcm();
                return;
            }
            bcm = service;
        } else {
            Log.w(TAG, "no binder for " + BCM_SERVICE);
            scheduleRebindBcm();
        }
    }

    /**
     * Binds the car services and registers a listener on each; {@code onChange} runs on main.
     *
     * <p>Starts a dedicated worker thread ("sambok-car") and posts the service lookup,
     * registration, unit configuration read, and initial refresh onto that worker so that
     * binder transactions never block the main thread.
     */
    public void bind(Context context, Runnable onChange) {
        // Context is not needed: system service lookups are process-wide, and the argument is part
        // of the Activity's contract.
        synchronized (lock) {
            this.onChange = onChange;
        }
        unbound = false;
        workerThread = new HandlerThread("sambok-car");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        workerHandler.post(new Runnable() {
            @Override
            public void run() {
                if (unbound) {
                    return;
                }
                bindDatc();
                bindHev();
                bindSystem();
                bindBcm();
                Log.i(TAG, "bound: " + DATC_SERVICE + "=" + (datc != null) + " " + HEV_SERVICE + "="
                        + (hev != null) + " " + SYSTEM_SERVICE + "=" + (system != null)
                        + " " + BCM_SERVICE + "=" + (bcm != null));
                refresh();
            }
        });
    }

    /**
     * Posts a {@link #refresh()} call to the worker thread, coalescing multiple requests so that
     * at most one refresh is pending in the queue at any time.
     */
    public void requestRefresh() {
        if (unbound) {
            return;
        }
        synchronized (refreshLock) {
            if (refreshPending) {
                return;
            }
            Handler handler = workerHandler;
            if (handler == null) {
                return;
            }
            refreshPending = true;
            handler.post(refreshTask);
        }
    }

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            synchronized (refreshLock) {
                refreshPending = false;
            }
            if (unbound) {
                return;
            }
            refresh();
        }
    };

    /**
     * Reads the display state of charge directly, along with power draw and Utility Mode state.
     *
     * <p>The car does not push it: registering the HEV listener delivered ids 36, 66, 73, 79 and
     * 80 on this unit, never 25, and no power-consumption callback ever arrived. The keyed getter
     * has it, so this is a read rather than a wait. Freshness is only stamped when at least one
     * read succeeds so an unresponsive or dead service does not self-renew.
     */
    public void refresh() {
        IBinder service = hev;
        if (service == null) {
            return;
        }
        Integer soc = read(service, FLAG_DISPLAY_SOC);
        Integer cutoff = read(service, FLAG_DISCHARGE_LIMIT_SOC);
        Integer climateW = read(service, FLAG_CLIMATE_W);
        Integer otherW = read(service, FLAG_OTHER_W);
        Integer dischargeW = read(service, FLAG_DISCHARGE_W);
        Integer remain = read(service, FLAG_V2L_REMAIN);
        Integer utilMode = read(service, FLAG_UTIL_MODE_STATE);
        Integer evRdy = read(service, FLAG_EV_RDY);
        Integer vcms = read(service, FLAG_VCMS_RDY);
        Integer chgAlarm = read(service, FLAG_VCMS_CHG_ALARM);
        Integer chg = read(service, FLAG_CHG_STS);

        boolean anyNonNull = (soc != null) || (cutoff != null) || (climateW != null)
                || (otherW != null) || (dischargeW != null) || (remain != null)
                || (utilMode != null) || (evRdy != null) || (vcms != null)
                || (chgAlarm != null) || (chg != null);

        if (!anyNonNull) {
            Log.w(TAG, "refresh failed: all reads returned null");
            return;
        }

        Log.i(TAG, "read raw soc=" + soc + " cutoff=" + cutoff + " climate=" + climateW
                + " other=" + otherW + " discharge=" + dischargeW + " remainMin=" + remain
                + " utilMode=" + utilMode + " evRdy=" + evRdy + " vcmsRdy=" + vcms
                + " chgAlarm=" + chgAlarm + " chgSts=" + chg);
        if (soc != null) {
            state.socPercent = Estimate.socPercent(soc.intValue());
        }
        if (cutoff != null) {
            state.cutoffPercent = Estimate.cutoffPercent(cutoff.intValue());
        }
        if (climateW != null) {
            state.climateW = Estimate.climateWatts(climateW.intValue());
        }
        if (otherW != null) {
            state.otherW = Estimate.otherWatts(otherW.intValue());
        }
        if (dischargeW != null) {
            state.dischargeW = Estimate.dischargeWatts(dischargeW.intValue());
        }
        if (remain != null) {
            state.remainingMin = remain.intValue();
        }
        if (utilMode != null) {
            Boolean utility = CarState.utilityFromFlag(utilMode.intValue());
            if (utility != null) {
                state.utilityOn = utility;
            }
        }
        if (evRdy != null) {
            Boolean ready = CarState.evReadyFromFlag(evRdy.intValue());
            if (ready != null) {
                state.evReady = ready;
            }
        }
        if (vcms != null) {
            vcmsRdy = vcms.intValue();
        }
        if (chgAlarm != null) {
            vcmsChgAlarm = chgAlarm.intValue();
        }
        if (chg != null) {
            chgSts = chg.intValue();
        }
        state.charging = CarState.isCharging(vcmsRdy, vcmsChgAlarm, chgSts);
        touched();
    }

    private Integer read(IBinder service, int flag) {
        return transactForIntArg(service, HEV_DESCRIPTOR, HEV_GET_STATUS, flag);
    }

    public CarState state() {
        return state;
    }

    /**
     * Turns Utility Mode on or off. Nothing else is ever written to the car.
     *
     * <p>Returns true only when the car has reported the matching state, which it does through the
     * HEV status callback; a write without confirmation returns false. The wait is short so a
     * caller stays responsive; the screen's own settle window is longer.
     */
    public boolean setUtilityOn(boolean on) {
        IBinder service = hev;
        if (service == null) {
            Log.w(TAG, "no hev service: Utility Mode not written");
            return false;
        }
        // IHEVService.setUtilModeSet, transaction 58, which sends micom command 3165. Sending 2
        // requests Utility Mode on; the VCU does not support turning Utility Mode off from the
        // head unit (0 is only the "setUtilModeSet() to Default" release pulse, and exiting
        // Utility Mode requires power-cycling the car). (The reported state is 2 on, 1 off.)
        int command = on ? 2 : 0;
        if (!transact(service, HEV_DESCRIPTOR, HEV_SET_UTIL_MODE, command)) {
            return false;
        }
        Log.i(TAG, "setUtilModeSet(" + command + ") sent");
        return awaitUtility(on);
    }

    /**
     * Sets the V2X / Utility Mode discharge limit percentage ({@code 20..80%}) via
     * {@code IHEVService.setV2XDischrgLimSocVal(int)} (transaction 57, which sends micom command
     * {@code 0xc5a} in {@code 0.5%} steps).
     */
    public boolean setDischargeLimitPercent(int percent) {
        IBinder service = hev;
        if (service == null) {
            Log.w(TAG, "no hev service: discharge limit not written");
            return false;
        }
        int raw = Estimate.cutoffRawFromPercent(percent);
        boolean ok = transact(service, HEV_DESCRIPTOR, HEV_SET_DISCHARGE_LIMIT_SOC, raw);
        Log.i(TAG, "setV2XDischrgLimSocVal(" + raw + ") [" + percent + "%] -> " + ok);
        if (ok) {
            state.cutoffPercent = Estimate.cutoffPercent(raw);
            scheduleRepaint();
        }
        return ok;
    }

    /**
     * Unlocks the vehicle doors via {@code IBCMService.lockDoor(false)} ({@code C_CtrlDrUnlockRlyIBOX}).
     */
    public boolean unlockDoors() {
        IBinder service = bcm;
        if (service == null) {
            service = service(BCM_SERVICE);
            if (service != null) {
                bcm = service;
            }
        }
        if (service == null) {
            Log.w(TAG, "no bcm service: doors not unlocked");
            return false;
        }
        boolean ok = transact(service, BCM_DESCRIPTOR, BCM_LOCK_DOOR, 0);
        Log.i(TAG, "unlockDoors() -> " + ok);
        return ok;
    }

    /**
     * Unregisters both listeners, unlinks death recipients, and stops the worker thread safely.
     */
    public void unbind() {
        unbound = true;
        synchronized (lock) {
            onChange = null;
        }

        IBinder d = datc;
        if (d != null) {
            try {
                d.unlinkToDeath(datcDeathRecipient, 0);
            } catch (Exception e) {
                Log.w(TAG, "unlinkToDeath datc failed", e);
            }
        }
        IBinder h = hev;
        if (h != null) {
            try {
                h.unlinkToDeath(hevDeathRecipient, 0);
            } catch (Exception e) {
                Log.w(TAG, "unlinkToDeath hev failed", e);
            }
        }
        IBinder s = system;
        if (s != null) {
            try {
                s.unlinkToDeath(systemDeathRecipient, 0);
            } catch (Exception e) {
                Log.w(TAG, "unlinkToDeath system failed", e);
            }
        }
        IBinder b = bcm;
        if (b != null) {
            try {
                b.unlinkToDeath(bcmDeathRecipient, 0);
            } catch (Exception e) {
                Log.w(TAG, "unlinkToDeath bcm failed", e);
            }
        }

        Handler handler = workerHandler;
        HandlerThread thread = workerThread;
        if (handler != null) {
            handler.removeCallbacks(rebindDatcTask);
            handler.removeCallbacks(rebindHevTask);
            handler.removeCallbacks(rebindSystemTask);
            handler.removeCallbacks(rebindBcmTask);
            handler.post(new Runnable() {
                @Override
                public void run() {
                    doUnregister();
                }
            });
        }
        if (thread != null) {
            thread.quitSafely();
        }
        workerHandler = null;
        workerThread = null;
        Log.i(TAG, "unbound");
    }

    private void doUnregister() {
        IBinder d = datc;
        Binder dl = datcListener;
        if (d != null && dl != null) {
            transactListener(d, DATC_DESCRIPTOR, DATC_UNREGISTER_LISTENER, dl, token);
        }
        IBinder h = hev;
        Binder hl = hevListener;
        if (h != null && hl != null) {
            transactListener(h, HEV_DESCRIPTOR, HEV_REMOVE_LISTENER, hl, null);
        }
        IBinder s = system;
        Binder sl = systemListener;
        if (s != null && sl != null) {
            transactListener(s, SYSTEM_DESCRIPTOR, SYSTEM_REMOVE_LISTENER, sl, null);
        }
        datc = null;
        hev = null;
        system = null;
        bcm = null;
        datcListener = null;
        hevListener = null;
        systemListener = null;
    }

    /** Reads the car's own unit setting once per session; opens no write. */
    private void readTemperatureUnit() {
        IBinder service = datc;
        if (service == null) {
            return;
        }
        Integer unit = transactForInt(service, DATC_DESCRIPTOR, DATC_GET_TEMPERATURE_UNIT);
        if (unit == null) {
            Log.w(TAG, "temperature unit unknown");
            return;
        }
        Log.i(TAG, "temperature unit " + unit);
        if (unit.intValue() == UNIT_CELSIUS) {
            state.isCelsius = true;
        } else if (unit.intValue() == UNIT_FAHRENHEIT) {
            state.isCelsius = false;
        } else {
            Log.w(TAG, "unknown temperature unit " + unit);
        }
    }

    private boolean awaitUtility(boolean on) {
        long deadline = SystemClock.uptimeMillis() + CONFIRM_MS;
        synchronized (lock) {
            while (true) {
                Boolean current = state.utilityOn;
                if (current != null && current.booleanValue() == on) {
                    return true;
                }
                long left = deadline - SystemClock.uptimeMillis();
                if (left <= 0) {
                    Log.w(TAG, "Utility Mode not reported as " + on + " within " + CONFIRM_MS
                            + " ms");
                    return false;
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    /**
     * Records that an HEV callback arrived, stamps freshness and asks the screen to repaint.
     *
     * <p>Only the HEV callbacks stamp the watermark: the DATC listener stays registered for the
     * unit reading but repaints without refreshing it, so a chatty climate service cannot keep a
     * silent HEV looking fresh.
     */
    private void touched() {
        synchronized (lock) {
            state.lastUpdateMs = SystemClock.uptimeMillis();
            lock.notifyAll();
        }
        scheduleRepaint();
    }

    /**
     * Queues one repaint on the main thread, leaving the freshness watermark alone.
     *
     * <p>One repaint is queued at a time: registering the listeners brings a burst of callbacks,
     * and the screen only ever needs the newest state.
     */
    private void scheduleRepaint() {
        synchronized (lock) {
            if (repaintPending || onChange == null) {
                return;
            }
            repaintPending = true;
        }
        main.post(repaint);
    }

    /** Sends one transaction, with an int argument unless it is {@link #NO_ARG}. */
    private static boolean transact(IBinder service, String descriptor, int code, int argument) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            if (argument != NO_ARG) {
                data.writeInt(argument);
            }
            service.transact(code, data, reply, 0);
            reply.readException();
            return true;
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "binder call " + code + " failed", e);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** Registers or unregisters a listener binder; the DATC calls carry a second token binder. */
    private static boolean transactListener(IBinder service, String descriptor, int code,
            Binder listener, IBinder token) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeStrongBinder(listener);
            if (token != null) {
                data.writeStrongBinder(token);
            }
            service.transact(code, data, reply, 0);
            reply.readException();
            return true;
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "binder call " + code + " failed", e);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** Calls a getter and returns its int reply, or {@code null} when the call failed. */
    private static Integer transactForInt(IBinder service, String descriptor, int code) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            service.transact(code, data, reply, 0);
            reply.readException();
            return Integer.valueOf(reply.readInt());
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "binder call " + code + " failed", e);
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** Calls a keyed getter: one int in, one int out. */
    private static Integer transactForIntArg(IBinder service, String descriptor, int code, int arg) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeInt(arg);
            service.transact(code, data, reply, 0);
            reply.readException();
            return Integer.valueOf(reply.readInt());
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "binder call " + code + "(" + arg + ") failed", e);
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static IBinder service(String name) {
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getMethod("getService", String.class);
            return (IBinder) getService.invoke(null, name);
        } catch (Exception e) {
            Log.w(TAG, "ServiceManager.getService(" + name + ") failed", e);
            return null;
        }
    }

    /** {@code IDATCListener} callbacks. */
    private final class DatcListener extends Binder {

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            switch (code) {
                case DATC_ON_TEMPERATURE:
                    data.enforceInterface(DATC_LISTENER_DESCRIPTOR);
                    readTemperatures(data);
                    return true;
                case DATC_ON_STATUS:
                    // A Parcelable DatcStatus; it carries no cabin reading, so only note it.
                    Log.i(TAG, "datc status");
                    scheduleRepaint();
                    return true;
                default:
                    Log.i(TAG, "datc callback " + code);
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private void readTemperatures(Parcel data) {
            double[] values = new double[PAIRS];
            int[] states = new int[PAIRS];
            for (int i = 0; i < PAIRS; i++) {
                values[i] = data.readFloat();
                states[i] = data.readInt();
            }
            Log.i(TAG, "datc temperatures"
                    + " drC=" + values[0] + "/" + states[0]
                    + " drF=" + values[1] + "/" + states[1]
                    + " psC=" + values[2] + "/" + states[2]
                    + " psF=" + values[3] + "/" + states[3]
                    + " amC=" + values[4] + "/" + states[4]
                    + " amF=" + values[5] + "/" + states[5]
                    + " rearC=" + values[6] + "/" + states[6]
                    + " rearF=" + values[7] + "/" + states[7]);
            state.ambientCelsius = states[PAIR_AMBIENT_CELSIUS] == TEMP_STATE_NORMAL
                    ? values[PAIR_AMBIENT_CELSIUS] : Double.NaN;
            state.ambientFahrenheit = states[PAIR_AMBIENT_FAHRENHEIT] == TEMP_STATE_NORMAL
                    ? values[PAIR_AMBIENT_FAHRENHEIT] : Double.NaN;
            state.setpointCelsius = states[PAIR_DRIVER_CELSIUS] == TEMP_STATE_NORMAL
                    ? values[PAIR_DRIVER_CELSIUS] : Double.NaN;
            state.setpointFahrenheit = states[PAIR_DRIVER_FAHRENHEIT] == TEMP_STATE_NORMAL
                    ? values[PAIR_DRIVER_FAHRENHEIT] : Double.NaN;
            state.passengerSetpointCelsius = states[PAIR_PASSENGER_CELSIUS] == TEMP_STATE_NORMAL
                    ? values[PAIR_PASSENGER_CELSIUS] : Double.NaN;
            state.passengerSetpointFahrenheit =
                    states[PAIR_PASSENGER_FAHRENHEIT] == TEMP_STATE_NORMAL
                            ? values[PAIR_PASSENGER_FAHRENHEIT] : Double.NaN;
            scheduleRepaint();
        }
    }

    /** {@code IHEVListener} callbacks. */
    private final class HevListener extends Binder {

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            switch (code) {
                case HEV_ON_STATUS:
                    data.enforceInterface(HEV_LISTENER_DESCRIPTOR);
                    readStatus(data);
                    return true;
                case HEV_ON_POWER:
                    data.enforceInterface(HEV_LISTENER_DESCRIPTOR);
                    readPower(data);
                    return true;
                default:
                    Log.i(TAG, "hev callback " + code);
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private void readStatus(Parcel data) {
            int flag = data.readInt();
            int value = data.readInt();
            Log.i(TAG, "hev status " + flag + "=" + value);
            if (flag == FLAG_DISPLAY_SOC) {
                state.socPercent = Estimate.socPercent(value);
            } else if (flag == FLAG_DISCHARGE_LIMIT_SOC) {
                state.cutoffPercent = Estimate.cutoffPercent(value);
            } else if (flag == FLAG_CLIMATE_W) {
                state.climateW = Estimate.climateWatts(value);
            } else if (flag == FLAG_OTHER_W) {
                state.otherW = Estimate.otherWatts(value);
            } else if (flag == FLAG_DISCHARGE_W) {
                state.dischargeW = Estimate.dischargeWatts(value);
            } else if (flag == FLAG_V2L_REMAIN) {
                state.remainingMin = value;
            } else if (flag == FLAG_UTIL_MODE_STATE) {
                state.utilityOn = CarState.utilityFromFlag(value);
            } else if (flag == FLAG_EV_RDY) {
                state.evReady = CarState.evReadyFromFlag(value);
            } else if (flag == FLAG_VCMS_RDY) {
                vcmsRdy = value;
                state.charging = CarState.isCharging(vcmsRdy, vcmsChgAlarm, chgSts);
            } else if (flag == FLAG_VCMS_CHG_ALARM) {
                vcmsChgAlarm = value;
                state.charging = CarState.isCharging(vcmsRdy, vcmsChgAlarm, chgSts);
            } else if (flag == FLAG_CHG_STS) {
                chgSts = value;
                state.charging = CarState.isCharging(vcmsRdy, vcmsChgAlarm, chgSts);
            } else if (flag == FLAG_UTIL_MODE_WARN && value != 0) {
                Log.w(TAG, "VCU Utility Mode warning: " + value);
            }
            touched();
        }

        private void readPower(Parcel data) {
            int draw = data.readInt();
            Log.i(TAG, "hev draw " + draw);
            state.drawW = draw < 0 ? Double.NaN : draw;
            touched();
        }
    }

    /** {@code ISystemListener} callbacks (all oneway on {@code com.hkmc.micom.ISystemListener}). */
    private final class SystemListener extends Binder {

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == SYSTEM_ON_GEAR_STATUS) {
                data.enforceInterface(SYSTEM_LISTENER_DESCRIPTOR);
                int gear = data.readInt();
                Log.i(TAG, "system gear=" + gear);
                state.inPark = CarState.inParkFromGear(gear);
                scheduleRepaint();
                return true;
            }
            if (code >= 1 && code <= SYSTEM_MAX_CALLBACK) {
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }
}
