package com.vizzion.tvaberta;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.VideoView;

// Full screen with Android's own player (VideoView): on this TV's chip VLC plays with no sound.
// Up/down (or Channel +/-) change channel; Back goes back to the list.
public class PlayerActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private VideoView video;
    private TextView banner;
    private int index;
    private final Runnable hideBanner = () -> banner.animate().alpha(0f).setDuration(400).start();
    // A stream that never starts (no error, no picture) is called off after this long.
    private final Runnable stalled = () -> fail("não iniciou");

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        float scale = getResources().getDisplayMetrics().widthPixels / 1920f;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        video.setFocusable(false);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        banner = new TextView(this);
        banner.setTextColor(Color.WHITE);
        banner.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.round(34 * scale));
        int p = Math.round(28 * scale);
        banner.setPadding(p, p / 2, p, p / 2);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC000000);
        bg.setCornerRadius(Math.round(18 * scale));
        banner.setBackground(bg);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        blp.leftMargin = blp.topMargin = Math.round(56 * scale);
        root.addView(banner, blp);
        tv = Device.isTv(this);
        if (tv) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        else buildTouch(root);
        setContentView(root);

        video.setOnPreparedListener(mp -> {
            ui.removeCallbacks(stalled);
            mp.setLooping(false);
            show(current().name, 3000);
        });
        video.setOnErrorListener((mp, what, extra) -> {
            fail("erro " + what + "/" + extra);
            return true; // no system "Can't play this video" dialog
        });
        video.setOnCompletionListener(mp -> fail("transmissão encerrada"));

        index = getIntent().getIntExtra("index", 0);
        if (Channels.playing.isEmpty()) { finish(); return; }
        start();
    }

    private Channels.Channel current() {
        return Channels.playing.get(index);
    }

    // ---- Phone: tap for controls, swipe up/down to change channel -------------------------------

    private boolean tv = true;
    private View controls;
    private final Runnable hideControls = () -> {
        if (controls != null) controls.animate().alpha(0f).setDuration(300)
                .withEndAction(() -> controls.setVisibility(View.GONE)).start();
    };

    private void buildTouch(FrameLayout root) {
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        immersive();
        float d = getResources().getDisplayMetrics().density;
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(0x59000000);
        // Back, top left; the channel's name sits beside it.
        TextView back = touchButton("←", d);
        back.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(Math.round(52 * d), Math.round(52 * d), Gravity.TOP | Gravity.START);
        bl.leftMargin = bl.topMargin = Math.round(16 * d);
        layer.addView(back, bl);
        // Previous / next, at the bottom centre.
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        TextView prev = touchButton("‹  Anterior", d);
        prev.setOnClickListener(v -> { zap(-1); keepControls(); });
        TextView next = touchButton("Próximo  ›", d);
        next.setOnClickListener(v -> { zap(1); keepControls(); });
        for (TextView t : new TextView[] {prev, next}) {
            t.setPadding(Math.round(26 * d), 0, Math.round(26 * d), 0);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-2, Math.round(52 * d));
            lp.leftMargin = lp.rightMargin = Math.round(10 * d);
            row.addView(t, lp);
        }
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        rl.bottomMargin = Math.round(24 * d);
        layer.addView(row, rl);
        layer.setVisibility(View.GONE);
        root.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        controls = layer;
        // The channel's name moves right of the back button.
        FrameLayout.LayoutParams blp = (FrameLayout.LayoutParams) banner.getLayoutParams();
        blp.leftMargin = Math.round(80 * d);
        blp.topMargin = Math.round(18 * d);
        banner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);

        final android.view.GestureDetector g = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(android.view.MotionEvent e) { return true; }
                    @Override public boolean onSingleTapUp(android.view.MotionEvent e) {
                        if (controls.getVisibility() == View.VISIBLE) hideControls.run(); else keepControls();
                        return true;
                    }
                    @Override public boolean onFling(android.view.MotionEvent a, android.view.MotionEvent b, float vx, float vy) {
                        if (a == null || Math.abs(vy) < 800 || Math.abs(b.getY() - a.getY()) < Math.abs(b.getX() - a.getX())) return false;
                        zap(vy < 0 ? 1 : -1); // swipe up: next channel
                        return true;
                    }
                });
        root.setOnTouchListener((v, e) -> g.onTouchEvent(e));
    }

    private void keepControls() {
        ui.removeCallbacks(hideControls);
        controls.animate().cancel();
        controls.setAlpha(1f);
        controls.setVisibility(View.VISIBLE);
        show((index + 1) + "/" + Channels.playing.size() + "  " + current().name, 4000);
        ui.postDelayed(hideControls, 4000);
    }

    private TextView touchButton(String label, float d) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99000000);
        bg.setStroke(Math.round(d), 0x40FFFFFF);
        bg.setCornerRadius(26 * d);
        t.setBackground(bg);
        return t;
    }

    // Phone: hide the status and navigation bars; a swipe from the edge brings them back for a moment.
    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has && !tv) immersive();
    }

    private void start() {
        Channels.Channel ch = current();
        Channels.saveLast(this, ch, getIntent().getIntExtra("category", 1));
        show((index + 1) + "/" + Channels.playing.size() + "  " + ch.name + "\nCarregando…", 0);
        ui.removeCallbacks(stalled);
        ui.postDelayed(stalled, 20000);
        video.stopPlayback();
        video.setVideoURI(Uri.parse(ch.url), ch.headers());
        video.start();
    }

    private void fail(String why) {
        ui.removeCallbacks(stalled);
        show(current().name + " indisponível (" + why + ")\n"
                + (tv ? "↑ ↓ para trocar de canal" : "Deslize ou toque para trocar de canal"), 0);
    }

    private void show(String text, int hideAfterMs) {
        ui.removeCallbacks(hideBanner);
        banner.animate().cancel();
        banner.setText(text);
        banner.setAlpha(1f);
        banner.setVisibility(View.VISIBLE);
        if (hideAfterMs > 0) ui.postDelayed(hideBanner, hideAfterMs);
    }

    private void zap(int step) {
        int n = Channels.playing.size();
        index = ((index + step) % n + n) % n;
        start();
    }

    // Typing a channel number: digits gather for a moment, then it jumps there.
    private final StringBuilder digits = new StringBuilder();
    private final Runnable jump = () -> {
        int n = Integer.parseInt(digits.toString());
        digits.setLength(0);
        if (n >= 1 && n <= Channels.playing.size()) {
            index = n - 1;
            start();
        } else {
            show("Canal " + n + " não existe nesta lista", 2500);
        }
    };

    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            if (digits.length() < 5) digits.append((char) ('0' + code - KeyEvent.KEYCODE_0));
            show("Canal " + digits + "…", 0);
            ui.removeCallbacks(jump);
            ui.postDelayed(jump, 1300);
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                zap(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                zap(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_INFO:
                show((index + 1) + "/" + Channels.playing.size() + "  " + current().name, 3000);
                return true;
        }
        return super.onKeyDown(code, ev);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacksAndMessages(null);
        video.stopPlayback();
        finish(); // never keep a stream running in the background on 1 GB of RAM
    }
}
