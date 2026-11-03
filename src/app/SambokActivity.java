package com.sambok;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

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
 * (the dog scene, with the toggle reflecting what the car reports).
 *
 * <p>There is no temperature field and no colour state: this firmware publishes no cabin
 * reading, so {@link Panel} is not used here.
 */
public class SambokActivity extends Activity {

    private static final String TAG = "Sambok";

    /**
     * State of charge, in percent, at which the car stops discharging: the V2X discharge limit.
     *
     * <p>The car publishes no getter for it, so it is written here by hand and must match the
     * value the car's own EV screen shows. This is the one number on the screen to tune on the
     * car; the countdown is meaningless if it is wrong.
     */
    private static final double CUTOFF_PERCENT = 20.0;

    /** The pack's usable capacity in kWh for this car; a constant, not read from the car. */
    private static final double CAPACITY_KWH = 77.4;

    /** How long an enable may take to read back as on before the screen calls it a failure. */
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
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Button toggle;
    private TextView countdown;
    private TextView countdownCaption;
    private TextView status;
    private View strip;
    private View scene;

    private Screen screen = Screen.CONNECTING;
    /** A car callback has arrived, so staleness and the countdown mean something. */
    private boolean sawCar;
    /**
     * The driver has tapped: the screen follows the car only when this is false. Cleared as soon
     * as the car reports a stopped Utility Mode, so it survives a pending tap and no longer.
     */
    private boolean driverChose;
    /** A Utility Mode write is in flight; the toggle stays closed until it lands. */
    private boolean writing;
    /** The last enable did not read back as on within the deadline. */
    private boolean failed;
    private long connectAtMs;
    private long startingAtMs;
    private int ticks;

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
        countdown = (TextView) findViewById(R.id.countdown);
        countdownCaption = (TextView) findViewById(R.id.countdown_caption);
        status = (TextView) findViewById(R.id.status);
        strip = findViewById(R.id.strip);
        scene = findViewById(R.id.scene);

        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                tap();
            }
        });

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

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        client.unbind();
        super.onDestroy();
    }

    /** Runs on the main thread whenever a car callback lands. */
    private final Runnable onCarChange = new Runnable() {
        @Override
        public void run() {
            sawCar = true;
            paint();
        }
    };

    /**
     * The one-second tick: staleness, the countdown, the enable deadline.
     *
     * <p>It reads the last {@link CarState} and nothing else. No binder call is made here, so a
     * car service that has stopped answering cannot freeze the screen or the clock.
     */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            ticks++;
            CarState state = client.state();
            boolean fresh = sawCar && state.isFresh(SystemClock.uptimeMillis());
            if (fresh && screen == Screen.RUNNING && ticks % TICKS_PER_SAMPLE == 0) {
                Countdown.sample(state.drawKw);
            }
            long now = SystemClock.uptimeMillis();
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

    /** One tap, one write: the enable or the disable, never both, never on a press-and-hold. */
    private void tap() {
        if (writing || screen == Screen.STARTING || screen == Screen.CONNECTING) {
            return;
        }
        if (Boolean.TRUE.equals(client.state().utilityOn)) {
            stop();
        } else {
            start();
        }
    }

    private void start() {
        Log.i(TAG, "tap: enable");
        failed = false;
        driverChose = true;
        writing = true;
        screen = Screen.STARTING;
        startingAtMs = SystemClock.uptimeMillis();
        paint();
        writeUtility(true);
    }

    private void stop() {
        Log.i(TAG, "tap: disable");
        failed = false;
        driverChose = true;
        writing = true;
        screen = Screen.OFF;
        paint();
        writeUtility(false);
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
        writing = false;
        if (on && !confirmed) {
            // The car may still report it on: the deadline in the tick owns the outcome.
            Log.i(TAG, "enable not confirmed yet");
        } else if (!on && !confirmed) {
            // The car did not take the disable, so the screen follows what the car reports
            // rather than claiming an off state that is not there.
            driverChose = false;
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
    private void paint() {
        CarState state = client.state();
        Boolean reported = state.utilityOn;
        boolean on = Boolean.TRUE.equals(reported);
        boolean fresh = sawCar && state.isFresh(SystemClock.uptimeMillis());

        if (Boolean.FALSE.equals(reported)) {
            // The car has reported a stopped Utility Mode, so any tap waiting on it is over. The
            // flag must not outlive the tap, or the screen would stop following the car.
            driverChose = false;
        }

        if (screen == Screen.STARTING) {
            if (on) {
                screen = Screen.RUNNING;
            }
        } else if (screen == Screen.CONNECTING) {
            // Nothing is claimed until the first report; the tick ends the wait if none arrives.
            if (reported != null) {
                screen = on ? Screen.RUNNING : Screen.OFF;
            }
        } else if (!driverChose) {
            // Launch, a failed enable or a failed disable: the car's report decides.
            screen = on ? Screen.RUNNING : Screen.OFF;
        }
        if (screen == Screen.RUNNING) {
            failed = false;
        }

        toggle.setEnabled(!writing && screen != Screen.STARTING && screen != Screen.CONNECTING);
        // The label follows the car, not the scene: after a dropped Utility Mode the scene stays
        // up but the button offers the enable again.
        toggle.setText(screen == Screen.STARTING ? R.string.toggle_starting
                : on ? R.string.toggle_on : R.string.toggle_off);

        // The countdown is only claimed while the scene is up; elsewhere it is not shown.
        int countdownVisibility = screen == Screen.RUNNING ? View.VISIBLE : View.INVISIBLE;
        countdownCaption.setVisibility(countdownVisibility);
        countdown.setVisibility(countdownVisibility);
        countdown.setText(fresh
                ? Countdown.format(Countdown.remainingMinutes(state.socPercent, CUTOFF_PERCENT,
                        CAPACITY_KWH))
                : getString(R.string.countdown_unknown));

        scene.setVisibility(screen == Screen.RUNNING ? View.VISIBLE : View.INVISIBLE);

        int statusId;
        if (screen == Screen.CONNECTING) {
            // Same wording as an enable in flight: the screen is waiting for the car either way.
            statusId = R.string.status_starting;
        } else if (screen == Screen.OFF) {
            statusId = failed ? R.string.status_failed : R.string.status_off;
        } else if (screen == Screen.STARTING) {
            statusId = fresh ? R.string.status_starting : R.string.status_stale;
        } else if (!fresh) {
            statusId = R.string.status_stale;
        } else {
            statusId = on ? R.string.status_running : R.string.status_dropped;
        }
        status.setText(statusId);

        // The amber strip is the only warning this version has: Utility Mode dropped while the
        // scene is up, or the callbacks stopped so the panel can no longer be trusted. It is
        // deliberately not raised in `off`, where the screen claims nothing, nor in `connecting`
        // or `starting`, which have their own deadline.
        boolean warn = screen == Screen.RUNNING && (!on || !fresh);
        strip.setVisibility(warn ? View.VISIBLE : View.GONE);
    }
}
