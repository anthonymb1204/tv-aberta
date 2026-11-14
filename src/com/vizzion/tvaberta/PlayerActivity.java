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

        // Brightness on the left edge, volume on the right: drag up or down on the bar.
        brightness = sideSlider(layer, Icon.SUN, Gravity.START, d, v -> setBrightness(v));
        volume = sideSlider(layer, Icon.SPEAKER, Gravity.END, d, v -> {
            android.media.AudioManager am = audio();
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                    Math.round(v * am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)), 0);
        });

        // The child lock, top right: open padlock; tapping it locks the screen.
        View lock = roundIcon(Icon.LOCK_OPEN, d);
        lock.setOnClickListener(v -> setLocked(true));
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(Math.round(52 * d), Math.round(52 * d), Gravity.TOP | Gravity.END);
        ll.rightMargin = ll.topMargin = Math.round(16 * d);
        layer.addView(lock, ll);

        layer.setVisibility(View.GONE);
        root.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        controls = layer;

        // While locked, a tap only shows the closed padlock; holding it for a second unlocks.
        FrameLayout lockLayer = new FrameLayout(this);
        View closed = roundIcon(Icon.LOCK_CLOSED, d);
        closed.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(1000).start();
                ui.removeCallbacks(hideLockHint);
                ui.postDelayed(unlock, 1000);
            } else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                ui.removeCallbacks(unlock);
                ui.postDelayed(hideLockHint, 2500);
            }
            return true;
        });
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(Math.round(52 * d), Math.round(52 * d), Gravity.TOP | Gravity.END);
        cl.rightMargin = cl.topMargin = Math.round(16 * d);
        lockLayer.addView(closed, cl);
        TextView hint = new TextView(this);
        hint.setText("Segure o cadeado para destravar");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setShadowLayer(6 * d, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        hl.rightMargin = Math.round(80 * d);
        hl.topMargin = Math.round(30 * d);
        lockLayer.addView(hint, hl);
        lockLayer.setVisibility(View.GONE);
        root.addView(lockLayer, new FrameLayout.LayoutParams(-1, -1));
        lockHint = lockLayer;
        // The channel's name moves right of the back button.
        FrameLayout.LayoutParams blp = (FrameLayout.LayoutParams) banner.getLayoutParams();
        blp.leftMargin = Math.round(80 * d);
        blp.topMargin = Math.round(18 * d);
        banner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);

        final android.view.GestureDetector g = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(android.view.MotionEvent e) { return true; }
                    @Override public boolean onSingleTapUp(android.view.MotionEvent e) {
                        if (locked) showLockHint();
                        else if (controls.getVisibility() == View.VISIBLE) hideControls.run();
                        else keepControls();
                        return true;
                    }
                    @Override public boolean onFling(android.view.MotionEvent a, android.view.MotionEvent b, float vx, float vy) {
                        if (locked || a == null || Math.abs(vy) < 800 || Math.abs(b.getY() - a.getY()) < Math.abs(b.getX() - a.getX())) return false;
                        zap(vy < 0 ? 1 : -1); // swipe up: next channel
                        return true;
                    }
                });
        root.setOnTouchListener((v, e) -> g.onTouchEvent(e));
    }

    private void keepControls() {
        if (locked) return;
        boolean wasShown = controls.getVisibility() == View.VISIBLE && controls.getAlpha() == 1f;
        ui.removeCallbacks(hideControls);
        controls.animate().cancel();
        controls.setAlpha(1f);
        controls.setVisibility(View.VISIBLE);
        if (!wasShown) {
            show((index + 1) + "/" + Channels.playing.size() + "  " + current().name, 4000);
            brightness.setValue(currentBrightness());
            android.media.AudioManager am = audio();
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            volume.setValue(max <= 0 ? 0 : am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) / (float) max);
        }
        ui.postDelayed(hideControls, 4000);
    }

    // ---- Brightness, volume and the child lock ----

    private VSlider brightness, volume;
    private View lockHint;
    private boolean locked;
    private final Runnable hideLockHint = () -> {
        if (lockHint != null) lockHint.animate().alpha(0f).setDuration(300)
                .withEndAction(() -> lockHint.setVisibility(View.GONE)).start();
    };
    private final Runnable unlock = () -> setLocked(false);

    private void setLocked(boolean on) {
        locked = on;
        ui.removeCallbacks(hideLockHint);
        if (on) {
            ui.removeCallbacks(hideControls);
            controls.animate().cancel();
            controls.setVisibility(View.GONE);
            showLockHint();
        } else {
            lockHint.animate().cancel();
            lockHint.setVisibility(View.GONE);
            keepControls();
        }
    }

    private void showLockHint() {
        ui.removeCallbacks(hideLockHint);
        lockHint.animate().cancel();
        lockHint.setAlpha(1f);
        lockHint.setVisibility(View.VISIBLE);
        ui.postDelayed(hideLockHint, 2500);
    }

    // A bar on one edge, with its icon above it, both centred vertically.
    private VSlider sideSlider(FrameLayout parent, int icon, int side, float d, VSlider.Listener l) {
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageDrawable(new Icon(icon));
        col.addView(iv, new android.widget.LinearLayout.LayoutParams(Math.round(26 * d), Math.round(26 * d)));
        VSlider s = new VSlider(this);
        s.setListener(l);
        s.onTouched(() -> { ui.removeCallbacks(hideControls); ui.postDelayed(hideControls, 4000); });
        android.widget.LinearLayout.LayoutParams sl = new android.widget.LinearLayout.LayoutParams(Math.round(48 * d), Math.round(160 * d));
        sl.topMargin = Math.round(12 * d);
        col.addView(s, sl);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL | side);
        lp.leftMargin = lp.rightMargin = Math.round(28 * d);
        parent.addView(col, lp);
        return s;
    }

    private View roundIcon(int icon, float d) {
        android.widget.ImageView b = new android.widget.ImageView(this);
        b.setImageDrawable(new Icon(icon));
        int p = Math.round(14 * d);
        b.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x99000000);
        bg.setStroke(Math.round(d), 0x40FFFFFF);
        b.setBackground(bg);
        return b;
    }

    private android.media.AudioManager audio() {
        return (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
    }

    // Only this app's window gets brighter or darker; the phone's own setting is left alone.
    private float currentBrightness() {
        float b = getWindow().getAttributes().screenBrightness;
        if (b >= 0) return b;
        try {
            return android.provider.Settings.System.getInt(getContentResolver(),
                    android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (Exception e) {
            return 0.5f;
        }
    }

    private void setBrightness(float v) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = Math.max(0.02f, v);
        getWindow().setAttributes(lp);
    }

    @Override
    public void onBackPressed() {
        if (locked) { showLockHint(); return; } // the lock also keeps Back from closing the channel
        super.onBackPressed();
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
        if (locked && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE)) {
            showLockHint();
            return true;
        }
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
