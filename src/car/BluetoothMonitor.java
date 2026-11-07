package com.sambok;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Monitors the user-selected Bluetooth device's ACL connection state and live RSSI (dBm).
 *
 * <p>On this head unit ({@code framework.odex} / {@code Bluetooth.odex}),
 * {@code BluetoothAdapter.readRawRssi(BluetoothDevice)} issues an HCI Read RSSI command for an
 * active ACL link and broadcasts {@code "android.bluetooth.device.action.RSSI"} carrying
 * {@code "android.bluetooth.device.extra.RSSI"} (an {@code int} in dBm).
 */
// BluetoothAdapter.getDefaultAdapter() and Intent.getParcelableExtra(String) are deprecated in
// API 31+/33+; API 19 has only those overloads.
@SuppressWarnings("deprecation")
public final class BluetoothMonitor {

    private static final String TAG = "Sambok";

    private static final String PREFS_NAME = "sambok_prefs";
    private static final String KEY_ADDRESS = "auto_unlock_address";
    private static final String KEY_NAME = "auto_unlock_name";

    /** Custom Mobis/Broadcom broadcast action emitted by {@code RemoteDevices.readRawRssiCallback}. */
    private static final String ACTION_RSSI = "android.bluetooth.device.action.RSSI";

    public static final class DeviceInfo {
        public final String address;
        public final String name;
        public final boolean connected;

        public DeviceInfo(String address, String name, boolean connected) {
            this.address = address;
            this.name = (name != null && !name.trim().isEmpty()) ? name.trim() : address;
            this.connected = connected;
        }
    }

    private Context appContext;
    private Runnable onChange;
    private boolean registered;

    private String targetAddress;
    private String targetName;
    private boolean targetConnected;
    private int targetRssiDbm = AutoUnlock.NO_RSSI;

    private Method getAclStateMethod;
    private boolean getAclStateResolved;
    private Method readRawRssiMethod;
    private boolean readRawRssiResolved;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state != BluetoothAdapter.STATE_ON) {
                    targetConnected = false;
                    targetRssiDbm = AutoUnlock.NO_RSSI;
                    notifyChanged();
                } else {
                    refreshTargetState();
                    notifyChanged();
                }
                return;
            }

            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null || targetAddress == null) {
                return;
            }
            String address = device.getAddress();
            if (address == null || !address.equalsIgnoreCase(targetAddress)) {
                return;
            }

            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                Log.i(TAG, "BT ACL connected: " + address);
                targetConnected = true;
                targetRssiDbm = AutoUnlock.NO_RSSI;
                requestRssiPoll();
                notifyChanged();
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                Log.i(TAG, "BT ACL disconnected: " + address);
                targetConnected = false;
                targetRssiDbm = AutoUnlock.NO_RSSI;
                notifyChanged();
            } else if (ACTION_RSSI.equals(action)) {
                int rssi = extractRssiDbm(intent);
                if (rssi != AutoUnlock.NO_RSSI) {
                    targetConnected = true;
                    targetRssiDbm = rssi;
                    notifyChanged();
                }
            }
        }
    };

    public void start(Context context, Runnable onChange) {
        this.appContext = context.getApplicationContext();
        this.onChange = onChange;

        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.targetAddress = prefs.getString(KEY_ADDRESS, null);
        this.targetName = prefs.getString(KEY_NAME, null);

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(ACTION_RSSI);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        try {
            appContext.registerReceiver(receiver, filter);
            registered = true;
        } catch (Exception e) {
            Log.w(TAG, "failed to register Bluetooth receiver", e);
        }

        refreshTargetState();
        if (targetConnected) {
            requestRssiPoll();
        }
    }

    public void stop() {
        onChange = null;
        if (registered && appContext != null) {
            try {
                appContext.unregisterReceiver(receiver);
            } catch (Exception e) {
                Log.w(TAG, "failed to unregister Bluetooth receiver", e);
            }
            registered = false;
        }
    }

    public void selectDevice(String address, String name) {
        String normalized = (address != null && !address.trim().isEmpty()) ? address.trim() : null;
        this.targetAddress = normalized;
        this.targetName = (normalized != null && name != null && !name.trim().isEmpty())
                ? name.trim()
                : normalized;
        this.targetRssiDbm = AutoUnlock.NO_RSSI;

        if (appContext != null) {
            SharedPreferences.Editor editor = appContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit();
            if (normalized == null) {
                editor.remove(KEY_ADDRESS).remove(KEY_NAME);
            } else {
                editor.putString(KEY_ADDRESS, this.targetAddress);
                editor.putString(KEY_NAME, this.targetName);
            }
            editor.apply();
        }

        refreshTargetState();
        if (targetConnected) {
            requestRssiPoll();
        }
        notifyChanged();
    }

    public String targetAddress() {
        return targetAddress;
    }

    public String targetName() {
        return targetName;
    }

    public boolean isTargetConnected() {
        return targetConnected;
    }

    public int targetRssiDbm() {
        return targetConnected ? targetRssiDbm : AutoUnlock.NO_RSSI;
    }

    /**
     * Refreshes the target device's ACL connection state and, when connected, triggers an
     * asynchronous {@code BluetoothAdapter.readRawRssi(BluetoothDevice)} query.
     */
    public void requestRssiPoll() {
        if (targetAddress == null) {
            targetConnected = false;
            targetRssiDbm = AutoUnlock.NO_RSSI;
            return;
        }
        BluetoothDevice device = findBondedDevice(targetAddress);
        if (device == null) {
            targetConnected = false;
            targetRssiDbm = AutoUnlock.NO_RSSI;
            return;
        }
        Boolean acl = queryAclState(device);
        if (acl != null) {
            if (!acl.booleanValue()) {
                targetConnected = false;
                targetRssiDbm = AutoUnlock.NO_RSSI;
                return;
            }
            targetConnected = true;
        }
        if (!targetConnected) {
            return;
        }
        invokeReadRawRssi(device);
    }

    public List<DeviceInfo> pairedDevices() {
        List<DeviceInfo> result = new ArrayList<DeviceInfo>();
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                return result;
            }
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) {
                return result;
            }
            for (BluetoothDevice device : bonded) {
                if (device == null || device.getAddress() == null) {
                    continue;
                }
                Boolean acl = queryAclState(device);
                boolean connected = (acl != null)
                        ? acl.booleanValue()
                        : (targetAddress != null
                                && targetAddress.equalsIgnoreCase(device.getAddress())
                                && targetConnected);
                String name = device.getName();
                result.add(new DeviceInfo(device.getAddress(), name, connected));
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Bluetooth permission denied when listing paired devices", e);
        } catch (Exception e) {
            Log.w(TAG, "failed to list paired Bluetooth devices", e);
        }
        Collections.sort(result, new Comparator<DeviceInfo>() {
            @Override
            public int compare(DeviceInfo a, DeviceInfo b) {
                if (a.connected != b.connected) {
                    return a.connected ? -1 : 1;
                }
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return result;
    }

    private void refreshTargetState() {
        if (targetAddress == null) {
            targetConnected = false;
            targetRssiDbm = AutoUnlock.NO_RSSI;
            return;
        }
        BluetoothDevice device = findBondedDevice(targetAddress);
        if (device == null) {
            targetConnected = false;
            targetRssiDbm = AutoUnlock.NO_RSSI;
            return;
        }
        try {
            String currentName = device.getName();
            if (currentName != null && !currentName.trim().isEmpty()) {
                targetName = currentName.trim();
            }
        } catch (Exception ignored) {
        }
        Boolean acl = queryAclState(device);
        if (acl != null) {
            targetConnected = acl.booleanValue();
            if (!targetConnected) {
                targetRssiDbm = AutoUnlock.NO_RSSI;
            }
        }
    }

    private static BluetoothDevice findBondedDevice(String address) {
        if (address == null) {
            return null;
        }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                return null;
            }
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice device : bonded) {
                    if (device != null && address.equalsIgnoreCase(device.getAddress())) {
                        return device;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "findBondedDevice failed", e);
        }
        return null;
    }

    /** Calls {@code BluetoothDevice.getAclState()} if present on this firmware, or returns null. */
    private Boolean queryAclState(BluetoothDevice device) {
        if (device == null) {
            return null;
        }
        try {
            if (!getAclStateResolved) {
                getAclStateResolved = true;
                getAclStateMethod = BluetoothDevice.class.getMethod("getAclState");
            }
            if (getAclStateMethod != null) {
                Object res = getAclStateMethod.invoke(device);
                if (res instanceof Boolean) {
                    return (Boolean) res;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getAclState reflection failed", e);
        }
        return null;
    }

    /** Calls {@code BluetoothAdapter.readRawRssi(BluetoothDevice)} on this firmware. */
    private void invokeReadRawRssi(BluetoothDevice device) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                return;
            }
            if (!readRawRssiResolved) {
                readRawRssiResolved = true;
                readRawRssiMethod = BluetoothAdapter.class.getMethod(
                        "readRawRssi", BluetoothDevice.class);
            }
            if (readRawRssiMethod != null) {
                readRawRssiMethod.invoke(adapter, device);
            }
        } catch (Exception e) {
            Log.w(TAG, "readRawRssi reflection failed", e);
        }
    }

    private static int extractRssiDbm(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return AutoUnlock.NO_RSSI;
        }
        Object raw = extras.get(BluetoothDevice.EXTRA_RSSI);
        if (raw instanceof Number) {
            int val = ((Number) raw).intValue();
            if (val < 0 && val >= -127) {
                return val;
            }
        }
        return AutoUnlock.NO_RSSI;
    }

    private void notifyChanged() {
        Runnable cb = onChange;
        if (cb != null) {
            cb.run();
        }
    }
}
