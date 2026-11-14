package com.vizzion.tvaberta;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Which channels are off the air. The public lists carry many streams that have stopped working;
// each channel is tried in the background (a few at a time) and the answer is remembered, so the
// list can hide the dead ones. "On the air" is trusted for 6 hours; "off the air" only for 1 hour,
// since streams often come back.
final class Health {
    private static final long OK_FOR = 6 * 3600_000L, DOWN_FOR = 3600_000L;
    private static final ExecutorService pool = Executors.newFixedThreadPool(6);
    private static final Set<String> checking = ConcurrentHashMap.newKeySet();

    interface Listener { void checked(Channels.Channel ch, boolean ok); }

    private Health() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("saude", 0);
    }

    // Channels that aren't streams (CazéTV opens YouTube, Globo opens Globoplay) are never tested.
    static boolean testable(Channels.Channel ch) {
        return !Channels.isCaze(ch) && !Channels.isGloboplay(ch);
    }

    // Known to be off the air, recently enough to still believe it.
    static boolean isDown(Context c, Channels.Channel ch) {
        long t = prefs(c).getLong(ch.url, 0);
        return t < 0 && System.currentTimeMillis() + t < DOWN_FOR;
    }

    private static boolean fresh(Context c, Channels.Channel ch) {
        long t = prefs(c).getLong(ch.url, 0), age = System.currentTimeMillis() - Math.abs(t);
        return t != 0 && age < (t > 0 ? OK_FOR : DOWN_FOR);
    }

    static void mark(Context c, Channels.Channel ch, boolean ok) {
        long now = System.currentTimeMillis();
        prefs(c).edit().putLong(ch.url, ok ? now : -now).apply();
    }

    // Tests the channels whose answer is missing or old; tells the listener (on a background thread)
    // about each one as it's done.
    static void check(final Context c, List<Channels.Channel> list, final Listener l) {
        for (final Channels.Channel ch : list) {
            if (!testable(ch) || fresh(c, ch) || !checking.add(ch.url)) continue;
            pool.execute(() -> {
                boolean ok = Channels.responds(ch);
                // One more try before calling it dead: a slow server or a hiccup on the Wi-Fi.
                if (!ok) ok = Channels.responds(ch);
                mark(c, ch, ok);
                checking.remove(ch.url);
                l.checked(ch, ok);
            });
        }
    }
}
