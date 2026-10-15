package com.pour.dictation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The big coral record button. The icon is drawn as a canvas shape at the
 * exact geometric center of the circle — never a text glyph, because font
 * glyphs carry invisible bearings that make them sit slightly off-center.
 */
public class RecordButton extends View {
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean recording;

    public RecordButton(Context context) {
        super(context);
        bgPaint.setColor(Ui.ACCENT);
        iconPaint.setColor(Ui.ON_ACCENT);
        setClickable(true);
        setFocusable(true);
    }

    public void setRecording(boolean recording) {
        if (this.recording != recording) {
            this.recording = recording;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float d = Math.min(w, h);

        // Coral circle, full-bleed like the old oval background.
        canvas.drawCircle(cx, cy, d / 2f, bgPaint);

        if (recording) {
            // Stop square, centered, slightly rounded corners.
            float half = d * 0.16f;
            rect.set(cx - half, cy - half, cx + half, cy + half);
            float r = d * 0.03f;
            canvas.drawRoundRect(rect, r, r, iconPaint);
        } else {
            // Record dot, centered.
            canvas.drawCircle(cx, cy, d * 0.15f, iconPaint);
        }
    }
}
