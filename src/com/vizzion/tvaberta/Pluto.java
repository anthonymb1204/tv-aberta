package com.vizzion.tvaberta;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Pluto TV Brasil, straight from Pluto (free, with ads): this device opens its own session, then
// reads the channel line-up and Pluto's own categories. Each stream link carries the session's
// token. The session is asked for as Pluto's web player with the ads put in by the player: Pluto
// then sends plain video segments with picture and sound together, which Android's own player
// plays (with the ads stitched in by Pluto's server, sound and picture come in separate files,
// and Android shows a black screen).
final class Pluto {
    private static final String BOOT = "https://boot.pluto.tv/v4/start?appName=web&appVersion=8.0.0&deviceVersion=1.0"
            + "&deviceModel=web&deviceMake=chrome&deviceType=web&clientModelNumber=1.0.0&serverSideAds=false&clientID=";
    private static final String CHANNELS = "https://service-channels.clusters.pluto.tv/v2/guide/channels"
            + "?channelIds=&offset=0&limit=1000&sort=number%3Aasc";
    private static final String CATEGORIES = "https://service-channels.clusters.pluto.tv/v2/guide/categories";
    private static final long LINEUP_MS = 12L * 3600_000L;

    private Pluto() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("pluto", 0);
    }

    // The session: a token and the parameters every stream link needs. Kept until an hour before it
    // runs out (Pluto gives about a day).
    private static synchronized String[] session(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        long exp = p.getLong("exp", 0);
        if (System.currentTimeMillis() < exp - 3600_000L && p.getString("token", null) != null)
            return new String[] {p.getString("token", null), p.getString("params", ""), p.getString("stitcher", "")};
        String client = p.getString("client", null);
        if (client == null) { client = UUID.randomUUID().toString(); p.edit().putString("client", client).apply(); }
        JSONObject boot = new JSONObject(get(BOOT + client, null));
        String token = boot.getString("sessionToken");
        String params = boot.optString("stitcherParams", "");
        String stitcher = boot.getJSONObject("servers").getString("stitcher");
        long until = System.currentTimeMillis() + 20 * 3600_000L;
        try { // the token's own expiry, from inside it
            String body = new String(android.util.Base64.decode(token.split("\\.")[1],
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP), "UTF-8");
            until = new JSONObject(body).getLong("exp") * 1000L;
        } catch (Exception ignored) {}
        p.edit().putString("token", token).putString("params", params).putString("stitcher", stitcher)
                .putLong("exp", until).apply();
        return new String[] {token, params, stitcher};
    }

    // Pluto TV Brasil's channels, with their category as the group ("Filmes", "Novelas"...). The
    // line-up is kept for 12 hours; the links are rebuilt from the current session every time.
    static List<Channels.Channel> channels(Context c) throws Exception {
        String[] s = session(c);
        File lineup = new File(c.getFilesDir(), "pluto_lineup.json"), cats = new File(c.getFilesDir(), "pluto_categories.json");
        if (!lineup.exists() || System.currentTimeMillis() - lineup.lastModified() > LINEUP_MS) {
            try {
                save(lineup, get(CHANNELS, s[0]));
                save(cats, get(CATEGORIES, s[0]));
            } catch (Exception e) {
                if (!lineup.exists()) throw e; // offline and never fetched
            }
        }
        Map<String, String> groupOf = new HashMap<>();
        try {
            JSONArray cs = new JSONObject(read(cats)).getJSONArray("data");
            for (int i = 0; i < cs.length(); i++) {
                JSONObject cat = cs.getJSONObject(i);
                JSONArray ids = cat.optJSONArray("channelIDs");
                if (ids != null) for (int j = 0; j < ids.length(); j++)
                    if (!groupOf.containsKey(ids.getString(j))) groupOf.put(ids.getString(j), cat.getString("name"));
            }
        } catch (Exception ignored) {} // without categories, everything lands in Entretenimento
        JSONArray data = new JSONObject(read(lineup)).getJSONArray("data");
        List<Channels.Channel> out = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.getJSONObject(i);
            JSONObject st = o.optJSONObject("stitched");
            if (st == null || st.optString("path", "").isEmpty()) continue;
            Channels.Channel ch = new Channels.Channel();
            ch.name = o.getString("name").trim();
            ch.search = Channels.fold(ch.name);
            ch.group = groupOf.get(o.getString("id"));
            ch.url = s[2] + "/v2" + st.getString("path") + "?" + s[1] + "&jwt=" + s[0] + "&masterJWTPassthrough=true";
            JSONArray imgs = o.optJSONArray("images");
            if (imgs != null) for (int j = 0; j < imgs.length(); j++) {
                JSONObject im = imgs.getJSONObject(j);
                if ("colorLogoPNG".equals(im.optString("type"))) { ch.logo = im.optString("url"); break; }
            }
            out.add(ch);
        }
        return out;
    }

    private static String get(String url, String token) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(15000);
        if (token != null) con.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (con.getResponseCode() != 200) throw new Exception("Pluto HTTP " + con.getResponseCode());
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                return b.toString("UTF-8");
            }
        } finally {
            con.disconnect();
        }
    }

    private static void save(File f, String s) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) { out.write(s.getBytes("UTF-8")); }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
    }

    private static String read(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }
}
