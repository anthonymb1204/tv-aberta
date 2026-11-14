package com.vizzion.tvaberta;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

// YouTube lives inside TV Aberta, with YouTube's own embedded player (the one websites use, ads and
// all) in a WebView: no library, and within YouTube's rules. Up/down (or Channel +/-) go to the
// previous / next YouTube channel of the list, OK pauses, Back leaves. A channel that doesn't allow
// its videos to be embedded opens in the YouTube app instead.
public class YouTubePlayerActivity extends Activity {

    // The YouTube channels of the list the person was in, and which one is on. Same process: a static.
    static List<Channels.Channel> list = new ArrayList<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView banner;
    private int index;
    private String videoId;
    private boolean ready, tv;
    private int load; // which load the player's callbacks belong to (zapping starts a new one)
    private final Runnable hideBanner = () -> banner.animate().alpha(0f).setDuration(400).start();
    // A player that never gets going (no answer from YouTube) gives way to the YouTube app.
    private final Runnable stalled = () -> { if (!ready) toYouTubeApp("o vídeo não carregou"); };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        tv = Device.isTv(this);
        setRequestedOrientation(tv ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        if (!tv) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(!tv); // on a TV the remote's keys come here, not to YouTube's player page
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // start playing by itself
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "TVA");
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        float d = getResources().getDisplayMetrics().density;
        banner = new TextView(this);
        banner.setTextColor(Color.WHITE);
        banner.setTextSize(TypedValue.COMPLEX_UNIT_SP, tv ? 18 : 15);
        banner.setPadding(Math.round(18 * d), Math.round(10 * d), Math.round(18 * d), Math.round(10 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC000000);
        bg.setCornerRadius(12 * d);
        banner.setBackground(bg);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        blp.leftMargin = blp.topMargin = Math.round(24 * d);
        root.addView(banner, blp);
        setContentView(root);

        index = getIntent().getIntExtra("index", 0);
        videoId = getIntent().getStringExtra("video");
        if (list.isEmpty() || index >= list.size()) { finish(); return; }
        if (videoId != null) play(videoId); else findLive();
    }

    private Channels.Channel current() {
        return list.get(index);
    }

    // The channel's live right now (read from its /live page), then plays it. A channel whose live
    // just ended is skipped, going on in the same direction (once round the list at most).
    private int step = 1, skipped;

    private void findLive() {
        final Channels.Channel ch = current();
        final int mine = ++load;
        show(ch.name + "\nProcurando a transmissão…", 0);
        new Thread(() -> {
            CazeLive cz = CazeLive.fetch(ch.url);
            ui.post(() -> {
                if (mine != load || isFinishing()) return;
                if (cz != null && cz.videoId != null && cz.liveNow) { skipped = 0; play(cz.videoId); return; }
                if (++skipped < list.size()) { index = ((index + step) % list.size() + list.size()) % list.size(); findLive(); return; }
                skipped = 0;
                show("Nenhum desses canais está ao vivo agora", 0);
            });
        }).start();
    }

    private void play(String id) {
        videoId = id;
        ready = false;
        final int mine = ++load;
        show((index + 1) + "/" + list.size() + "  " + current().name + "\nCarregando…", 0);
        ui.removeCallbacks(stalled);
        ui.postDelayed(stalled, 25000);
        String html = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;background:#000;height:100%;overflow:hidden}#p{position:absolute;top:0;left:0;width:100%;height:100%}</style>"
                + "</head><body><div id='p'></div><script src='https://www.youtube.com/iframe_api'></script><script>"
                + "var player;function onYouTubeIframeAPIReady(){player=new YT.Player('p',{videoId:'" + id + "',"
                + "playerVars:{autoplay:1,controls:" + (tv ? 0 : 1) + ",rel:0,playsinline:1,fs:0,origin:'https://tvaberta.app'},"
                + "events:{onReady:function(e){e.target.playVideo();TVA.ready(" + mine + ")},"
                + "onError:function(e){TVA.error(" + mine + ",e.data)},"
                + "onStateChange:function(e){TVA.state(" + mine + ",e.data)}}})}"
                + "function toggle(){if(!player)return;if(player.getPlayerState()==1)player.pauseVideo();else player.playVideo()}"
                + "</script></body></html>";
        // A web address as the page's origin: YouTube's embedded player refuses pages without one.
        web.loadDataWithBaseURL("https://tvaberta.app/", html, "text/html", "UTF-8", null);
    }

    // YouTube's player calls these from its page (on a background thread).
    private final class Bridge {
        @JavascriptInterface public void ready(int from) {
            ui.post(() -> {
                if (from != load) return;
                ready = true;
                ui.removeCallbacks(stalled);
                show(current().name, 3000);
            });
        }

        @JavascriptInterface public void state(int from, int st) {
            if (st == 1) ui.post(() -> { if (from == load) { ready = true; ui.removeCallbacks(stalled); } });
        }

        // 101 and 150: the channel doesn't allow its videos in other apps; 2, 5, 100: bad or gone video.
        @JavascriptInterface public void error(int from, int code) {
            ui.post(() -> {
                if (from != load) return;
                if (code == 101 || code == 150 || code == 152 || code == 153) toYouTubeApp("o canal só permite assistir no YouTube");
                else show(current().name + ": o vídeo não está disponível\n↑ ↓ para trocar de canal", 0);
            });
        }
    }

    private void toYouTubeApp(String why) {
        if (videoId == null || isFinishing()) return;
        android.widget.Toast.makeText(this, current().name + ": " + why + ". Abrindo no YouTube…",
                android.widget.Toast.LENGTH_LONG).show();
        CazeLive.open(this, videoId);
        finish();
    }

    private void show(String text, int hideAfterMs) {
        ui.removeCallbacks(hideBanner);
        banner.animate().cancel();
        banner.setText(text);
        banner.setAlpha(1f);
        if (hideAfterMs > 0) ui.postDelayed(hideBanner, hideAfterMs);
    }

    private void zap(int step) {
        int n = list.size();
        this.step = step;
        skipped = 0;
        index = ((index + step) % n + n) % n;
        videoId = null;
        web.loadUrl("about:blank");
        findLive();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
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
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                web.evaluateJavascript("toggle()", null);
                show(current().name, 2500);
                return true;
        }
        return super.onKeyDown(code, ev);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacksAndMessages(null);
        finish(); // never keep a video running in the background on 1 GB of RAM
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
        }
        super.onDestroy();
    }
}
