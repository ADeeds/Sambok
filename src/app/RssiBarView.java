package com.sambok;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

/**
 * Horizontal Bluetooth RSSI meter with tick marks and labels for the Arm and Unlock thresholds.
 */
public final class RssiBarView extends View {

    private static final int TRACK_FILL_COLOR = 0xFF182029;
    private static final int TRACK_STROKE_COLOR = 0xFF2B3745;
    private static final int TICK_HALO_COLOR = 0xFF0E1216;
    private static final int TICK_LINE_COLOR = 0xFFD0D8E2;
    private static final int LABEL_COLOR = 0xFF8D99A6;

    private final Paint trackFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF trackRect = new RectF();
    private final RectF fillRect = new RectF();
    private final Path clipPath = new Path();

    private float fillFraction;
    private int fillColor = 0xFF4ADE80;
    private String armLabel = "Arm";
    private String unlockLabel = "Unlock";

    public RssiBarView(Context context) {
        super(context);
        init();
    }

    public RssiBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RssiBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        trackFillPaint.setStyle(Paint.Style.FILL);
        trackFillPaint.setColor(TRACK_FILL_COLOR);

        trackStrokePaint.setStyle(Paint.Style.STROKE);
        trackStrokePaint.setStrokeWidth(dp(1.5f));
        trackStrokePaint.setColor(TRACK_STROKE_COLOR);

        barFillPaint.setStyle(Paint.Style.FILL);
        barFillPaint.setColor(fillColor);

        tickHaloPaint.setStyle(Paint.Style.STROKE);
        tickHaloPaint.setStrokeWidth(dp(4f));
        tickHaloPaint.setColor(TICK_HALO_COLOR);

        tickLinePaint.setStyle(Paint.Style.STROKE);
        tickLinePaint.setStrokeWidth(dp(2f));
        tickLinePaint.setColor(TICK_LINE_COLOR);

        labelPaint.setColor(LABEL_COLOR);
        labelPaint.setTextSize(sp(14f));
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setLabels(String arm, String unlock) {
        if (arm != null) {
            this.armLabel = arm;
        }
        if (unlock != null) {
            this.unlockLabel = unlock;
        }
        invalidate();
    }

    public void setState(double fraction, int color) {
        float clamped = (float) Math.max(0.0, Math.min(1.0, fraction));
        if (Math.abs(this.fillFraction - clamped) > 0.001f || this.fillColor != color) {
            this.fillFraction = clamped;
            this.fillColor = color;
            this.barFillPaint.setColor(color);
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = Math.round(dp(42f));
        int width = resolveSize(Math.round(dp(240f)), widthMeasureSpec);
        int height = resolveSize(desiredHeight, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        if (w <= 0) {
            return;
        }

        float strokeHalf = trackStrokePaint.getStrokeWidth() * 0.5f;
        float barHeight = dp(16f);
        float radius = barHeight * 0.5f;

        trackRect.set(strokeHalf, strokeHalf, w - strokeHalf, barHeight - strokeHalf);
        canvas.drawRoundRect(trackRect, radius, radius, trackFillPaint);

        if (fillFraction > 0f) {
            float fillRight = strokeHalf + (trackRect.width() * fillFraction);
            int save = canvas.save();
            clipPath.reset();
            clipPath.addRoundRect(trackRect, radius, radius, Path.Direction.CW);
            canvas.clipPath(clipPath);
            fillRect.set(trackRect.left, trackRect.top, fillRight, trackRect.bottom);
            canvas.drawRect(fillRect, barFillPaint);
            canvas.restoreToCount(save);
        }

        canvas.drawRoundRect(trackRect, radius, radius, trackStrokePaint);

        float armFrac = (float) AutoUnlock.dbmToFraction(AutoUnlock.FAR_THRESHOLD_DBM);
        float unlockFrac = (float) AutoUnlock.dbmToFraction(AutoUnlock.NEAR_THRESHOLD_DBM);
        float armX = trackRect.left + trackRect.width() * armFrac;
        float unlockX = trackRect.left + trackRect.width() * unlockFrac;

        float tickTop = 0f;
        float tickBottom = barHeight + dp(4f);
        canvas.drawLine(armX, tickTop, armX, tickBottom, tickHaloPaint);
        canvas.drawLine(armX, tickTop, armX, tickBottom, tickLinePaint);
        canvas.drawLine(unlockX, tickTop, unlockX, tickBottom, tickHaloPaint);
        canvas.drawLine(unlockX, tickTop, unlockX, tickBottom, tickLinePaint);

        Paint.FontMetrics fm = labelPaint.getFontMetrics();
        float labelBaseline = tickBottom + dp(3f) - fm.ascent;
        canvas.drawText(armLabel, armX, labelBaseline, labelPaint);
        canvas.drawText(unlockLabel, unlockX, labelBaseline, labelPaint);
    }

    private float dp(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private float sp(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
    }
}
