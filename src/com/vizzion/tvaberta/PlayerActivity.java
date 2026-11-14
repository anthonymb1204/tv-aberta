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
        show(current().name + " indisponível (" + why + ")\n↑ ↓ para trocar de canal", 0);
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
