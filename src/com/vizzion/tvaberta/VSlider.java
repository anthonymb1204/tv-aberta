package com.vizzion.tvaberta;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

// A vertical bar for the phone player (brightness on the left, volume on the right): a pill-shaped
// track that fills from the bottom. Touch or drag anywhere on it to set the level, 0 to 1.
final class VSlider extends View {
    interface Listener { void changed(float value); }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), level = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float value;
    private Listener listener;
    private Runnable touched; // told on every touch, so the controls stay up while you drag

    VSlider(Context c) {
        super(c);
        track.setColor(0x4DFFFFFF);
        level.setColor(0xFFFFFFFF);
    }

    void setListener(Listener l) { listener = l; }
    void onTouched(Runnable r) { touched = r; }

    void setValue(float v) {
        value = Math.max(0f, Math.min(1f, v));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), bar = Math.min(w, getResources().getDisplayMetrics().density * 6);
        float left = (w - bar) / 2f;
        r.set(left, 0, left + bar, h);
        c.drawRoundRect(r, bar / 2, bar / 2, track);
        r.set(left, h * (1 - value), left + bar, h);
        c.drawRoundRect(r, bar / 2, bar / 2, level);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
            getParent().requestDisallowInterceptTouchEvent(true);
            setValue(1f - e.getY() / Math.max(1, getHeight()));
            if (listener != null) listener.changed(value);
        }
        if (touched != null) touched.run();
        return true;
    }
}
