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

// Gathers the free channels from several public lists, keeps each list cached for 12 hours, and
// sorts them into categories:
//  - iptv-org: every Brazilian channel and every channel in Portuguese (Brazil, Portugal, Angola...);
//  - iptv-com and Free-TV: more Brazilian (and Portuguese) free-to-air channels;
//  - Pluto TV Brasil: its whole line-up (films, series, novelas, anime, kids...), straight from Pluto
//    with this device's own session (Pluto.java);
//  - M3UPT: Portugal's TV channels;
//  - and, as before, the big international names from iptv-org's category lists.
// Unofficial re-streams of pay TV are always left out.
final class Channels {

    // Category 0 is the viewer's favourites, kept on the device.
    static final int FAVORITES = 0, BRASIL = 1, NEWS = 2, SPORTS = 3, MOVIES = 4, SERIES = 5, NOVELAS = 6, KIDS = 7,
            ANIME = 8, DOCS = 9, ENTERTAINMENT = 10, MUSIC = 11, PORTUGAL = 12, WORLD = 13, ALL = 14;
    static final String[] CATEGORY_NAMES = {"★ Favoritos", "Brasil", "Notícias", "Esportes", "Filmes", "Séries", "Novelas",
            "Infantil", "Anime", "Documentários", "Entretenimento", "Música", "Portugal", "Mundo", "Todos"};
    private static final String BASE = "https://iptv-org.github.io/iptv/";
    // iptv-org's own category list for a category, for the famous international channels in it.
    private static final String[] CATEGORY_PATHS = {null, null, "categories/news", "categories/sports",
            "categories/movies", "categories/series", null, "categories/kids", null, "categories/documentary",
            "categories/entertainment", "categories/music", null, null, null};
    private static final long CACHE_MS = 12L * 60 * 60 * 1000;

    static final class Channel {
        String name, url, group, referrer, userAgent, search, logo, id;
        boolean partTime; // marked [Not 24/7] in the list (the tag itself is taken out of the name)
        int genre;        // one of the categories above (0 when it has none), set by pool()
        String country;   // "br", "pt", ... from the list's channel id; "pluto" for Pluto TV

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

    // Loads a category. Throws only if no list at all could be had (offline and nothing cached).
    static List<Channel> load(Context c, int category) throws Exception {
        if (category == FAVORITES) return refreshed(c, favorites(c));
        List<Channel> pool = pool(c);
        List<Channel> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Channel ch : pool) {
            boolean in = category == ALL
                    || (category == BRASIL && "br".equals(ch.country))
                    || (category == PORTUGAL && "pt".equals(ch.country))
                    || (category == WORLD && "world".equals(ch.country))
                    || ch.genre == category;
            if (in && seen.add(key(ch))) out.add(ch);
        }
        // The big international names from iptv-org's own category list (BBC, CNN International,
        // FIFA+, Red Bull TV...), only the famous ones: these lists are mostly foreign channels.
        String path = CATEGORY_PATHS[category];
        if (path != null) {
            try {
                for (Channel ch : loadList(c, BASE + path + ".m3u", "cat_" + path.replace('/', '_') + ".m3u")) {
                    if (unofficial(ch) || !famous(ch) || isStalePluto(ch)) continue;
                    // Pluto channels come from Pluto's own line-up (in the pool), with working links.
                    if (ch.search.startsWith("pluto tv") || (ch.url != null && ch.url.contains("pluto.tv"))) continue;
                    ch.group = groupPt(ch.group);
                    officialCaze(ch);
                    if (seen.add(key(ch)) && seen.add(fold(ch.name))) out.add(ch);
                }
            } catch (Exception e) {
                if (out.isEmpty()) throw e;
            }
        }
        // CazéTV, which the lists only carry as a re-stream, becomes one entry: its official live.
        boolean hasCaze = false;
        for (java.util.Iterator<Channel> it = out.iterator(); it.hasNext(); ) {
            Channel ch = it.next();
            if (isCaze(ch)) { if (hasCaze) it.remove(); hasCaze = true; }
        }
        rank(out);
        addGlobo(category, out);
        return out;
    }

    // ---- The pool: every free channel in Portuguese, from all the lists ----

    private static final String IPTVORG_BR = BASE + "countries/br.m3u", IPTVORG_POR = BASE + "languages/por.m3u";
    private static final String FREE_TV = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8";
    private static final String M3UPT = "https://raw.githubusercontent.com/LITUATUI/M3UPT/main/M3U/M3UPT.m3u";

    private static volatile List<Channel> pool;
    private static volatile long poolAt;

    // All the channels, built from the cached lists (each list is downloaded again after 12 hours).
    // Kept in memory for 10 minutes, so moving between categories is instant.
    static synchronized List<Channel> pool(Context c) throws Exception {
        if (pool != null && System.currentTimeMillis() - poolAt < 10 * 60 * 1000L) return pool;
        List<Channel> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        Exception last = null;
        int lists = 0;
        // Pluto first, straight from Pluto with this device's own session (see Pluto.java). The other
        // lists' copies of Pluto channels are left out when this works: their links don't play.
        List<Channel> pluto = null;
        try { pluto = Pluto.channels(c); } catch (Exception e) { last = e; }
        if (pluto != null) {
            lists++;
            for (Channel ch : pluto) {
                ch.country = "pluto";
                ch.genre = plutoGenre(ch);
                add(out, seen, ch);
            }
        }
        String[][] lists2 = {{IPTVORG_POR, "por.m3u"}, {IPTVORG_BR, "cat_countries_br.m3u"}, {EXTRA_BRASIL, "extra_brazil.m3u"},
                {FREE_TV, "free_tv.m3u"}, {M3UPT, "m3upt.m3u"}};
        for (String[] l : lists2) {
            List<Channel> list;
            try { list = loadList(c, l[0], l[1]); lists++; }
            catch (Exception e) { last = e; continue; }
            boolean freeTv = l[0].equals(FREE_TV), m3upt = l[0].equals(M3UPT), iptvCom = l[0].equals(EXTRA_BRASIL);
            for (Channel ch : list) {
                String g = ch.group == null ? "" : ch.group;
                if (freeTv) {
                    if (g.startsWith("Brazil")) ch.country = "br";
                    else if (g.startsWith("Portugal")) ch.country = "pt";
                    else continue; // the rest of the world: not in Portuguese
                } else if (m3upt) {
                    if (!g.equals("TV")) continue; // beach cams, radios, on-demand videos
                    // Its "TV" group also has channels from France, Spain, the US, Korea...: those go to Mundo
                    // (BBC, France 24... also to Notícias), Brazil's to Brasil; no code at all means Portugal.
                    String cc = countryOf(ch);
                    ch.country = cc == null || cc.equals("pt") ? "pt" : cc.equals("br") ? "br" : "world";
                } else {
                    ch.country = iptvCom ? "br" : countryOf(ch);
                }
                if (unofficial(ch) || isStalePluto(ch)) continue;
                // With Pluto's own line-up loaded, the other lists' copies of Pluto channels are only repeats.
                if (pluto != null && (ch.search.startsWith("pluto tv") || (ch.url != null && ch.url.contains("pluto.tv")))) continue;
                ch.genre = genreOf(ch);
                ch.group = groupPt(ch.group);
                officialCaze(ch);
                add(out, seen, ch);
            }
        }
        if (lists == 0) throw last != null ? last : new Exception("sem listas");
        pool = out;
        poolAt = System.currentTimeMillis();
        return out;
    }

    private static void add(List<Channel> out, java.util.Set<String> seen, Channel ch) {
        // The same stream, or the same channel name (with its quality), only once.
        if (!seen.add(key(ch)) || !seen.add(fold(ch.name))) return;
        out.add(ch);
    }

    // "SBTNacional.br@SD" -> "br".
    private static String countryOf(Channel ch) {
        if (ch.id == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\.([a-z]{2})(@|$)").matcher(ch.id);
        return m.find() ? m.group(1) : null;
    }

    // iptv-org's genres ("News;Sports", "Animation;Kids"...) to ours; 0 for General, Religious, etc.
    private static int genreOf(Channel ch) {
        if (ch.search.contains("novela")) return NOVELAS;
        if (ch.search.contains("anime")) return ANIME;
        String g = ch.group == null ? "" : ch.group.toLowerCase(Locale.ROOT);
        for (String part : g.split(";")) {
            switch (part.trim()) {
                case "news": case "business": return NEWS;
                case "sports": return SPORTS;
                case "movies": return MOVIES;
                case "series": return SERIES;
                case "kids": case "animation": return KIDS;
                case "documentary": case "science": case "travel": case "outdoor": return DOCS;
                case "music": return MUSIC;
                case "entertainment": case "comedy": case "lifestyle": case "family": case "culture":
                case "classic": case "cooking": case "auto": return ENTERTAINMENT;
                default: break;
            }
        }
        return 0;
    }

    // The lists' genre labels, shown beside each channel, in Portuguese ("News;Sports" -> "Notícias · Esportes").
    private static final Map<String, String> GROUP_PT = new HashMap<>();
    static {
        String[] pairs = {"news", "Notícias", "sports", "Esportes", "movies", "Filmes", "series", "Séries", "kids", "Infantil",
                "animation", "Animação", "documentary", "Documentários", "music", "Música", "entertainment", "Entretenimento",
                "general", "Geral", "religious", "Religioso", "legislative", "Legislativo", "education", "Educação",
                "lifestyle", "Estilo de vida", "comedy", "Comédia", "culture", "Cultura", "business", "Negócios",
                "family", "Família", "cooking", "Culinária", "travel", "Viagem", "science", "Ciência", "outdoor", "Natureza",
                "classic", "Clássicos", "weather", "Tempo", "shop", "Compras", "auto", "Carros", "relax", "Relaxar"};
        for (int i = 0; i < pairs.length; i += 2) GROUP_PT.put(pairs[i], pairs[i + 1]);
    }

    private static String groupPt(String group) {
        if (group == null) return null;
        StringBuilder b = new StringBuilder();
        for (String part : group.split(";")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (p.isEmpty() || p.equals("undefined") || p.equals("tv") || p.equals("brazil") || p.equals("portugal")) continue;
            String pt = GROUP_PT.get(p);
            if (b.length() > 0) b.append(';');
            b.append(pt != null ? pt : part.trim());
        }
        return b.length() == 0 ? null : b.toString();
    }

    // A channel's name without its quality or notes, to spot the same channel twice:
    // "Jovem Pan News (720p)" and "Jovem Pan NEWS" are both "jovem pan news".
    static String baseName(Channel ch) {
        return ch.search.replaceAll("\\(.*?\\)|\\[.*?\\]", " ").replaceAll("\\b(hd|sd|fhd|uhd|4k)\\b", " ")
                .replaceAll("\\s+", " ").trim();
    }

    // Pluto TV Brasil's own groups to our categories.
    private static int plutoGenre(Channel ch) {
        String g = ch.group == null ? "" : fold(ch.group);
        if (ch.search.contains("novela") || g.contains("novela")) return NOVELAS;
        if (g.contains("filme")) return MOVIES;
        if (g.contains("anime")) return ANIME;
        if (g.contains("infantil") || g.contains("nickelodeon") || g.contains("kids")) return KIDS;
        if (g.contains("esporte")) return SPORTS;
        if (g.contains("noticia")) return NEWS;
        if (g.contains("serie") || g.contains("jornada") || g.contains("south park")) return SERIES;
        if (g.contains("curiosidade") || g.contains("natureza") || g.contains("investiga") || g.contains("misterio")
                || g.contains("document")) return DOCS;
        if (g.contains("music") || g.contains("musica")) return MUSIC;
        return ENTERTAINMENT; // MTV, reality, comedy, retro, lifestyle...
    }

    // Pluto links other lists carry (directly, or through the jmp2.uk/plu-... redirect): they send
    // sound and picture apart, which Android's player shows as a black screen.
    private static boolean isStalePluto(Channel ch) {
        return ch.url != null && (ch.url.contains("pluto.tv") || ch.url.contains("jmp2.uk/plu-"));
    }

    // What identifies a channel over time: its link, except for Pluto TV, whose links change every day
    // (new token) while the channel's own id in them stays.
    private static final java.util.regex.Pattern PLUTO_ID = java.util.regex.Pattern.compile("/channel/([0-9a-f]{24})/");
    static String key(Channel ch) {
        if (ch.url != null && ch.url.contains("pluto.tv")) {
            java.util.regex.Matcher m = PLUTO_ID.matcher(ch.url);
            if (m.find()) return "pluto:" + m.group(1);
        }
        return ch.url;
    }

    // Favourites (and the last channel) saved with a Pluto link get today's link instead.
    static List<Channel> refreshed(Context c, List<Channel> list) {
        boolean any = false;
        for (Channel ch : list) if (key(ch).startsWith("pluto:")) any = true;
        if (!any) return list;
        java.util.Map<String, Channel> now = new java.util.HashMap<>();
        try { for (Channel ch : pool(c)) now.put(key(ch), ch); } catch (Exception e) { return list; }
        for (Channel ch : list) {
            Channel fresh = now.get(key(ch));
            if (fresh != null) { ch.url = fresh.url; ch.referrer = fresh.referrer; ch.userAgent = fresh.userAgent; }
        }
        return list;
    }

    // Globo, the most-watched channel, at the top of Brasil and Todos: it opens "Agora na TV" in the
    // Globoplay app (free with a Globo account), the official way to watch it live.
    static final String GLOBOPLAY = "https://globoplay.globo.com/agora-na-tv/";
    private static final String GLOBO_LOGO =
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c9/TV_Globo_2025.svg/330px-TV_Globo_2025.svg.png";

    private static void addGlobo(int category, List<Channel> list) {
        if (category != 1 && category != CATEGORY_NAMES.length - 1) return; // Brasil and Todos
        Channel g = new Channel();
        g.name = "Globo";
        g.search = fold(g.name);
        g.url = GLOBOPLAY;
        g.group = "Ao vivo no Globoplay";
        g.logo = GLOBO_LOGO;
        list.add(0, g);
    }

    static boolean isGloboplay(Channel ch) {
        return ch != null && GLOBOPLAY.equals(ch.url);
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
        // institutional: Congress and the courts
        "^tv camara (\\(brazil|2)", "^tv senado \\(brazil", "^tv justica",
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
            + "discovery|cartoon network|studio universal|fox|universal tv|rede globo|tv globo|globo|a&e|amc|axn|sony|"
            + "history|h2|nat ?geo|national geographic|tlc|gnt|multishow|viva|canal brasil|canal off|bis|gloob|gloobinho|"
            + "paramount|syfy|e!|lifetime|comedy central|cinemax|star channel|space|arte 1|film ?& ?arts|curta!|"
            + "prime box|tooncast|disney|nick jr\\.?$|discovery kids|investigation discovery|food network|hgtv|"
            + "animal planet|dazn|tv ?zyn|sbt ?sports|tnt sports|canal like|woohoo|music box brazil)\\b");

    // True for a Brazilian channel (tvg-id ".br") from that pay-TV list, or a pay channel's
    // "Latin America" feed: these lists only carry them as unofficial re-streams.
    // Whatever country code the list gives them (pay channels' "Latin America" feeds come marked as
    // other countries'). Pluto's own channels never come through here.
    static boolean unofficial(Channel ch) {
        return PAY_TV.matcher(ch.name).find() || PAY_TV_PT.matcher(ch.search).find()
                || ch.search.contains("latin america");
    }

    // Portugal's pay channels, as carried by iptv-org (M3UPT's own list only has official streams).
    private static final java.util.regex.Pattern PAY_TV_PT = java.util.regex.Pattern.compile(
            "^(sport ?tv|tvcine|hollywood|eleven|benfica tv|canal panda|biggs|cartoonito|nos studios|mov|canal q|eurosport)\\b");

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

    // Turns any CazéTV entry into the official one: its YouTube live page. Returns true if it was CazéTV.
    static boolean officialCaze(Channel ch) {
        if (ch == null || !ch.search.startsWith("caze")) return false;
        ch.name = "CazéTV";
        ch.search = fold(ch.name);
        ch.url = CazeLive.PAGE;
        ch.group = "Sports";
        ch.referrer = null;
        ch.userAgent = null;
        return true;
    }

    static boolean isCaze(Channel ch) {
        return ch != null && CazeLive.PAGE.equals(ch.url);
    }

    // ---- Favourites and the last channel watched, kept in the app's preferences ----

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("tvaberta", 0);
    }

    static List<Channel> favorites(Context c) {
        List<Channel> out = new ArrayList<>();
        for (String line : prefs(c).getString("favs", "").split("\n")) {
            Channel ch = line.isEmpty() ? null : Channel.decode(line);
            if (ch == null) continue;
            // Unofficial pay-TV re-streams saved by older versions never play (Pluto's own channels are fine).
            if (!key(ch).startsWith("pluto:") && PAY_TV.matcher(ch.name).find()) continue;
            ch.group = groupPt(ch.group); // saved by older versions with the lists' English labels
            officialCaze(ch);
            out.add(ch);
        }
        return out;
    }

    static boolean isFavorite(Context c, Channel ch) {
        String k = key(ch);
        for (Channel f : favorites(c)) if (key(f).equals(k)) return true;
        return false;
    }

    // Adds or removes a channel; returns true if it is a favourite now.
    static boolean toggleFavorite(Context c, Channel ch) {
        List<Channel> favs = favorites(c);
        boolean removed = false;
        String k = key(ch);
        for (java.util.Iterator<Channel> it = favs.iterator(); it.hasNext(); )
            if (key(it.next()).equals(k)) { it.remove(); removed = true; }
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
        Channel ch = s == null ? null : Channel.decode(s);
        officialCaze(ch);
        return ch;
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
