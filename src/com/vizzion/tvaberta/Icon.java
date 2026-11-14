package com.vizzion.tvaberta;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

// White line icons for the phone player, drawn on a 100x100 grid (no image files): sun (brightness),
// speaker (volume), and a padlock open or closed.
final class Icon extends Drawable {
    static final int SUN = 0, SPEAKER = 1, LOCK_OPEN = 2, LOCK_CLOSED = 3;

    private final int type;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    Icon(int type) {
        this.type = type;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(0xFFFFFFFF);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFFFFFFF);
    }

    @Override
    public void draw(Canvas c) {
        float s = Math.min(getBounds().width(), getBounds().height()) / 100f;
        line.setStrokeWidth(8 * s);
        c.save();
        c.translate(getBounds().exactCenterX() - 50 * s, getBounds().exactCenterY() - 50 * s);
        switch (type) {
            case SUN:
                c.drawCircle(50 * s, 50 * s, 17 * s, line);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    float cx = (float) Math.cos(a), sy = (float) Math.sin(a);
                    c.drawLine((50 + cx * 30) * s, (50 + sy * 30) * s, (50 + cx * 42) * s, (50 + sy * 42) * s, line);
                }
                break;
            case SPEAKER:
                path.reset();
                path.moveTo(10 * s, 38 * s);
                path.lineTo(28 * s, 38 * s);
                path.lineTo(50 * s, 18 * s);
                path.lineTo(50 * s, 82 * s);
                path.lineTo(28 * s, 62 * s);
                path.lineTo(10 * s, 62 * s);
                path.close();
                c.drawPath(path, fill);
                for (int i = 0; i < 2; i++) {
                    float rad = (16 + i * 16) * s;
                    r.set(54 * s - rad, 50 * s - rad, 54 * s + rad, 50 * s + rad);
                    c.drawArc(r, -45, 90, false, line);
                }
                break;
            default: // padlock: body, keyhole, and the shackle (lifted and turned aside when open)
                r.set(20 * s, 46 * s, 80 * s, 92 * s);
                c.drawRoundRect(r, 9 * s, 9 * s, fill);
                if (type == LOCK_CLOSED) {
                    r.set(32 * s, 12 * s, 68 * s, 56 * s);
                    c.drawArc(r, 180, 180, false, line);
                    c.drawLine(32 * s, 34 * s, 32 * s, 46 * s, line);
                    c.drawLine(68 * s, 34 * s, 68 * s, 46 * s, line);
                } else {
                    r.set(50 * s, 6 * s, 86 * s, 46 * s);
                    c.drawArc(r, 180, 180, false, line);
                    c.drawLine(50 * s, 26 * s, 50 * s, 46 * s, line);
                    c.drawLine(86 * s, 26 * s, 86 * s, 34 * s, line);
                }
                Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
                hole.setColor(0xFF000000);
                c.drawCircle(50 * s, 66 * s, 6 * s, hole);
                c.drawRect(47 * s, 66 * s, 53 * s, 80 * s, hole);
        }
        c.restore();
    }

    @Override public void setAlpha(int a) { line.setAlpha(a); fill.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { line.setColorFilter(cf); fill.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
