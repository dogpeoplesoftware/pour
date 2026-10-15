package com.pour.dictation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayDeque;

/**
 * Minimalist real-time audio waveform. A single thin scrolling line,
 * like iOS Voice Memos. Transparent background, muted grey-blue when
 * idle, vibrant blue when voice is detected.
 */
public class WaveformView extends View {

    private static final int MAX_POINTS = 120;

    // Muted grey-blue (idle) -> vibrant iOS blue (voice)
    private static final int COLOR_IDLE = 0xFF9DB3C9;
    private static final int COLOR_ACTIVE = 0xFF0A84FF;

    private final ArrayDeque<Float> levels = new ArrayDeque<Float>();
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private long lastUpdate = 0;
    private boolean active = false;
    /** Smoothing buffer for jitter reduction (moving average). */
    private final float[] smoothBuf = new float[2];
    private int smoothIdx = 0;

    public WaveformView(Context ctx) {
        super(ctx);
        init();
    }

    public WaveformView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init();
    }

    private void init() {
        linePaint.setColor(COLOR_IDLE);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(3f);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        for (int i = 0; i < MAX_POINTS; i++) {
            levels.add(0f);
        }
        // Transparent — blends into the card.
        setBackgroundColor(0x00000000);
    }

    /**
     * Push a new RMS level (0.0 to 1.0). Call from the audio thread.
     * Applies moving average smoothing to reduce jitter.
     */
    public void pushLevel(float rms) {
        // Light smoothing for responsiveness.
        smoothBuf[smoothIdx] = rms;
        smoothIdx = (smoothIdx + 1) % smoothBuf.length;
        float sum = 0;
        for (float v : smoothBuf) sum += v;
        float avg = sum / smoothBuf.length;
        float v = Math.max(0f, Math.min(1f, avg * 6f));
        v = (float) Math.sqrt(v);
        synchronized (levels) {
            levels.removeFirst();
            levels.addLast(v);
        }
        long now = System.currentTimeMillis();
        if (now - lastUpdate > 33) { // 30fps: smooth and responsive
            lastUpdate = now;
            postInvalidate();
        }
    }

    /** Clear the waveform. */
    public void clear() {
        synchronized (levels) {
            levels.clear();
            for (int i = 0; i < MAX_POINTS; i++) {
                levels.add(0f);
            }
        }
        postInvalidate();
    }

    /**
     * Set whether voice is actively detected. Muted grey-blue when idle,
     * vibrant blue when voice is heard.
     */
    public void setActive(boolean active) {
        if (this.active != active) {
            this.active = active;
            linePaint.setColor(active ? COLOR_ACTIVE : COLOR_IDLE);
            postInvalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float centerY = h / 2f;
        float stepX = (float) w / (MAX_POINTS - 1);

        path.reset();
        boolean first = true;
        int i = 0;
        synchronized (levels) {
            for (Float level : levels) {
                float x = i * stepX;
                // Draw a mirrored waveform: line goes up and down from center.
                float amp = level * (h / 2f) * 0.9f;
                float y = centerY - amp;
                // Alternate above/below center for a classic waveform look.
                if (i % 2 == 1) {
                    y = centerY + amp;
                }
                if (first) {
                    path.moveTo(x, y);
                    first = false;
                } else {
                    path.lineTo(x, y);
                }
                i++;
            }
        }
        canvas.drawPath(path, linePaint);
    }
}
