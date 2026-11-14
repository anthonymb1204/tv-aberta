package com.vizzion.tvaberta;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

// Keeps the app up to date without GitHub: asks GitHub for the latest published version, shows a
// short summary of what's new, and installs the new APK with Android's own installer (the person
// only confirms). The summaries live in res/raw/novidades.txt, in the app and in the repository,
// so a new version's summary can be shown before updating.
final class Updater {
    private static final String LATEST = "https://api.github.com/repos/anthonymb1204/tv-aberta/releases/latest";
    private static final String NEWS = "https://raw.githubusercontent.com/anthonymb1204/tv-aberta/main/res/raw/novidades.txt";
    static final String ACTION_STATUS = "com.vizzion.tvaberta.INSTALL_STATUS";
    private static final long CHECK_EVERY = 30 * 60_000L; // automatic checks; the Novidades button always asks now

    static final class Release {
        String version, apk;
        long size;
    }

    private Updater() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("update", 0);
    }

    static String current(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return "0"; }
    }

    // "1.6.10" > "1.6.9": compares number by number.
    static int compare(String a, String b) {
        String[] x = a.replaceAll("[^0-9.]", "").split("\\."), y = b.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    // The newer published version, or null when this one is the latest (or GitHub can't be reached).
    // Asks GitHub at most every 30 minutes unless forced; in between, answers from the last check.
    static Release check(Context c, boolean force) {
        SharedPreferences p = prefs(c);
        if (force || System.currentTimeMillis() - p.getLong("at", 0) > CHECK_EVERY) {
            try {
                JSONObject o = new JSONObject(get(LATEST));
                String v = o.getString("tag_name").replaceFirst("^v", "");
                String apk = null;
                long size = 0;
                JSONArray as = o.optJSONArray("assets");
                if (as != null) for (int i = 0; i < as.length(); i++) {
                    JSONObject a = as.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) { apk = a.getString("browser_download_url"); size = a.optLong("size"); break; }
                }
                p.edit().putLong("at", System.currentTimeMillis()).putString("version", v)
                        .putString("apk", apk).putLong("size", size).apply();
            } catch (Exception ignored) {
                return cached(c);
            }
        }
        return cached(c);
    }

    private static Release cached(Context c) {
        SharedPreferences p = prefs(c);
        String v = p.getString("version", null), apk = p.getString("apk", null);
        if (v == null || apk == null || compare(v, current(c)) <= 0) return null;
        Release r = new Release();
        r.version = v;
        r.apk = apk;
        r.size = p.getLong("size", 0);
        return r;
    }

    // The automatic notice shows once per new version; the Novidades button always shows it.
    static boolean noticeShown(Context c, String version) {
        return version.equals(prefs(c).getString("noticed", null));
    }

    static void markNoticeShown(Context c, String version) {
        prefs(c).edit().putString("noticed", version).apply();
    }

    // ---- What's new: version blocks, newest first ----
    // novidades.txt: a version number on its own line, its short notes under it, a blank line between.

    static final class Notes {
        String version;
        final List<String> lines = new ArrayList<>();
    }

    // The online file (it has the summary of versions newer than this one), else the one in the app.
    static List<Notes> notes(Context c, boolean online) {
        String text = null;
        if (online) try { text = get(NEWS); } catch (Exception ignored) {}
        if (text == null) try (InputStream in = c.getResources().openRawResource(
                c.getResources().getIdentifier("novidades", "raw", c.getPackageName()))) {
            text = read(in);
        } catch (Exception e) { text = ""; }
        List<Notes> out = new ArrayList<>();
        Notes cur = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) { cur = null; continue; }
            if (cur == null) { cur = new Notes(); cur.version = line; out.add(cur); }
            else cur.lines.add(line);
        }
        return out;
    }

    // ---- Installing ----

    interface Progress {
        void at(int percent);          // while downloading
        void failed(String why);       // anything that stopped it
    }

    // Downloads the APK straight into an install session, then hands it to Android's installer, which
    // asks the person to confirm (see MainActivity's receiver for ACTION_STATUS). Off the main thread.
    static void install(Context c, Release r, Progress progress) {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        int id = -1;
        try {
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(c.getPackageName());
            id = installer.createSession(params);
            try (PackageInstaller.Session s = installer.openSession(id)) {
                HttpURLConnection con = Channels.open(r.apk, null);
                try {
                    if (con.getResponseCode() != 200) throw new Exception("HTTP " + con.getResponseCode());
                    long total = con.getContentLengthLong() > 0 ? con.getContentLengthLong() : r.size;
                    try (InputStream in = con.getInputStream(); OutputStream out = s.openWrite("tvaberta.apk", 0, total > 0 ? total : -1)) {
                        byte[] buf = new byte[32768];
                        long done = 0;
                        int last = -1;
                        for (int n; (n = in.read(buf)) > 0; ) {
                            out.write(buf, 0, n);
                            done += n;
                            int pct = total > 0 ? (int) (done * 100 / total) : -1;
                            if (pct != last) { last = pct; progress.at(pct); }
                        }
                        s.fsync(out);
                    }
                } finally {
                    con.disconnect();
                }
                Intent done = new Intent(ACTION_STATUS).setPackage(c.getPackageName());
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                s.commit(PendingIntent.getBroadcast(c, id, done, flags).getIntentSender());
            }
        } catch (Exception e) {
            if (id >= 0) try { installer.abandonSession(id); } catch (Exception ignored) {}
            progress.failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static String get(String url) throws Exception {
        java.util.Map<String, String> h = new java.util.HashMap<>();
        h.put("Accept", "application/vnd.github+json");
        h.put("User-Agent", "TV-Aberta");
        HttpURLConnection con = Channels.open(url, h);
        try {
            if (con.getResponseCode() != 200) throw new Exception("HTTP " + con.getResponseCode());
            try (InputStream in = con.getInputStream()) { return read(in); }
        } finally {
            con.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }
}
