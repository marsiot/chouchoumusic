package com.chouchou.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

public class EqualizerView extends View {

    private final Paint paint;
    private boolean active;
    private final long t0;

    public EqualizerView(Context context) {
        this(context, null);
    }

    public EqualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ContextCompat.getColor(context, R.color.accent));
        t0 = System.currentTimeMillis();
    }

    public void setActive(boolean active) {
        if (this.active == active) {
            if (active) postInvalidateOnAnimation();
            return;
        }
        this.active = active;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        int bars = 3;
        float gap = w * 0.18f;
        float bw = (w - gap * (bars - 1)) / bars;
        float radius = bw * 0.5f;
        long t = System.currentTimeMillis() - t0;
        for (int i = 0; i < bars; i++) {
            float frac;
            if (active) {
                double phase = i * 1.9;
                frac = (float) (0.30 + 0.55
                        * (0.5 - 0.5 * Math.cos((t / 240.0) * 2 * Math.PI + phase)));
            } else {
                frac = 0.30f;
            }
            float bh = h * frac;
            float x = i * (bw + gap);
            float y = h - bh;
            canvas.drawRoundRect(x, y, x + bw, h, radius, radius, paint);
        }
        if (active) postInvalidateOnAnimation();
    }
}
