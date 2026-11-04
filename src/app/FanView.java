package com.sambok;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * A small desk fan at the left of the scene with the breeze drifting right across the dog.
 *
 * <p>No assets and no animator: the phase advances from the clock inside {@link #onDraw} and the
 * view re-schedules itself only while {@link #setBlowing(boolean)} is true. Stopped, it draws the
 * same still frame, which is what the screen shows whenever the amber strip is up.
 */
public class FanView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean blowing;
    private float phase;
    private long lastMs;

    public FanView(Context context, AttributeSet attrs) {
        super(context, attrs);
        lastMs = SystemClock.uptimeMillis();
    }

    /** Runs the fan and the breeze while {@code on}; a still frame otherwise. */
    public void setBlowing(boolean on) {
        if (blowing == on) {
            return;
        }
        blowing = on;
        lastMs = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.1f, (now - lastMs) / 1000f);
        lastMs = now;
        if (blowing) {
            phase += dt * 2.2f;
        }

        float w = getWidth();
        float h = getHeight();
        float cx = h * 0.44f;
        float cy = h * 0.46f;
        float r = h * 0.27f;

        // base and stem
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF3A4652);
        canvas.drawRect(cx - r * 0.13f, cy + r * 0.72f, cx + r * 0.13f, h * 0.90f, paint);
        RectF base = new RectF(cx - r * 0.95f, h * 0.90f, cx + r * 0.95f, h * 0.98f);
        canvas.drawRoundRect(base, r * 0.18f, r * 0.18f, paint);

        // cage
        paint.setColor(0xFF1B232B);
        canvas.drawCircle(cx, cy, r, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, r * 0.06f));
        paint.setColor(0xFF6E7C8A);
        canvas.drawCircle(cx, cy, r, paint);
        canvas.drawCircle(cx, cy, r * 0.64f, paint);

        // blades
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF9DAAB8);
        for (int i = 0; i < 4; i++) {
            double a = phase + i * (Math.PI / 2);
            float bx = cx + (float) Math.cos(a) * r * 0.33f;
            float by = cy + (float) Math.sin(a) * r * 0.33f;
            canvas.save();
            canvas.rotate((float) Math.toDegrees(a), bx, by);
            RectF blade = new RectF(bx - r * 0.10f, by - r * 0.30f, bx + r * 0.10f, by + r * 0.30f);
            canvas.drawOval(blade, paint);
            canvas.restore();
        }
        paint.setColor(0xFF2A333D);
        canvas.drawCircle(cx, cy, r * 0.13f, paint);

        // breeze, drifting right and fading as it goes
        if (blowing) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, r * 0.055f));
            float fromX = cx + r * 1.08f;
            float span = Math.max(0f, w - fromX - r * 0.4f);
            for (int i = 0; i < 3; i++) {
                float t = (phase * 0.18f + i * 0.33f) % 1f;
                int alpha = (int) (120f * (1f - Math.abs(t - 0.5f) * 2f));
                if (alpha <= 0) {
                    continue;
                }
                paint.setColor(Color.argb(alpha, 0xBF, 0xCB, 0xD8));
                float x0 = fromX + t * span;
                float y0 = cy - r * 0.42f + i * r * 0.42f;
                RectF arc = new RectF(x0, y0, x0 + r * 0.55f, y0 + r * 0.55f);
                canvas.drawArc(arc, -75f, 150f, false, paint);
            }
            postInvalidateOnAnimation();
        }
    }
}
