package com.vizzion.tvaberta;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Downloads the iptv-org lists, keeps them cached for 12 hours, and parses them.
final class Channels {

    static final String[] CATEGORY_NAMES = {"Brasil", "Notícias", "Esportes", "Filmes", "Entretenimento", "Infantil", "Todos"};
    private static final String BASE = "https://iptv-org.github.io/iptv/";
    private static final String[] CATEGORY_PATHS = {"countries/br", "categories/news", "categories/sports",
            "categories/movies", "categories/entertainment", "categories/kids", "index"};
    private static final long CACHE_MS = 12L * 60 * 60 * 1000;

    static final class Channel {
        String name, url, group, referrer, userAgent, search;

        Map<String, String> headers() {
            Map<String, String> h = new HashMap<>();
            if (referrer != null && !referrer.isEmpty()) h.put("Referer", referrer);
            if (userAgent != null && !userAgent.isEmpty()) h.put("User-Agent", userAgent);
            return h;
        }
    }

    // The channel list the player walks through with up/down. Same process, so a static is enough.
    static List<Channel> playing = new ArrayList<>();

    // Loads a category: fresh cache, else download, else a stale cache. Throws if there is nothing at all.
    static List<Channel> load(Context c, int category) throws Exception {
        File f = new File(c.getFilesDir(), "cat_" + category + ".m3u");
        boolean fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < CACHE_MS;
        if (!fresh) {
            try {
                download(BASE + CATEGORY_PATHS[category] + ".m3u", f);
            } catch (Exception e) {
                if (!f.exists()) throw e; // offline and never downloaded
            }
        }
        try (InputStream in = new FileInputStream(f)) {
            return parse(in);
        }
    }

    private static void download(String url, File to) throws Exception {
        HttpURLConnection con = open(url, null);
        try {
            if (con.getResponseCode() != 200) throw new Exception("HTTP " + con.getResponseCode());
            File tmp = new File(to.getPath() + ".tmp");
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            if (!tmp.renameTo(to)) { to.delete(); tmp.renameTo(to); }
        } finally {
            con.disconnect();
        }
    }

    // Follows redirects by hand (HttpURLConnection won't go from http to https on its own).
    static HttpURLConnection open(String url, Map<String, String> headers) throws Exception {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(10000);
            con.setInstanceFollowRedirects(false);
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet())
                con.setRequestProperty(e.getKey(), e.getValue());
            int code = con.getResponseCode();
            if (code >= 300 && code < 400 && con.getHeaderField("Location") != null) {
                url = new URL(new URL(url), con.getHeaderField("Location")).toString();
                con.disconnect();
                continue;
            }
            return con;
        }
        throw new Exception("too many redirects");
    }

    // True if the stream answers with something that isn't an error, within a few seconds.
    static boolean responds(Channel ch) {
        HttpURLConnection con = null;
        try {
            Map<String, String> h = ch.headers();
            h.put("Range", "bytes=0-2047");
            con = open(ch.url, h);
            con.setReadTimeout(6000);
            int code = con.getResponseCode();
            if (code >= 400) return false;
            try (InputStream in = con.getInputStream()) { return in.read(new byte[512]) > 0; }
        } catch (Exception e) {
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    // M3U: #EXTINF:-1 attrs,Name  then optional #EXTVLCOPT / #KODIPROP lines, then the URL.
    // Channels marked [Geo-blocked] or that need #KODIPROP (DRM, Kodi only) are left out.
    static List<Channel> parse(InputStream in) throws Exception {
        List<Channel> out = new ArrayList<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"), 16384);
        Channel cur = null;
        boolean skip = false;
        for (String line; (line = r.readLine()) != null; ) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                cur = new Channel();
                skip = false;
                cur.name = nameOf(line);
                cur.group = attr(line, "group-title");
                cur.referrer = attr(line, "http-referrer");
                cur.userAgent = attr(line, "http-user-agent");
                if (cur.name.contains("[Geo-blocked]")) skip = true;
            } else if (line.startsWith("#EXTVLCOPT:") && cur != null) {
                String opt = line.substring(11);
                if (opt.startsWith("http-referrer=")) cur.referrer = opt.substring(14);
                else if (opt.startsWith("http-user-agent=")) cur.userAgent = opt.substring(16);
            } else if (line.startsWith("#KODIPROP")) {
                skip = true;
            } else if (!line.startsWith("#") && cur != null) {
                if (!skip) {
                    cur.url = line;
                    cur.name = cur.name.replace("[Not 24/7]", "").trim();
                    cur.search = fold(cur.name);
                    out.add(cur);
                }
                cur = null;
            }
        }
        return out;
    }

    // The name is after the first comma that isn't inside quotes.
    private static String nameOf(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') quoted = !quoted;
            else if (ch == ',' && !quoted) return line.substring(i + 1).trim();
        }
        return line;
    }

    private static String attr(String line, String key) {
        int i = line.indexOf(key + "=\"");
        if (i < 0) return null;
        int start = i + key.length() + 2, end = line.indexOf('"', start);
        return end < 0 ? null : line.substring(start, end);
    }

    // Lower case without accents, so "noticias" finds "Notícias".
    static String fold(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }
}
