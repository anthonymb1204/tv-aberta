package com.vizzion.tvaberta;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Finds the TVs (and Chromecasts, Google TVs, Nest speakers...) that take Google Cast on the Wi-Fi:
// they announce themselves over mDNS as "_googlecast._tcp". Android's own NsdManager does the
// listening; each one found is resolved (one at a time, as older Androids require) to its address
// and its friendly name ("fn"), e.g. "TV da Sala".
final class CastFinder {
    static final class Tv {
        final String name, host;
        final int port;
        Tv(String name, String host, int port) { this.name = name; this.host = host; this.port = port; }
    }

    interface Listener { void changed(List<Tv> tvs); }

    private final NsdManager nsd;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Tv> tvs = new ArrayList<>();
    private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
    private boolean resolving, running;
    private Listener listener;

    private final NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
        @Override public void onServiceFound(NsdServiceInfo s) { ui.post(() -> { queue.add(s); next(); }); }
        @Override public void onServiceLost(NsdServiceInfo s) {}
        @Override public void onDiscoveryStarted(String t) {}
        @Override public void onDiscoveryStopped(String t) {}
        @Override public void onStartDiscoveryFailed(String t, int e) { running = false; }
        @Override public void onStopDiscoveryFailed(String t, int e) {}
    };

    CastFinder(Context c) {
        nsd = (NsdManager) c.getApplicationContext().getSystemService(Context.NSD_SERVICE);
    }

    void start(Listener l) {
        listener = l;
        if (running || nsd == null) return;
        running = true;
        try { nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, discovery); }
        catch (Exception e) { running = false; }
    }

    void stop() {
        listener = null;
        queue.clear();
        if (!running) return;
        running = false;
        try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) {}
    }

    @SuppressWarnings("deprecation") // resolveService: its replacement needs Android 14
    private void next() {
        if (resolving || queue.isEmpty() || !running) return;
        resolving = true;
        nsd.resolveService(queue.poll(), new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo s, int e) {
                ui.post(() -> { resolving = false; next(); });
            }
            @Override public void onServiceResolved(NsdServiceInfo s) {
                ui.post(() -> {
                    resolving = false;
                    if (s.getHost() != null) add(new Tv(name(s), s.getHost().getHostAddress(), s.getPort()));
                    next();
                });
            }
        });
    }

    private void add(Tv tv) {
        for (Tv t : tvs) if (t.host.equals(tv.host)) return;
        tvs.add(tv);
        if (listener != null) listener.changed(new ArrayList<>(tvs));
    }

    private static String name(NsdServiceInfo s) {
        try {
            Map<String, byte[]> a = s.getAttributes();
            byte[] fn = a == null ? null : a.get("fn");
            if (fn != null && fn.length > 0) return new String(fn, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        return s.getServiceName();
    }
}
