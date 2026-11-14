package com.vizzion.tvaberta;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Full screen with Android's own player (VideoView): on this TV's chip VLC plays with no sound.
// TV: up/down (or Channel +/-) change channel; Back goes back to the list.
// Phone: a tap shows the controls (back, channel name, cast; brightness left, volume right;
// previous / pause / next; lock), a swipe up or down changes channel.
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

    // ---- Phone controls ------------------------------------------------------------------------

    private boolean tv = true;
    private float d = 1f; // density
    private View controls, lockHint, castCard;
    private TextView title, castText;
    private ImageView playIcon, castIcon;
    private VSlider brightness, volume;
    private boolean locked, paused;
    private long pausedAt;
    private final Runnable hideControls = () -> {
        if (controls != null) controls.animate().alpha(0f).setDuration(300)
                .withEndAction(() -> controls.setVisibility(View.GONE)).start();
    };
    private final Runnable hideLockHint = () -> {
        if (lockHint != null) lockHint.animate().alpha(0f).setDuration(300)
                .withEndAction(() -> lockHint.setVisibility(View.GONE)).start();
    };
    private final Runnable unlock = () -> setLocked(false);

    private int dp(float v) { return Math.round(v * d); }

    private void buildTouch(FrameLayout root) {
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        immersive();
        d = getResources().getDisplayMetrics().density;

        // While casting, the phone shows where the channel is playing instead of the video.
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        ImageView ci = new ImageView(this);
        ci.setImageDrawable(new Icon(Icon.CAST_ON));
        ci.setAlpha(0.85f);
        card.addView(ci, new LinearLayout.LayoutParams(dp(64), dp(64)));
        castText = new TextView(this);
        castText.setTextColor(0xCCFFFFFF);
        castText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        castText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ctl = new LinearLayout.LayoutParams(-2, -2);
        ctl.topMargin = dp(14);
        card.addView(castText, ctl);
        card.setVisibility(View.GONE);
        root.addView(card, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        castCard = card;

        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(0x33000000);
        // Soft shades behind the top and bottom bars.
        View top = new View(this);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xB3000000, 0x00000000}));
        layer.addView(top, new FrameLayout.LayoutParams(-1, dp(110), Gravity.TOP));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xB3000000, 0x00000000}));
        layer.addView(bottom, new FrameLayout.LayoutParams(-1, dp(110), Gravity.BOTTOM));

        // Top: back, the channel's name with "AO VIVO", and cast on the right.
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(12), 0);
        ImageView back = iconButton(Icon.BACK, 13);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = dp(12);
        bar.addView(title, tl);
        TextView live = new TextView(this);
        live.setText("●  AO VIVO");
        live.setTextColor(Color.WHITE);
        live.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        live.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        live.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable lb = new GradientDrawable();
        lb.setColor(0xFFE53935);
        lb.setCornerRadius(dp(4));
        live.setBackground(lb);
        LinearLayout.LayoutParams lvl = new LinearLayout.LayoutParams(-2, -2);
        lvl.leftMargin = dp(14);
        bar.addView(live, lvl);
        View gap = new View(this);
        bar.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
        castIcon = iconButton(Icon.CAST, 11);
        castIcon.setOnClickListener(v -> castPressed());
        bar.addView(castIcon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        layer.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Centre: previous channel, pause / play, next channel.
        LinearLayout mid = new LinearLayout(this);
        mid.setGravity(Gravity.CENTER);
        ImageView prev = iconButton(Icon.PREV, 13);
        prev.setOnClickListener(v -> { zap(-1); keepControls(); });
        ImageView play = iconButton(Icon.PAUSE, 22);
        GradientDrawable pb = new GradientDrawable();
        pb.setShape(GradientDrawable.OVAL);
        pb.setColor(0x66000000);
        play.setBackground(pb);
        play.setOnClickListener(v -> { togglePause(); keepControls(); });
        playIcon = play;
        ImageView next = iconButton(Icon.NEXT, 13);
        next.setOnClickListener(v -> { zap(1); keepControls(); });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(52), dp(52));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(76), dp(76));
        pp.leftMargin = pp.rightMargin = dp(48);
        mid.addView(prev, sp);
        mid.addView(play, pp);
        mid.addView(next, new LinearLayout.LayoutParams(dp(52), dp(52)));
        layer.addView(mid, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // Brightness on the left edge, volume on the right: drag up or down on the bar.
        brightness = sideSlider(layer, Icon.SUN, Gravity.START, this::setBrightness);
        volume = sideSlider(layer, Icon.SPEAKER, Gravity.END, v -> {
            android.media.AudioManager am = audio();
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                    Math.round(v * am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)), 0);
        });

        // Bottom: the child lock.
        View lock = labelButton(Icon.LOCK_OPEN, "Bloquear");
        lock.setOnClickListener(v -> setLocked(true));
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ll.bottomMargin = dp(10);
        layer.addView(lock, ll);

        layer.setVisibility(View.GONE);
        root.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        controls = layer;

        // While locked, a tap only shows the closed padlock; holding it for a second unlocks.
        FrameLayout lockLayer = new FrameLayout(this);
        View closed = labelButton(Icon.LOCK_CLOSED, "Segure para desbloquear");
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(0x99000000);
        cb.setCornerRadius(dp(24));
        closed.setBackground(cb);
        closed.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(1000).start();
                ui.removeCallbacks(hideLockHint);
                ui.postDelayed(unlock, 1000);
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                ui.removeCallbacks(unlock);
                ui.postDelayed(hideLockHint, 2500);
            }
            return true;
        });
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cl.bottomMargin = dp(10);
        lockLayer.addView(closed, cl);
        lockLayer.setVisibility(View.GONE);
        root.addView(lockLayer, new FrameLayout.LayoutParams(-1, -1));
        lockHint = lockLayer;

        // Messages ("Carregando…", errors) sit centred under the top bar.
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        blp.topMargin = dp(72);
        banner.setLayoutParams(blp);
        banner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(dp(16), dp(8), dp(16), dp(8));
        banner.bringToFront();

        final android.view.GestureDetector g = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) { return true; }
                    @Override public boolean onSingleTapUp(MotionEvent e) {
                        if (locked) showLockHint();
                        else if (controls.getVisibility() == View.VISIBLE) hideControls.run();
                        else keepControls();
                        return true;
                    }
                    @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                        if (locked || a == null || Math.abs(vy) < 800 || Math.abs(b.getY() - a.getY()) < Math.abs(b.getX() - a.getX())) return false;
                        zap(vy < 0 ? 1 : -1); // swipe up: next channel
                        return true;
                    }
                });
        root.setOnTouchListener((v, e) -> g.onTouchEvent(e));
    }

    private void keepControls() {
        if (locked || controls == null) return;
        boolean wasShown = controls.getVisibility() == View.VISIBLE && controls.getAlpha() == 1f;
        ui.removeCallbacks(hideControls);
        controls.animate().cancel();
        controls.setAlpha(1f);
        controls.setVisibility(View.VISIBLE);
        title.setText(current().name);
        if (!wasShown) {
            brightness.setValue(currentBrightness());
            android.media.AudioManager am = audio();
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            volume.setValue(max <= 0 ? 0 : am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) / (float) max);
        }
        ui.postDelayed(hideControls, 4000);
    }

    private ImageView iconButton(int icon, int padDp) {
        ImageView b = new ImageView(this);
        b.setImageDrawable(new Icon(icon));
        b.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp));
        b.setBackground(ripple());
        return b;
    }

    // An icon with a word beside it, like "🔓 Bloquear".
    private View labelButton(int icon, String label) {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(16), dp(10), dp(18), dp(10));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(new Icon(icon));
        b.addView(iv, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = dp(10);
        b.addView(t, tl);
        b.setBackground(ripple());
        return b;
    }

    private android.graphics.drawable.Drawable ripple() {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(28));
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x40FFFFFF), null, mask);
    }

    // ---- Pause ---------------------------------------------------------------------------------

    private void togglePause() {
        paused = !paused;
        playIcon.setImageDrawable(new Icon(paused ? Icon.PLAY : Icon.PAUSE));
        if (cast != null) {
            final CastClient c = cast;
            castWork.execute(() -> c.pause(paused));
            return;
        }
        if (paused) {
            pausedAt = System.currentTimeMillis();
            video.pause();
        } else if (System.currentTimeMillis() - pausedAt > 60000) {
            start(); // live TV: after a long pause, pick it up live again instead of an old buffer
        } else {
            video.start();
        }
    }

    // ---- Brightness, volume and the child lock -------------------------------------------------

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

    // A thin bar on one edge, with its icon above it, both centred vertically.
    private VSlider sideSlider(FrameLayout parent, int icon, int side, VSlider.Listener l) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(new Icon(icon));
        col.addView(iv, new LinearLayout.LayoutParams(dp(26), dp(26)));
        VSlider s = new VSlider(this);
        s.setListener(l);
        s.onTouched(() -> { ui.removeCallbacks(hideControls); ui.postDelayed(hideControls, 4000); });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(44), dp(150));
        sl.topMargin = dp(14);
        col.addView(s, sl);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL | side);
        lp.leftMargin = lp.rightMargin = dp(24);
        parent.addView(col, lp);
        return s;
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

    // ---- Cast to a TV (Google Cast) ------------------------------------------------------------

    private CastClient cast;
    private String castName;
    private final ExecutorService castWork = Executors.newSingleThreadExecutor();

    private void castPressed() {
        ui.removeCallbacks(hideControls);
        if (cast != null) {
            dialog().setTitle("Transmitindo para " + castName)
                    .setPositiveButton("Parar transmissão", (x, w) -> stopCast())
                    .setNegativeButton("Continuar", null)
                    .setOnDismissListener(x -> keepControls())
                    .show();
            return;
        }
        final List<CastFinder.Tv> found = new ArrayList<>();
        final ArrayAdapter<String> rows = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        rows.add("Procurando TVs na rede…");
        final CastFinder finder = new CastFinder(this);
        final AlertDialog dlg = dialog().setTitle("Transmitir para uma TV")
                .setAdapter(rows, null)
                .setNeutralButton("Espelhar tela", (x, w) -> mirror())
                .setNegativeButton("Cancelar", null)
                .create();
        dlg.getListView().setOnItemClickListener((parent, view, pos, id) -> {
            if (pos >= found.size()) return;
            dlg.dismiss();
            startCast(found.get(pos));
        });
        final Runnable none = () -> {
            if (found.isEmpty() && dlg.isShowing()) {
                rows.clear();
                rows.add("Nenhuma TV com Google Cast encontrada.\nTente \"Espelhar tela\".");
            }
        };
        finder.start(tvs -> {
            found.clear();
            found.addAll(tvs);
            rows.clear();
            for (CastFinder.Tv t : tvs) rows.add(t.name);
        });
        dlg.setOnDismissListener(x -> {
            finder.stop();
            ui.removeCallbacks(none);
            keepControls();
        });
        dlg.show();
        ui.postDelayed(none, 8000);
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    // Phones' own screen mirroring (Smart View, Screen Cast...), for TVs without Google Cast.
    private void mirror() {
        for (String a : new String[] {"android.settings.CAST_SETTINGS", android.provider.Settings.ACTION_WIRELESS_SETTINGS}) {
            try { startActivity(new Intent(a)); return; } catch (Exception ignored) {}
        }
    }

    private void startCast(final CastFinder.Tv target) {
        show("Conectando à " + target.name + "…", 0);
        castWork.execute(() -> {
            CastClient c = new CastClient();
            try {
                c.connect(target.host, target.port);
            } catch (Exception e) {
                c.close();
                ui.post(() -> show("Não deu para conectar à " + target.name, 4000));
                return;
            }
            ui.post(() -> {
                if (isFinishing()) { c.close(); return; }
                cast = c;
                castName = target.name;
                ui.removeCallbacks(stalled);
                video.stopPlayback();
                castIcon.setImageDrawable(new Icon(Icon.CAST_ON));
                castCard.setVisibility(View.VISIBLE);
                start();
            });
        });
    }

    private void castLoad(final Channels.Channel ch) {
        final CastClient c = cast;
        final int at = index;
        castText.setText("Transmitindo para " + castName);
        show("Abrindo " + ch.name + " na " + castName + "…", 0);
        castWork.execute(() -> {
            String r;
            try { r = c.play(ch.url, ch.name); }
            catch (Exception e) { r = "a conexão com a TV caiu"; }
            final String why = r;
            ui.post(() -> {
                if (cast != c || at != index) return; // the channel was changed meanwhile
                if (why == null) {
                    show("▶  " + ch.name + " na " + castName, 3000);
                } else if (!c.isOpen()) {
                    show("A transmissão para " + castName + " terminou", 4000);
                    endCast();
                } else {
                    show(ch.name + ": " + why + "\nTroque de canal ou pare a transmissão", 0);
                }
            });
        });
    }

    private void stopCast() {
        final CastClient c = cast;
        if (c == null) return;
        castWork.execute(() -> { c.stop(); c.close(); });
        endCast();
    }

    private void endCast() {
        if (cast != null) {
            final CastClient c = cast;
            castWork.execute(c::close);
        }
        cast = null;
        castIcon.setImageDrawable(new Icon(Icon.CAST));
        castCard.setVisibility(View.GONE);
        paused = false;
        playIcon.setImageDrawable(new Icon(Icon.PAUSE));
        start(); // back to playing on the phone
    }

    // ---- System bars ---------------------------------------------------------------------------

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

    // ---- Playing -------------------------------------------------------------------------------

    private void start() {
        Channels.Channel ch = current();
        Channels.saveLast(this, ch, getIntent().getIntExtra("category", 1));
        if (title != null) title.setText(ch.name);
        if (cast != null) { castLoad(ch); return; }
        if (playIcon != null && paused) { paused = false; playIcon.setImageDrawable(new Icon(Icon.PAUSE)); }
        show((index + 1) + "/" + Channels.playing.size() + "  " + ch.name + "\nCarregando…", 0);
        ui.removeCallbacks(stalled);
        ui.postDelayed(stalled, 20000);
        video.stopPlayback();
        video.setVideoURI(Uri.parse(ch.url), ch.headers());
        video.start();
    }

    private void fail(String why) {
        ui.removeCallbacks(stalled);
        if (cast != null) return; // the phone isn't playing it: the TV is
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
        // Leaving while casting: the TV keeps playing the channel; only the connection is closed.
        final CastClient c = cast;
        if (c != null) castWork.execute(c::close);
        castWork.shutdown();
        ui.removeCallbacksAndMessages(null);
        video.stopPlayback();
        finish(); // never keep a stream running in the background on 1 GB of RAM
    }
}
