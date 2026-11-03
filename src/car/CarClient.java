package com.sambok;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
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

    private static final String DATC_DESCRIPTOR = "com.hkmc.datc.IDATCService";
    private static final String DATC_LISTENER_DESCRIPTOR = "com.hkmc.datc.IDATCListener";
    private static final String HEV_DESCRIPTOR = "com.hkmc.hev.IHEVService";
    private static final String HEV_LISTENER_DESCRIPTOR = "com.hkmc.hev.IHEVListener";

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
    /** {@code IHEVService$Stub.TRANSACTION_setV2XInstantRun}. */
    private static final int HEV_SET_V2X_INSTANT_RUN = 56;
    /** {@code IHEVListener$Stub.TRANSACTION_onHEVStatusChanged}. */
    private static final int HEV_ON_STATUS = 1;
    /** {@code IHEVListener$Stub.TRANSACTION_onPwrConsumptionChanged}. */
    private static final int HEV_ON_POWER = 14;

    /** {@code DATCFlag$DATCConstants.TEMP_UNIT_C}. */
    private static final int UNIT_CELSIUS = 1;
    /** {@code DATCFlag$DATCConstants.TEMP_UNIT_F}. */
    private static final int UNIT_FAHRENHEIT = 2;
    /** {@code DATCFlag$DATCConstants.INVALID_VALUE}, also the "no reading" value. */
    private static final int INVALID = -1;

    /** {@code HEVManager$HEVFlag.DISPLAY_SOC}: the battery percentage. */
    private static final int FLAG_DISPLAY_SOC = 25;
    /** {@code HEVManager$HEVFlag.VCU_UTILMODUSMSTA}: Utility Mode on/off. */
    private static final int FLAG_UTIL_MODE_STATE = 80;

    /**
     * The eight (value, state) pairs of {@code onTemperatureInformation}, in the order the DATC
     * service sends them: driver celsius, driver fahrenheit, passenger celsius, passenger
     * fahrenheit, outside celsius, outside fahrenheit, rear celsius, rear fahrenheit. All of them
     * are climate set-points or the outside sensor; this unit exposes no cabin reading through
     * any callback, so {@link CarState#cabinCelsius} stays unknown.
     */
    private static final int PAIRS = 8;
    private static final int PAIR_AMBIENT_CELSIUS = 4;

    /** How long a Utility Mode write waits for the car to report the new state. */
    private static final long CONFIRM_MS = 1500;

    /** Argument marker for a transaction that carries no int. */
    private static final int NO_ARG = Integer.MIN_VALUE;

    private final CarState state = new CarState(0L);
    private final Object lock = new Object();
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
    private IBinder datc;
    private IBinder hev;
    private Binder datcListener;
    private Binder hevListener;
    private IBinder token;

    /** Binds both services and registers a listener on each; {@code onChange} runs on main. */
    public void bind(Context context, Runnable onChange) {
        // Context is not needed: system service lookups are process-wide, and the argument is part
        // of the Activity's contract.
        synchronized (lock) {
            this.onChange = onChange;
        }
        token = new Binder();

        datc = service(DATC_SERVICE);
        if (datc != null) {
            datcListener = new DatcListener();
            if (transactListener(datc, DATC_DESCRIPTOR, DATC_REGISTER_LISTENER, datcListener,
                    token)) {
                Log.i(TAG, "registered DATC listener");
            }
            readTemperatureUnit();
        } else {
            Log.w(TAG, "no binder for " + DATC_SERVICE);
        }

        hev = service(HEV_SERVICE);
        if (hev != null) {
            hevListener = new HevListener();
            if (transactListener(hev, HEV_DESCRIPTOR, HEV_ADD_LISTENER, hevListener, null)) {
                Log.i(TAG, "registered HEV listener");
            }
        } else {
            Log.w(TAG, "no binder for " + HEV_SERVICE);
        }
        Log.i(TAG, "bound: " + DATC_SERVICE + "=" + (datc != null) + " " + HEV_SERVICE + "="
                + (hev != null));
    }

    public CarState state() {
        return state;
    }

    /**
     * Turns Utility Mode on or off. Nothing else is ever written to the car.
     *
     * <p>Returns true only when the car has reported the matching state, which it does through the
     * HEV status callback; a write without confirmation returns false. The wait is short so a
     * caller on the UI thread stays responsive; the screen's own settle window is longer.
     */
    public boolean setUtilityOn(boolean on) {
        IBinder service = hev;
        if (service == null) {
            Log.w(TAG, "no hev service: Utility Mode not written");
            return false;
        }
        if (!transact(service, HEV_DESCRIPTOR, HEV_SET_V2X_INSTANT_RUN, on ? 1 : 0)) {
            return false;
        }
        Log.i(TAG, "setV2XInstantRun(" + (on ? 1 : 0) + ") sent");
        return awaitUtility(on);
    }

    /** Unregisters both listeners; writes nothing. */
    public void unbind() {
        if (datc != null && datcListener != null) {
            transactListener(datc, DATC_DESCRIPTOR, DATC_UNREGISTER_LISTENER, datcListener, token);
        }
        if (hev != null && hevListener != null) {
            transactListener(hev, HEV_DESCRIPTOR, HEV_REMOVE_LISTENER, hevListener, null);
        }
        synchronized (lock) {
            onChange = null;
        }
        datc = null;
        hev = null;
        datcListener = null;
        hevListener = null;
        Log.i(TAG, "unbound");
    }

    /** Reads the car's own unit setting once per session; opens no write. */
    private void readTemperatureUnit() {
        Integer unit = transactForInt(datc, DATC_DESCRIPTOR, DATC_GET_TEMPERATURE_UNIT);
        Log.i(TAG, "temperature unit " + unit);
        if (unit == null) {
            return;
        }
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
     * Records that a callback arrived and asks the screen to repaint.
     *
     * <p>One repaint is queued at a time: registering the listeners brings a burst of callbacks,
     * and the screen only ever needs the newest state.
     */
    private void touched() {
        synchronized (lock) {
            state.lastUpdateMs = SystemClock.uptimeMillis();
            lock.notifyAll();
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
        } catch (RemoteException e) {
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
        } catch (RemoteException e) {
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
        } catch (RemoteException e) {
            Log.w(TAG, "binder call " + code + " failed", e);
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
                    touched();
                    return true;
                default:
                    Log.i(TAG, "datc callback " + code);
                    return false;
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
            state.ambientCelsius = states[PAIR_AMBIENT_CELSIUS] == INVALID
                    ? Double.NaN : values[PAIR_AMBIENT_CELSIUS];
            touched();
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
                    return false;
            }
        }

        private void readStatus(Parcel data) {
            int flag = data.readInt();
            int value = data.readInt();
            Log.i(TAG, "hev status " + flag + "=" + value);
            if (flag == FLAG_DISPLAY_SOC) {
                state.socPercent = value < 0 ? Double.NaN : value;
            } else if (flag == FLAG_UTIL_MODE_STATE) {
                if (value == 1) {
                    state.utilityOn = Boolean.TRUE;
                } else if (value == 0) {
                    state.utilityOn = Boolean.FALSE;
                } else {
                    state.utilityOn = null;
                }
            }
            touched();
        }

        private void readPower(Parcel data) {
            int draw = data.readInt();
            Log.i(TAG, "hev draw " + draw);
            state.drawKw = draw < 0 ? Double.NaN : draw;
            touched();
        }
    }
}
