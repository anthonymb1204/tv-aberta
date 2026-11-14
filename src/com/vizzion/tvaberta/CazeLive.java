package com.vizzion.tvaberta;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

// CazéTV's next or current official live on YouTube, read from its public "/live" page (no API key).
// The same file lives in both apps (home and TV Aberta), each in its own package.
final class CazeLive {
    static final String PAGE = "https://www.youtube.com/@CazeTV/live";
    private static final String YOUTUBE_TV = "com.google.android.youtube.tv";

    String videoId, title;
    boolean liveNow;
    long start; // when it starts (or started), in ms; 0 if unknown

    // Fetches the page; null when it can't be read. Call it off the main thread.
    static CazeLive fetch() {
        try {
            java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(PAGE).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(10000);
            con.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36");
            con.setRequestProperty("Accept-Language", "pt-BR");
            String html;
            try (java.io.InputStream in = con.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[16384];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                html = out.toString("UTF-8");
            } finally {
                con.disconnect();
            }
            CazeLive c = new CazeLive();
            c.videoId = find(html, "<link rel=\"canonical\" href=\"https://www.youtube.com/watch\\?v=([\\w-]{6,})\"");
            if (c.videoId == null) return null; // no live and nothing scheduled
            String t = find(html, "\"videoDetails\":\\{\"videoId\":\"[\\w-]+\",\"title\":\"((?:[^\"\\\\]|\\\\.)*)\"");
            c.title = t == null ? "" : tidy(unescape(t));
            String live = find(html, "\"liveBroadcastDetails\":\\{\"isLiveNow\":(true|false)");
            c.liveNow = "true".equals(live);
            String ts = find(html, "\"startTimestamp\":\"([^\"]+)\"");
            if (ts != null) try {
                c.start = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.ROOT).parse(ts).getTime();
            } catch (Exception ignored) {}
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    // A game day: live now, or a live set for later today.
    boolean today() {
        if (liveNow) return true;
        if (start <= System.currentTimeMillis()) return false;
        java.util.Calendar a = java.util.Calendar.getInstance(), b = java.util.Calendar.getInstance();
        b.setTimeInMillis(start);
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
                && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
    }

    String startTime() {
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(start));
    }

    // Opens the live in the YouTube app (any app that takes the link, if YouTube for TV isn't there).
    static void open(Context ctx, String videoId) {
        Uri u = Uri.parse("https://www.youtube.com/watch?v=" + videoId);
        Intent i = new Intent(Intent.ACTION_VIEW, u).setPackage(YOUTUBE_TV).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(i); }
        catch (Exception e) {
            try { ctx.startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ignored) {}
        }
    }

    private static String find(String s, String regex) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'u' && i + 4 < s.length()) {
                    try { b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; continue; }
                    catch (NumberFormatException ignored) {}
                }
                b.append(n == 'n' ? ' ' : n);
            } else b.append(c);
        }
        return b.toString();
    }

    // "AO VIVO: TÊNIS DE MESA | BRUNA ..." -> "Tênis de Mesa | Bruna ...": no shouting, no "ao vivo" twice.
    private static String tidy(String t) {
        t = t.replaceFirst("(?i)^\\s*ao vivo( e com imagens)?\\s*[:!\\-|]*\\s*", "").trim();
        if (!t.equals(t.toUpperCase(java.util.Locale.ROOT))) return t; // already mixed case: leave it
        StringBuilder b = new StringBuilder();
        for (String orig : t.split(" ")) {
            if (b.length() > 0) b.append(' ');
            // Acronyms (no vowels, like WTT, NBA, UFC) stay as they are.
            if (!orig.matches(".*[AEIOUÁÉÍÓÚÂÊÔÃÕ].*")) { b.append(orig); continue; }
            String w = orig.toLowerCase(new java.util.Locale("pt", "BR"));
            boolean small = w.length() <= 2 || w.equals("das") || w.equals("dos");
            b.append(small ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return b.toString();
    }
}
