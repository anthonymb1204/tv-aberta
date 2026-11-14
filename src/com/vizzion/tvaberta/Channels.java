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

    // Category 0 is the viewer's favourites, kept on the TV; the rest are iptv-org lists.
    static final int FAVORITES = 0;
    static final String[] CATEGORY_NAMES = {"★ Favoritos", "Brasil", "Notícias", "Esportes", "Filmes", "Entretenimento", "Infantil", "Todos"};
    private static final String BASE = "https://iptv-org.github.io/iptv/";
    private static final String[] CATEGORY_PATHS = {null, "countries/br", "categories/news", "categories/sports",
            "categories/movies", "categories/entertainment", "categories/kids", "index"};
    private static final long CACHE_MS = 12L * 60 * 60 * 1000;

    static final class Channel {
        String name, url, group, referrer, userAgent, search, logo, id;
        boolean partTime; // marked [Not 24/7] in the list (the tag itself is taken out of the name)

        // One line per channel for storage: fields split by tabs (tabs and line breaks removed first).
        String encode() {
            StringBuilder b = new StringBuilder();
            for (String s : new String[] {name, url, group, referrer, userAgent, logo}) {
                if (b.length() > 0) b.append('\t');
                b.append(s == null ? "" : s.replace('\t', ' ').replace('\n', ' '));
            }
            return b.toString();
        }

        static Channel decode(String line) {
            String[] f = line.split("\t", -1);
            if (f.length < 2 || f[1].isEmpty()) return null;
            Channel c = new Channel();
            c.name = f[0];
            c.url = f[1];
            c.group = f.length > 2 && !f[2].isEmpty() ? f[2] : null;
            c.referrer = f.length > 3 && !f[3].isEmpty() ? f[3] : null;
            c.userAgent = f.length > 4 && !f[4].isEmpty() ? f[4] : null;
            c.logo = f.length > 5 && !f[5].isEmpty() ? f[5] : null;
            c.search = fold(c.name);
            return c;
        }

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
        if (category == FAVORITES) return favorites(c);
        List<Channel> main = loadList(c, BASE + CATEGORY_PATHS[category] + ".m3u",
                "cat_" + CATEGORY_PATHS[category].replace('/', '_') + ".m3u");
        // Only the famous channels, and never the unofficial pay-TV re-streams.
        for (java.util.Iterator<Channel> it = main.iterator(); it.hasNext(); ) {
            Channel ch = it.next();
            if (unofficial(ch) || !famous(ch)) it.remove();
        }
        if (category != 1) { rank(main); return main; }
        // Brasil also gets the regional and community channels of iptv-com's list, without repeats.
        // Pay-TV brands there come from unofficial re-streams, so they are left out.
        try {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Channel ch : main) { seen.add(ch.url); seen.add(fold(ch.name)); }
            for (Channel ch : loadList(c, EXTRA_BRASIL, "extra_brazil.m3u")) {
                if (unofficial(ch) || !famous(ch) || seen.contains(ch.url) || seen.contains(fold(ch.name))) continue;
                main.add(ch);
            }
        } catch (Exception ignored) {} // the extra list is a bonus: Brasil still works without it
        rank(main);
        return main;
    }

    // Only the big names, most famous first (the lists carry no audience numbers, so this is chosen by
    // hand, at the level of the national networks). Matched on the accent-free, lower-case name.
    // Globo, ESPN, SporTV and other pay channels are not here: these lists only carry them as
    // unofficial re-streams (see PAY_TV); Globo is on the antenna and in Globoplay.
    private static final String[] FAME = {
        // Brazil's national free-to-air networks
        "^sbt nacional", "^record( \\(|$)", "^band( \\(|$)", "^rede ?tv!? ?(sd|mais)", "^tv cultura( \\(|$)",
        "^tv brasil( \\(|$)", "^tv gazeta( \\(|$)",
        // big news channels
        "^cnn brasil", "^jovem pan news", "^bandnews", "^record news", "^sbt news", "^pluto tv record news",
        // sport, free and official
        "^caze", "^fifa\\+( \\(|$| portuguese)", "^red bull tv( \\(|$| br)", "^pluto tv esportes", "^ufc", "^wwe", "^nba( |$)",
        // kids
        "^nickelodeon (classico|teen|toons|\\(brazil)", "^nick jr\\. club.*\\bbr\\b", "^bob esponja", "^turma da monica",
        "^pluto tv kids( \\(|$)", "^pluto tv junior",
        // films and entertainment from Pluto TV Brasil
        "^pluto tv (cine|filmes)", "^mtv pluto tv br",
        // the big international news channels, main feeds in English or Portuguese
        "^bbc news( \\(|$| uk)", "^cnn international( \\(|$)", "^cnn portugal", "^dw english", "^france 24 english( \\(|$)",
        "^al jazeera english( \\(|$)"
    };
    private static final java.util.regex.Pattern[] FAME_P = new java.util.regex.Pattern[FAME.length];
    static {
        for (int i = 0; i < FAME.length; i++) FAME_P[i] = java.util.regex.Pattern.compile(FAME[i]);
    }

    // Feeds of those brands made for other countries (Spanish, German, Danish... versions of Pluto TV
    // and Nickelodeon), which a Brazilian viewer doesn't want.
    private static final java.util.regex.Pattern FOREIGN = java.util.regex.Pattern.compile(
            "\\b(latam|germany|dk|no|italy|spain|mexico|france)\\b|pantalones|juniorklubben|accion|clasico|crimen|de autor|"
            + "estelar|inspirador|romantico|suspenso|italiano|orientale");

    static boolean famous(Channel c) {
        if (fame(c) >= FAME_P.length || FOREIGN.matcher(c.search).find()) return false;
        // "(United States)" feeds only count in their Brazilian version (marked BR).
        return !c.search.contains("united states") || c.search.matches(".*\\bbr\\b.*");
    }

    private static int fame(Channel c) {
        for (int i = 0; i < FAME_P.length; i++) if (FAME_P[i].matcher(c.search).find()) return i;
        return FAME_P.length;
    }

    // Picture height from names like "(1080p)" or "(720i)"; 400 when the list doesn't say.
    private static int quality(Channel c) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((\\d{3,4})[pi]\\)").matcher(c.name);
        return m.find() ? Integer.parseInt(m.group(1)) : 400;
    }

    static void rank(List<Channel> list) {
        final java.util.Map<Channel, int[]> key = new java.util.IdentityHashMap<>();
        for (Channel c : list) key.put(c, new int[] {fame(c), c.name.contains("Not 24/7") || c.partTime ? 1 : 0, -quality(c)});
        java.util.Collections.sort(list, (a, b) -> {
            int[] x = key.get(a), y = key.get(b);
            for (int i = 0; i < 3; i++) if (x[i] != y[i]) return Integer.compare(x[i], y[i]);
            return a.search.compareTo(b.search);
        });
    }

    private static final String EXTRA_BRASIL = "https://raw.githubusercontent.com/iptv-com/iptv/refs/heads/main/lists/brazil.m3u";
    // Brazilian pay-TV channels (and Globo's own feeds), which these lists only carry as unofficial
    // re-streams. Matched at the start of the name, so free channels like "TV Universal" or
    // "Gospel Cartoon" stay.
    private static final java.util.regex.Pattern PAY_TV = java.util.regex.Pattern.compile(
            "(?i)^(espn|sportv|premiere|telecine|hbo|combate|globonews|globoplay novelas|megapix|tnt|warner|band ?sports|"
            + "discovery|cartoon network|studio universal|fox|universal tv|rede globo|tv globo|globo)\\b");

    // True for a Brazilian channel (tvg-id ".br") from that pay-TV list.
    static boolean unofficial(Channel ch) {
        return ch.id != null && ch.id.contains(".br") && PAY_TV.matcher(ch.name).find();
    }

    // One list: fresh cache, else download, else a stale cache. Throws if there is nothing at all.
    private static List<Channel> loadList(Context c, String url, String cacheName) throws Exception {
        File f = new File(c.getFilesDir(), cacheName);
        boolean fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < CACHE_MS;
        if (!fresh) {
            try {
                download(url, f);
            } catch (Exception e) {
                if (!f.exists()) throw e; // offline and never downloaded
            }
        }
        try (InputStream in = new FileInputStream(f)) {
            return parse(in);
        }
    }

    // ---- Favourites and the last channel watched, kept in the app's preferences ----

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("tvaberta", 0);
    }

    static List<Channel> favorites(Context c) {
        List<Channel> out = new ArrayList<>();
        for (String line : prefs(c).getString("favs", "").split("\n")) {
            Channel ch = line.isEmpty() ? null : Channel.decode(line);
            if (ch != null) out.add(ch);
        }
        return out;
    }

    static boolean isFavorite(Context c, String url) {
        for (Channel ch : favorites(c)) if (ch.url.equals(url)) return true;
        return false;
    }

    // Adds or removes a channel; returns true if it is a favourite now.
    static boolean toggleFavorite(Context c, Channel ch) {
        List<Channel> favs = favorites(c);
        boolean removed = false;
        for (java.util.Iterator<Channel> it = favs.iterator(); it.hasNext(); )
            if (it.next().url.equals(ch.url)) { it.remove(); removed = true; }
        if (!removed) favs.add(ch);
        StringBuilder b = new StringBuilder();
        for (Channel f : favs) b.append(f.encode()).append('\n');
        prefs(c).edit().putString("favs", b.toString()).apply();
        return !removed;
    }

    static void saveLast(Context c, Channel ch, int category) {
        prefs(c).edit().putString("last", ch.encode()).putInt("lastCategory", category).apply();
    }

    static Channel last(Context c) {
        String s = prefs(c).getString("last", null);
        return s == null ? null : Channel.decode(s);
    }

    static int lastCategory(Context c) {
        int i = prefs(c).getInt("lastCategory", 1);
        return i >= 0 && i < CATEGORY_NAMES.length ? i : 1;
    }

    // A channel logo, small: from the cache folder, else downloaded once and kept there. Null if none.
    static android.graphics.Bitmap logo(Context c, String url, int maxH) {
        if (url == null || url.isEmpty()) return null;
        File dir = new File(c.getCacheDir(), "logos");
        dir.mkdirs();
        File f = new File(dir, Integer.toHexString(url.hashCode()) + ".img");
        try {
            if (!f.exists()) {
                HttpURLConnection con = open(url, null);
                try {
                    if (con.getResponseCode() != 200) return null;
                    File tmp = new File(f.getPath() + ".tmp");
                    try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[8192];
                        int total = 0;
                        for (int n; (n = in.read(buf)) > 0; ) {
                            out.write(buf, 0, n);
                            if ((total += n) > 2_000_000) return null; // not a logo
                        }
                    }
                    tmp.renameTo(f);
                } finally {
                    con.disconnect();
                }
            }
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outHeight / (sample * 2) >= maxH) sample *= 2;
            o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            return android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Exception e) {
            return null;
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
                cur.logo = attr(line, "tvg-logo");
                cur.id = attr(line, "tvg-id");
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
                    cur.partTime = cur.name.contains("[Not 24/7]");
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
