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
    static final int SUN = 0, SPEAKER = 1, LOCK_OPEN = 2, LOCK_CLOSED = 3, BACK = 4, CAST = 5, CAST_ON = 6,
            PLAY = 7, PAUSE = 8, PREV = 9, NEXT = 10;

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
            case BACK: // "<"
                path.reset();
                path.moveTo(62 * s, 16 * s);
                path.lineTo(28 * s, 50 * s);
                path.lineTo(62 * s, 84 * s);
                c.drawPath(path, line);
                break;
            case CAST:
            case CAST_ON: // the screen with the waves in its bottom-left corner
                path.reset();
                path.moveTo(10 * s, 36 * s);
                path.lineTo(10 * s, 20 * s);
                path.lineTo(90 * s, 20 * s);
                path.lineTo(90 * s, 80 * s);
                path.lineTo(54 * s, 80 * s);
                c.drawPath(path, line);
                for (int i = 0; i < 2; i++) {
                    float rad = (22 + i * 18) * s;
                    r.set(10 * s - rad, 80 * s - rad, 10 * s + rad, 80 * s + rad);
                    c.drawArc(r, -90, 90, false, line);
                }
                c.drawCircle(13 * s, 77 * s, 7 * s, fill);
                if (type == CAST_ON) c.drawRect(24 * s, 32 * s, 78 * s, 68 * s, fill);
                break;
            case PLAY:
                path.reset();
                path.moveTo(28 * s, 14 * s);
                path.lineTo(84 * s, 50 * s);
                path.lineTo(28 * s, 86 * s);
                path.close();
                c.drawPath(path, fill);
                break;
            case PAUSE:
                c.drawRoundRect(new RectF(24 * s, 14 * s, 42 * s, 86 * s), 4 * s, 4 * s, fill);
                c.drawRoundRect(new RectF(58 * s, 14 * s, 76 * s, 86 * s), 4 * s, 4 * s, fill);
                break;
            case PREV:
            case NEXT: { // a bar and a triangle: previous / next channel
                boolean next = type == NEXT;
                c.save();
                if (next) c.scale(-1, 1, 50 * s, 50 * s);
                c.drawRoundRect(new RectF(16 * s, 18 * s, 28 * s, 82 * s), 4 * s, 4 * s, fill);
                path.reset();
                path.moveTo(84 * s, 18 * s);
                path.lineTo(34 * s, 50 * s);
                path.lineTo(84 * s, 82 * s);
                path.close();
                c.drawPath(path, fill);
                c.restore();
                break;
            }
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
