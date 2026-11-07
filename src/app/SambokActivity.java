package com.sambok;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * The dog screen: the whole app.
 *
 * <p>The whole app is the one package {@code com.sambok}, so the Activity names its neighbours
 * directly and the manifest names the class relative to that package. It paints the panel from
 * the last state {@link CarClient} delivered and never calls the car itself except when the driver
 * taps the toggle.
 *
 * <p>Four screen states: {@code connecting} (the car has not reported Utility Mode yet, so the
 * toggle is closed and the screen waits up to {@link #START_DEADLINE_MS} for the first report),
 * {@code off} (toggle off, "tap to start"), {@code starting} (an enable has been sent and the
 * screen waits up to {@link #START_DEADLINE_MS} for the car to report it on) and {@code running}
 * (the dog scene, with the toggle reflecting what the car reports; if Utility Mode drops
 * mid-session, the screen remains in {@code running} as a "dropped" sub-state with the scene and
 * amber warning strip visible and the toggle offering enable again until the driver re-enables
 * it or taps the dismiss button, which acknowledges the warning and shows {@code off}).
 *
 * <p>There is no temperature field and no colour state: this firmware publishes no cabin
 * reading, so {@link Panel} is not used here.
 */
public class SambokActivity extends Activity {

    private static final String TAG = "Sambok";

    /** The pack's usable capacity in kWh for this car; a constant, not read from the car. */
    private static final double CAPACITY_KWH = 77.4;

    /** How long an enable or disable may take before the screen considers the write timed out. */
    private static final long START_DEADLINE_MS = 10L * 1000L;

    /** The screen's clock: staleness and the countdown are re-read once a second. */
    private static final long TICK_MS = 1000L;

    /** Draw samples are taken every fifth tick, so sixty of them span five minutes. */
    private static final int TICKS_PER_SAMPLE = 5;

    /** What the toggle and the scene are showing. */
    private enum Screen {
        CONNECTING, OFF, STARTING, RUNNING
    }

    private final CarClient client = new CarClient();
    private final BluetoothMonitor bluetooth = new BluetoothMonitor();
    private final AutoUnlock autoUnlock = new AutoUnlock();
    private final Countdown countdownModel = new Countdown();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Button toggle;
    /** Acknowledges the dropped warning; visible only while that warning is up. */
    private Button dismiss;
    private TextView countdown;
    private TextView countdownCaption;
    /** "A/C set to 73°F": the driver set-point, while Utility Mode runs and the car is fresh. */
    private TextView setpoint;
    private TextView status;
    private View autoUnlockPanel;
    private TextView autoUnlockStatus;
    private TextView autoUnlockRssi;
    private RssiBarView autoUnlockBar;
    private View strip;
    private View preflight;
    private TextView preflightClimateValue;
    private TextView preflightClimateSub;
    private TextView preflightBatteryValue;
    private View preflightBatterySubRow;
    private TextView preflightBatterySub;
    private Button preflightLimitButton;
    private TextView preflightBatterySubClose;
    private TextView preflightCheckCharger;
    private TextView preflightCheckReady;
    private Button preflightAutoUnlockButton;
    private View scene;
    private View sceneBanner;
    /** Shows assets/scene.svg; KitKat's Chromium WebView runs the artwork's own animations. */
    private WebView sceneView;
    /** The artwork is playing, rather than parked on a still frame. */
    private boolean sceneLive;
    /** The document has loaded, so a pause/unpause injection will land. */
    private boolean sceneReady;

    private Screen screen = Screen.CONNECTING;
    /** A car callback has arrived, so staleness and the countdown mean something. */
    private boolean sawCar;
    /** Whether the initial power draw sample has been seeded into {@link #countdownModel}. */
    private boolean seededDraw;
    /**
     * The driver has tapped: the screen follows the car only when this is false. Cleared as soon
     * as the car reports a stopped Utility Mode, so it survives a pending tap and no longer.
     */
    private boolean driverChose;
    /** A Utility Mode write is in flight; the toggle stays closed until it lands. */
    private boolean writing;
    /** Timestamp when the last write was sent. */
    private long writeStartedMs;
    /** The last enable did not read back as on within the deadline. */
    private boolean failed;
    private long connectAtMs;
    private long startingAtMs;
    private int ticks;
    private int currentBatteryDrawableId;
    private boolean destroyed;

    @Override
    // FLAG_FULLSCREEN is deprecated from API 30, where the insets API replaces it; API 19 has no
    // other way to hide the status bar, and the unit runs API 19.
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_sambok);

        toggle = (Button) findViewById(R.id.toggle);
        dismiss = (Button) findViewById(R.id.dismiss);
        countdown = (TextView) findViewById(R.id.countdown);
        countdownCaption = (TextView) findViewById(R.id.countdown_caption);
        setpoint = (TextView) findViewById(R.id.setpoint);
        status = (TextView) findViewById(R.id.status);
        autoUnlockPanel = findViewById(R.id.auto_unlock_panel);
        autoUnlockStatus = (TextView) findViewById(R.id.auto_unlock_status);
        autoUnlockRssi = (TextView) findViewById(R.id.auto_unlock_rssi);
        autoUnlockBar = (RssiBarView) findViewById(R.id.auto_unlock_bar);
        autoUnlockBar.setLabels(
                getString(R.string.auto_unlock_tick_arm),
                getString(R.string.auto_unlock_tick_unlock));
        strip = findViewById(R.id.strip);
        preflight = findViewById(R.id.preflight);
        preflightClimateValue = (TextView) findViewById(R.id.preflight_climate_value);
        preflightClimateSub = (TextView) findViewById(R.id.preflight_climate_sub);
        preflightBatteryValue = (TextView) findViewById(R.id.preflight_battery_value);
        preflightBatterySubRow = findViewById(R.id.preflight_battery_sub_row);
        preflightBatterySub = (TextView) findViewById(R.id.preflight_battery_sub);
        preflightLimitButton = (Button) findViewById(R.id.preflight_limit_button);
        preflightLimitButton.setCompoundDrawablePadding(dp(6));
        preflightLimitButton.setCompoundDrawablesWithIntrinsicBounds(
                null, null, createDropdownArrowDrawable(), null);
        preflightBatterySubClose = (TextView) findViewById(R.id.preflight_battery_sub_close);
        preflightCheckCharger = (TextView) findViewById(R.id.preflight_check_charger);
        preflightCheckReady = (TextView) findViewById(R.id.preflight_check_ready);
        preflightAutoUnlockButton = (Button) findViewById(R.id.preflight_auto_unlock_button);
        scene = findViewById(R.id.scene);
        sceneBanner = findViewById(R.id.scene_banner);

        // The scene is opaque artwork filling the pane, so nothing here needs a background.
        sceneView = (WebView) findViewById(R.id.scene_view);
        sceneView.setBackgroundColor(Color.TRANSPARENT);
        sceneView.setVerticalScrollBarEnabled(false);
        sceneView.setHorizontalScrollBarEnabled(false);
        sceneView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sceneView.setFocusable(false);
        sceneView.setClickable(false);
        sceneView.getSettings().setJavaScriptEnabled(true);
        sceneView.getSettings().setSupportZoom(false);
        sceneView.getSettings().setBuiltInZoomControls(false);
        sceneView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                sceneReady = true;
                applySceneAnimation();
            }
        });
        sceneView.loadUrl("file:///android_asset/scene.svg");

        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                tap();
            }
        });
        dismiss.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dismissDropped();
            }
        });
        View.OnClickListener limitClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showDischargeLimitDialog();
            }
        };
        preflightLimitButton.setOnClickListener(limitClickListener);
        preflightBatterySubRow.setOnClickListener(limitClickListener);
        preflightAutoUnlockButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showAutoUnlockDialog();
            }
        });

        bluetooth.start(this, onBluetoothChange);
        autoUnlock.setTarget(bluetooth.targetAddress(), bluetooth.targetName());

        // The first frame is painted before binding, so the panel exists even if no callback ever
        // arrives; nothing is written to the car here. Utility Mode is only known from a callback,
        // so the launch state waits for one until the connect deadline runs out.
        connectAtMs = SystemClock.uptimeMillis();
        paint();
        client.bind(this, onCarChange);
    }

    @Override
    protected void onResume() {
        super.onResume();
        paint();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TICK_MS);
    }

    /**
     * Starts or parks the artwork. The SVG uses native SMIL animations ({@code <animateTransform>}
     * and {@code <animate>}) because KitKat's Chromium 30 WebView does not support unprefixed CSS
     * {@code @keyframes} or CSS {@code transform-origin} on SVG elements; parking calls standard
     * {@code SVGSVGElement.pauseAnimations()}, which holds the drawing where it is rather than
     * hiding it — the scene is only ever visible while dog mode runs.
     */
    private void setSceneLive(boolean live) {
        if (sceneLive == live) {
            return;
        }
        sceneLive = live;
        applySceneAnimation();
    }

    private void applySceneAnimation() {
        if (sceneView == null || !sceneReady) {
            return;
        }
        String js = sceneLive
                ? "(function(){var r=document.documentElement;"
                        + "if(r&&r.unpauseAnimations)r.unpauseAnimations()})()"
                : "(function(){var r=document.documentElement;"
                        + "if(r&&r.pauseAnimations)r.pauseAnimations()})()";
        sceneView.evaluateJavascript(js, null);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacks(tick);
        bluetooth.stop();
        client.unbind();
        if (sceneView != null) {
            sceneView.destroy();
            sceneView = null;
        }
        super.onDestroy();
    }

    /**
     * Minutes of Utility Mode left, from the car's own estimate when plausible, otherwise from
     * the pack's remaining energy above the cutoff divided by the smoothed draw in
     * {@link #countdownModel}.
     *
     * <p>Returns -1 when nothing usable is known, which the caller renders as a dash.
     */
    private int estimateMinutes(CarState state) {
        int usable = Estimate.usableSocPercent(state.socPercent, state.cutoffPercent);
        if (usable == 0) {
            return 0;
        }
        int modelMin = countdownModel.remainingMinutes(
                state.socPercent, state.cutoffPercent, CAPACITY_KWH);
        if (modelMin >= 0) {
            return modelMin;
        }
        if (Estimate.plausibleCarMinutes(state.remainingMin)) {
            return state.remainingMin;
        }
        return -1;
    }

    /** Runs on the main thread whenever a car callback lands. */
    private final Runnable onCarChange = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }
            sawCar = true;
            if (!seededDraw) {
                CarState state = client.state();
                double watts = Estimate.drawWatts(state.dischargeW, state.climateW, state.otherW);
                if (!Double.isNaN(watts) && watts > 0) {
                    countdownModel.sample(watts / 1000.0);
                    seededDraw = true;
                }
            }
            paint();
        }
    };

    /** Runs on the main thread whenever a Bluetooth ACL or RSSI event arrives. */
    private final Runnable onBluetoothChange = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }
            paint();
        }
    };

    /**
     * The one-second tick: staleness, the countdown, write deadlines, and periodic refresh.
     *
     * <p>Periodic refresh requests are posted to {@link CarClient}'s worker thread rather than
     * calling binder directly on the main thread, so a car service that has stopped answering
     * cannot freeze the screen or the clock.
     */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }
            ticks++;
            if (ticks % 2 == 0 && autoUnlock.isEnabled()) {
                bluetooth.requestRssiPoll();
            }
            CarState state = client.state();
            if (ticks % TICKS_PER_SAMPLE == 0) {
                // Periodically request a refresh on the worker thread so reads stay current even
                // when callbacks stop or are missing.
                client.requestRefresh();
                if (sawCar) {
                    double watts = Estimate.drawWatts(state.dischargeW, state.climateW,
                            state.otherW);
                    countdownModel.sample(watts / 1000.0);
                    Log.i(TAG, "estimate soc=" + state.socPercent + " cutoff=" + state.cutoffPercent
                            + " climateW=" + state.climateW
                            + " otherW=" + state.otherW + " dischargeW=" + state.dischargeW
                            + " watts=" + watts + " remainMin=" + state.remainingMin
                            + " minutes=" + estimateMinutes(state));
                }
            }
            long now = SystemClock.uptimeMillis();
            if (writing && now - writeStartedMs >= START_DEADLINE_MS) {
                Log.w(TAG, "write timed out after " + START_DEADLINE_MS + " ms");
                writing = false;
                driverChose = false;
            }
            if (screen == Screen.STARTING && now - startingAtMs >= START_DEADLINE_MS) {
                expireStarting();
            } else if (screen == Screen.CONNECTING && now - connectAtMs >= START_DEADLINE_MS) {
                // The car never reported Utility Mode: stop claiming to know and offer the tap.
                Log.w(TAG, "no Utility Mode report within " + START_DEADLINE_MS + " ms");
                screen = Screen.OFF;
            }
            paint();
            handler.postDelayed(this, TICK_MS);
        }
    };

    /** One tap, one write: starts Utility Mode when off; exiting requires power-cycling the car. */
    private void tap() {
        if (writing || screen == Screen.STARTING || screen == Screen.CONNECTING) {
            return;
        }
        CarState state = client.state();
        if (!Boolean.TRUE.equals(state.utilityOn) && !state.charging) {
            start();
        }
    }

    /**
     * The driver has seen the dropped warning: take the scene and the strip down and show
     * {@code off}. Writes nothing to the car. Only acts while the car still reports Utility Mode
     * stopped, so a stale tap cannot hide a running or unknown state; if the car later reports it
     * on again, {@link #paint()} follows it back to {@code running}.
     */
    private void dismissDropped() {
        if (destroyed || writing || screen != Screen.RUNNING
                || !Boolean.FALSE.equals(client.state().utilityOn)) {
            return;
        }
        Log.i(TAG, "tap: dismiss dropped warning");
        screen = Screen.OFF;
        paint();
    }

    private void start() {
        Log.i(TAG, "tap: enable");
        failed = false;
        driverChose = true;
        writing = true;
        writeStartedMs = SystemClock.uptimeMillis();
        screen = Screen.STARTING;
        startingAtMs = SystemClock.uptimeMillis();
        paint();
        writeUtility(true);
    }

    /**
     * Sends the write off the main thread: {@link CarClient#setUtilityOn} blocks for up to 1.5 s
     * waiting for the car to confirm, which would freeze the screen on the UI thread.
     */
    private void writeUtility(final boolean on) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean confirmed = false;
                try {
                    confirmed = client.setUtilityOn(on);
                } catch (RuntimeException e) {
                    // A binder failure must not leave the toggle closed for good.
                    Log.w(TAG, "write " + on + " failed", e);
                }
                final boolean result = confirmed;
                Log.i(TAG, "write " + on + " confirmed=" + result);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        settle(on, result);
                    }
                });
            }
        }, "sambok-utility").start();
    }

    /** Runs on the main thread when a write has finished, confirmed or not. */
    private void settle(boolean on, boolean confirmed) {
        if (destroyed) {
            return;
        }
        writing = false;
        if (on && !confirmed) {
            // The car may still report it on: the deadline in the tick owns the outcome.
            Log.i(TAG, "enable not confirmed yet");
        }
        paint();
    }

    /**
     * The enable ran out of its window: report the generic failure, release the toggle and give
     * the car back. Clearing {@code writing} matters when the write itself wedged: the driver can
     * retry instead of facing a closed toggle for the rest of the session.
     */
    private void expireStarting() {
        Log.w(TAG, "enable not reported on within " + START_DEADLINE_MS + " ms");
        failed = true;
        driverChose = false;
        writing = false;
        screen = Screen.OFF;
    }

    /**
     * Paints every field from the last car state and this screen's own state.
     *
     * <p>Reads {@link CarClient#state()}, a plain holder; it makes no binder call, so both the
     * callback path and the one-second tick can use it.
     */
    // Resources.getColor(int) is deprecated from API 23; API 19 has only the single-arg overload.
    @SuppressWarnings("deprecation")
    private void paint() {
        CarState state = client.state();
        Boolean reported = state.utilityOn;
        boolean on = Boolean.TRUE.equals(reported);
        boolean charging = state.charging;
        boolean fresh = sawCar && state.isFresh(SystemClock.uptimeMillis());

        if (Boolean.FALSE.equals(reported)) {
            // The car has reported a stopped Utility Mode, so any tap waiting on it is over. The
            // flag must not outlive the tap, or the screen would stop following the car.
            driverChose = false;
        }

        if (screen == Screen.STARTING) {
            if (on) {
                screen = Screen.RUNNING;
            } else if (charging) {
                // The VCU refuses Utility Mode while charging; abort the start wait immediately.
                writing = false;
                driverChose = false;
                screen = Screen.OFF;
            }
        } else if (screen == Screen.CONNECTING) {
            // Nothing is claimed until the first report; the tick ends the wait if none arrives.
            if (reported != null) {
                screen = on ? Screen.RUNNING : Screen.OFF;
            }
        } else if (!driverChose) {
            // Launch, a failed enable, or car status change: the car's report decides.
            // When Utility Mode drops mid-session while running, stay in RUNNING (the dropped
            // state: scene visible, amber strip raised via warn, status shows dropped, toggle
            // label offers enable again since on is false). Only a driver tap (stop()) takes the
            // screen from RUNNING to OFF.
            if (on) {
                screen = Screen.RUNNING;
            } else if (screen != Screen.RUNNING) {
                screen = Screen.OFF;
            }
        }
        if (screen == Screen.RUNNING) {
            failed = false;
        }

        boolean blockedByCharge = charging && !on;
        // Once Utility Mode is on, the head unit cannot turn it off (the car must be power-cycled),
        // so the button is disabled while running and only re-enables if Utility Mode drops.
        toggle.setEnabled(!on && !writing && !blockedByCharge
                && screen != Screen.STARTING && screen != Screen.CONNECTING);
        // The label follows the car, not the scene: after a dropped Utility Mode the scene stays
        // up but the button offers the enable again.
        toggle.setText(screen == Screen.STARTING ? R.string.toggle_starting
                : on ? R.string.toggle_on : R.string.toggle_off);

        boolean active = screen == Screen.RUNNING && on;
        countdownCaption.setText(active
                ? R.string.countdown_caption : R.string.countdown_caption_inactive);

        // The countdown is only claimed on the left panel while the scene is up; elsewhere it is
        // not shown there (the pre-flight card on the right shows the battery preview instead).
        int estMinutes = fresh ? estimateMinutes(state) : -1;
        int countdownVisibility = screen == Screen.RUNNING ? View.VISIBLE : View.INVISIBLE;
        countdown.setVisibility(countdownVisibility);
        String remaining = fresh ? Countdown.format(estMinutes)
                : getString(R.string.countdown_unknown);
        countdown.setText(remaining);
        int batteryIconId = batteryDrawableId(Countdown.batteryIcon(estMinutes));
        if (batteryIconId != currentBatteryDrawableId) {
            currentBatteryDrawableId = batteryIconId;
            countdown.setCompoundDrawablesWithIntrinsicBounds(batteryIconId, 0, 0, 0);
        }

        // The set-point is only claimed while the climate is actually being run: a dropped Utility
        // Mode still has a set-point, but "A/C set to 73°F" over a stopped climate would mislead
        // anyone reading it through the window. Stale or implausible values hide the line too.
        String setTo = Setpoint.format(state.setpointCelsius, state.setpointFahrenheit,
                state.isCelsius);
        boolean showSetpoint = screen == Screen.RUNNING && on && fresh && setTo != null;
        if (showSetpoint) {
            setpoint.setText(getString(R.string.setpoint, setTo));
        }
        setpoint.setVisibility(showSetpoint ? View.VISIBLE : View.INVISIBLE);

        // Right pane: show the animated dog scene only while Utility Mode is running; otherwise
        // show the pre-flight summary cards and checklist.
        scene.setVisibility(screen == Screen.RUNNING ? View.VISIBLE : View.INVISIBLE);
        preflight.setVisibility(screen == Screen.RUNNING ? View.GONE : View.VISIBLE);
        if (screen != Screen.RUNNING) {
            preflightClimateValue.setText(fresh && setTo != null
                    ? setTo : getString(R.string.countdown_unknown));
            String passengerSetTo = Setpoint.format(state.passengerSetpointCelsius,
                    state.passengerSetpointFahrenheit, state.isCelsius);
            String outside = Setpoint.formatAmbient(state.ambientCelsius,
                    state.ambientFahrenheit, state.isCelsius);
            if (fresh && passengerSetTo != null && outside != null) {
                preflightClimateSub.setText(getString(
                        R.string.preflight_climate_passenger_outside, passengerSetTo, outside));
            } else if (fresh && passengerSetTo != null) {
                preflightClimateSub.setText(getString(
                        R.string.preflight_climate_passenger, passengerSetTo));
            } else if (fresh && setTo != null && outside != null) {
                preflightClimateSub.setText(getString(
                        R.string.preflight_climate_driver_only_outside, outside));
            } else if (fresh && setTo != null) {
                preflightClimateSub.setText(R.string.preflight_climate_driver_only);
            } else if (fresh && outside != null) {
                preflightClimateSub.setText(getString(
                        R.string.preflight_climate_outside, outside));
            } else {
                preflightClimateSub.setText(R.string.preflight_climate_default_sub);
            }

            String heroRuntime = !fresh ? getString(R.string.countdown_unknown)
                    : estMinutes == 0 ? getString(R.string.preflight_battery_zero_minutes)
                    : Countdown.format(estMinutes);
            preflightBatteryValue.setText(heroRuntime);
            boolean lowRuntime = fresh && Estimate.isLowRuntime(estMinutes);
            preflightBatteryValue.setTextColor(getResources().getColor(
                    lowRuntime ? R.color.danger : R.color.ink));

            int cutoffInt = (int) Math.round(state.cutoffPercent);
            preflightLimitButton.setText(getString(
                    R.string.preflight_battery_limit_button, Integer.valueOf(cutoffInt)));
            int usable = fresh
                    ? Estimate.usableSocPercent(state.socPercent, state.cutoffPercent) : -1;
            if (usable >= 0) {
                int socInt = (int) Math.round(state.socPercent);
                preflightBatterySub.setText(getString(R.string.preflight_battery_usable_prefix,
                        Integer.valueOf(usable), Integer.valueOf(socInt)));
                preflightBatterySubClose.setVisibility(View.VISIBLE);
            } else {
                preflightBatterySub.setText(R.string.preflight_battery_limit_prefix);
                preflightBatterySubClose.setVisibility(View.GONE);
            }

            preflightCheckCharger.setText(charging
                    ? R.string.preflight_check_charging
                    : R.string.preflight_check_unplugged);
            preflightCheckCharger.setTextColor(getResources().getColor(
                    charging ? R.color.amber : R.color.ready));

            Boolean readyAndParked = fresh ? state.isReadyAndParked() : null;
            if (Boolean.TRUE.equals(readyAndParked)) {
                preflightCheckReady.setText(R.string.preflight_check_ready_ok);
                preflightCheckReady.setTextColor(getResources().getColor(R.color.ready));
            } else if (Boolean.FALSE.equals(readyAndParked)) {
                preflightCheckReady.setText(R.string.preflight_check_ready_warn);
                preflightCheckReady.setTextColor(getResources().getColor(R.color.amber));
            } else {
                preflightCheckReady.setText(R.string.preflight_check_ready);
                preflightCheckReady.setTextColor(getResources().getColor(R.color.ink));
            }

            if (!autoUnlock.isEnabled()) {
                preflightAutoUnlockButton.setText(R.string.preflight_auto_unlock_off);
            } else {
                String btName = autoUnlock.targetName();
                int rssi = bluetooth.targetRssiDbm();
                if (bluetooth.isTargetConnected() && rssi != AutoUnlock.NO_RSSI) {
                    preflightAutoUnlockButton.setText(getString(
                            R.string.preflight_auto_unlock_on_rssi,
                            btName, Integer.valueOf(rssi)));
                } else if (bluetooth.isTargetConnected()) {
                    preflightAutoUnlockButton.setText(getString(
                            R.string.preflight_auto_unlock_on_connected, btName));
                } else {
                    preflightAutoUnlockButton.setText(getString(
                            R.string.preflight_auto_unlock_on_away, btName));
                }
            }
        }

        long now = SystemClock.uptimeMillis();
        if (autoUnlock.update(
                screen == Screen.RUNNING,
                bluetooth.isTargetConnected(),
                bluetooth.targetRssiDbm(),
                now)) {
            triggerDoorUnlock();
        }
        if (screen == Screen.RUNNING && autoUnlock.isEnabled()) {
            String btName = autoUnlock.targetName();
            boolean btConnected = bluetooth.isTargetConnected();
            int rssi = bluetooth.targetRssiDbm();
            int readyColor = getResources().getColor(R.color.ready);
            int amberColor = getResources().getColor(R.color.amber);
            int inkColor = getResources().getColor(R.color.ink);
            int mutedColor = getResources().getColor(R.color.muted);

            autoUnlockPanel.setVisibility(View.VISIBLE);
            int barColor;
            if (autoUnlock.recentlyUnlocked(now)) {
                autoUnlockStatus.setText(getString(R.string.auto_unlock_state_unlocked, btName));
                autoUnlockStatus.setTextColor(readyColor);
                barColor = readyColor;
            } else if (autoUnlock.state() == AutoUnlock.State.ARMED) {
                autoUnlockStatus.setText(getString(R.string.auto_unlock_state_armed, btName));
                autoUnlockStatus.setTextColor(readyColor);
                barColor = readyColor;
            } else if (autoUnlock.state() == AutoUnlock.State.LEAVING) {
                autoUnlockStatus.setText(getString(R.string.auto_unlock_state_leaving, btName));
                autoUnlockStatus.setTextColor(amberColor);
                barColor = amberColor;
            } else {
                autoUnlockStatus.setText(getString(R.string.auto_unlock_state_near, btName));
                autoUnlockStatus.setTextColor(inkColor);
                barColor = inkColor;
            }

            if (btConnected && rssi != AutoUnlock.NO_RSSI) {
                autoUnlockRssi.setText(getString(
                        R.string.auto_unlock_rssi_dbm, Integer.valueOf(rssi)));
                autoUnlockRssi.setTextColor(inkColor);
            } else if (btConnected) {
                autoUnlockRssi.setText(R.string.auto_unlock_rssi_connected);
                autoUnlockRssi.setTextColor(mutedColor);
            } else {
                autoUnlockRssi.setText(R.string.auto_unlock_rssi_away);
                autoUnlockRssi.setTextColor(mutedColor);
            }

            double barFraction = AutoUnlock.rssiToFraction(btConnected, rssi);
            autoUnlockBar.setState(barFraction, barColor);
        } else {
            autoUnlockPanel.setVisibility(View.GONE);
        }

        int statusId;
        if (screen == Screen.CONNECTING) {
            // Same wording as an enable in flight: the screen is waiting for the car either way.
            statusId = R.string.status_starting;
        } else if (screen == Screen.OFF) {
            statusId = charging ? R.string.status_charging
                    : failed ? R.string.status_failed : R.string.status_off;
        } else if (screen == Screen.STARTING) {
            statusId = fresh ? R.string.status_starting : R.string.status_stale;
        } else if (!fresh) {
            statusId = R.string.status_stale;
        } else {
            statusId = on ? R.string.status_running
                    : charging ? R.string.status_charging : R.string.status_dropped;
        }
        status.setText(statusId);
        boolean statusWarn = (screen == Screen.OFF && (charging || failed))
                || (screen == Screen.RUNNING && (!on || !fresh));
        status.setTextColor(getResources().getColor(statusWarn ? R.color.amber : R.color.muted));
        boolean hideNormalRunningStatus = (screen == Screen.RUNNING && on && fresh);
        status.setVisibility(hideNormalRunningStatus ? View.GONE : View.VISIBLE);

        // The amber strip is the only warning this version has: Utility Mode dropped while the
        // scene is up, or the callbacks stopped so the panel can no longer be trusted. It is
        // deliberately not raised in `off`, where the screen claims nothing, nor in `connecting`
        // or `starting`, which have their own deadline.
        boolean warn = screen == Screen.RUNNING && (!on || !fresh);
        strip.setVisibility(warn ? View.VISIBLE : View.GONE);

        // The dismiss button belongs to the dropped warning alone: a stale warning cannot be
        // acknowledged away, because the screen does not know what the car is doing.
        boolean dropped = screen == Screen.RUNNING && Boolean.FALSE.equals(reported);
        dismiss.setVisibility(dropped && !writing ? View.VISIBLE : View.GONE);

        // The artwork only moves while dog mode is actually running and trusted; otherwise it is a
        // still frame, and when the screen is off the whole scene is invisible anyway.
        boolean liveScene = screen == Screen.RUNNING && !warn;
        sceneBanner.setVisibility(liveScene ? View.VISIBLE : View.INVISIBLE);
        setSceneLive(liveScene);
    }

    @SuppressWarnings("deprecation")
    private void showAutoUnlockDialog() {
        final List<BluetoothMonitor.DeviceInfo> devices = bluetooth.pairedDevices();
        int checked = 0;
        String currentAddr = autoUnlock.targetAddress();
        for (int i = 0; i < devices.size(); i++) {
            BluetoothMonitor.DeviceInfo d = devices.get(i);
            if (currentAddr != null && currentAddr.equalsIgnoreCase(d.address)) {
                checked = i + 1;
            }
        }

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        int padH = dp(28);
        int padV = dp(24);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_backdrop);
        card.setPadding(padH, padV, padH, padV);
        card.setMinimumWidth(dp(520));

        TextView title = new TextView(this);
        title.setText(R.string.auto_unlock_dialog_title);
        title.setAllCaps(true);
        title.setTextColor(getResources().getColor(R.color.muted));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp(12);
        card.addView(title, titleLp);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        int inkColor = getResources().getColor(R.color.ink);
        int readyColor = getResources().getColor(R.color.ready);
        int dividerColor = 0xFF232D38;

        int totalItems = devices.size() + 1;
        for (int i = 0; i < totalItems; i++) {
            if (i > 0) {
                View divider = new View(this);
                divider.setBackgroundColor(dividerColor);
                list.addView(divider, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            }
            final int index = i;
            boolean isSelected = (i == checked);
            String label;
            if (i == 0) {
                label = getString(R.string.auto_unlock_option_off);
            } else {
                BluetoothMonitor.DeviceInfo d = devices.get(i - 1);
                label = d.connected
                        ? getString(R.string.auto_unlock_device_connected, d.name)
                        : d.name;
            }
            TextView row = new TextView(this);
            row.setText((isSelected ? "✓   " : "○   ") + label);
            row.setTextColor(isSelected ? readyColor : inkColor);
            row.setTypeface(null, isSelected ? Typeface.BOLD : Typeface.NORMAL);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinHeight(dp(56));
            row.setPadding(dp(8), dp(12), dp(8), dp(12));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (index <= 0 || index > devices.size()) {
                        bluetooth.selectDevice(null, null);
                        autoUnlock.setTarget(null, null);
                    } else {
                        BluetoothMonitor.DeviceInfo chosen = devices.get(index - 1);
                        bluetooth.selectDevice(chosen.address, chosen.name);
                        autoUnlock.setTarget(chosen.address, chosen.name);
                    }
                    dialog.dismiss();
                    paint();
                }
            });
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        scroll.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(card);
        dialog.show();
    }

    @SuppressWarnings("deprecation")
    private void showDischargeLimitDialog() {
        CarState state = client.state();
        boolean fresh = sawCar && state.isFresh(SystemClock.uptimeMillis());
        int currentCutoff = (int) Math.round(state.cutoffPercent);
        double soc = fresh ? state.socPercent : Double.NaN;

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        int padH = dp(28);
        int padV = dp(24);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_backdrop);
        card.setPadding(padH, padV, padH, padV);
        card.setMinimumWidth(dp(460));

        TextView title = new TextView(this);
        title.setText(R.string.discharge_limit_dialog_title);
        title.setAllCaps(true);
        title.setTextColor(getResources().getColor(R.color.muted));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp(12);
        card.addView(title, titleLp);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        int inkColor = getResources().getColor(R.color.ink);
        int readyColor = getResources().getColor(R.color.ready);
        int dividerColor = 0xFF232D38;

        int[] presets = Estimate.CUTOFF_PRESETS_PERCENT;
        for (int i = 0; i < presets.length; i++) {
            if (i > 0) {
                View divider = new View(this);
                divider.setBackgroundColor(dividerColor);
                list.addView(divider, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            }
            final int preset = presets[i];
            boolean isSelected = (preset == currentCutoff);
            int usable = Estimate.usableSocPercent(soc, preset);
            String label = usable >= 0
                    ? getString(R.string.discharge_limit_option_with_usable,
                            Integer.valueOf(preset), Integer.valueOf(usable))
                    : getString(R.string.discharge_limit_option, Integer.valueOf(preset));
            TextView row = new TextView(this);
            row.setText((isSelected ? "✓   " : "○   ") + label);
            row.setTextColor(isSelected ? readyColor : inkColor);
            row.setTypeface(null, isSelected ? Typeface.BOLD : Typeface.NORMAL);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinHeight(dp(52));
            row.setPadding(dp(8), dp(10), dp(8), dp(10));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    writeDischargeLimit(preset);
                }
            });
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        scroll.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(card);
        dialog.show();
    }

    private void writeDischargeLimit(final int percent) {
        client.state().cutoffPercent = percent;
        paint();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    client.setDischargeLimitPercent(percent);
                } catch (RuntimeException e) {
                    Log.w(TAG, "setDischargeLimitPercent(" + percent + ") failed", e);
                }
            }
        }, "sambok-limit").start();
    }

    private static int batteryDrawableId(Countdown.BatteryIcon icon) {
        switch (icon) {
            case HALF:
                return R.drawable.battery_icon_half;
            case AMBER:
                return R.drawable.battery_icon_amber;
            case RED:
                return R.drawable.battery_icon_red;
            case FULL:
            default:
                return R.drawable.battery_icon;
        }
    }

    /**
     * Draws a downward caret triangle on Canvas so the discharge limit button never depends on the
     * head unit's API 19 system font having {@code U+25BE} ({@code ▾}).
     */
    @SuppressWarnings("deprecation")
    private Drawable createDropdownArrowDrawable() {
        final int w = dp(10);
        final int h = dp(6);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(getResources().getColor(R.color.ink));
        final Path path = new Path();
        return new Drawable() {
            @Override
            public void draw(Canvas canvas) {
                android.graphics.Rect b = getBounds();
                path.reset();
                path.moveTo(b.left, b.top);
                path.lineTo(b.right, b.top);
                path.lineTo(b.exactCenterX(), b.bottom);
                path.close();
                canvas.drawPath(path, paint);
            }

            @Override
            public int getIntrinsicWidth() {
                return w;
            }

            @Override
            public int getIntrinsicHeight() {
                return h;
            }

            @Override
            public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(ColorFilter colorFilter) {
                paint.setColorFilter(colorFilter);
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void triggerDoorUnlock() {
        Log.i(TAG, "Auto-unlock triggered for " + autoUnlock.targetName());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    client.unlockDoors();
                } catch (RuntimeException e) {
                    Log.w(TAG, "unlockDoors failed", e);
                }
            }
        }, "sambok-unlock").start();
    }
}
