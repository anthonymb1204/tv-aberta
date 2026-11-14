package com.vizzion.tvaberta;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

// A tiny Google Cast sender, without Google's library (this app has none): it speaks the Cast
// protocol itself. TLS to the TV on port 8009, then length-prefixed protobuf "CastMessage"s that
// carry JSON. It starts Google's Default Media Receiver on the TV and has it play a stream URL.
// Plain Java on purpose, so it can also be tried from a computer.
final class CastClient implements Closeable {
    private static final String CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
    private static final String HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";
    private static final String RECEIVER = "urn:x-cast:com.google.cast.receiver";
    private static final String MEDIA = "urn:x-cast:com.google.cast.media";
    private static final String DEFAULT_RECEIVER = "CC1AD845"; // Google's Default Media Receiver
    private static final String ME = "sender-tvaberta";

    static boolean DEBUG; // prints every message received (for trying it from a computer)
    private SSLSocket socket;
    private OutputStream out;
    private volatile boolean closed;
    private volatile String transportId, sessionId, mediaReply;
    private int requestId = 1;
    private volatile int loadRequest;
    private volatile String loadSession;
    private final Object replies = new Object();

    void connect(String host, int port) throws IOException {
        try {
            SSLContext tls = SSLContext.getInstance("TLS");
            // Cast devices use their own self-signed certificate: there is nothing to check it against.
            tls.init(null, new TrustManager[] {new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
                @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, null);
            socket = (SSLSocket) tls.getSocketFactory().createSocket();
        } catch (Exception e) {
            throw new IOException("TLS indisponível", e);
        }
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.startHandshake();
        out = socket.getOutputStream();
        final DataInputStream in = new DataInputStream(socket.getInputStream());
        send("receiver-0", CONNECTION, "{\"type\":\"CONNECT\"}");
        Thread reader = new Thread(() -> {
            try {
                while (!closed) {
                    int len = in.readInt();
                    if (len <= 0 || len > 1 << 20) break;
                    byte[] msg = new byte[len];
                    in.readFully(msg);
                    received(msg);
                }
            } catch (IOException ignored) {
            } finally {
                closed = true;
                synchronized (replies) { replies.notifyAll(); }
            }
        }, "cast-reader");
        reader.setDaemon(true);
        reader.start();
        Thread beat = new Thread(() -> {
            while (!closed) {
                try {
                    Thread.sleep(5000);
                    send("receiver-0", HEARTBEAT, "{\"type\":\"PING\"}");
                } catch (Exception e) {
                    break;
                }
            }
        }, "cast-heartbeat");
        beat.setDaemon(true);
        beat.start();
    }

    // Starts the receiver if needed, then loads the stream. Returns null when the TV accepted it,
    // or a short reason when it didn't. Blocks for up to about 20 seconds.
    String play(String url, String title) throws IOException {
        if (transportId == null) {
            send("receiver-0", RECEIVER, "{\"type\":\"LAUNCH\",\"appId\":\"" + DEFAULT_RECEIVER
                    + "\",\"requestId\":" + (requestId++) + "}");
            waitFor(() -> transportId != null, 15000);
            if (transportId == null) return closed ? "a TV fechou a conexão" : "a TV não abriu o player";
            send(transportId, CONNECTION, "{\"type\":\"CONNECT\"}");
        }
        mediaReply = null;
        loadSession = null;
        loadRequest = requestId;
        send(transportId, MEDIA, "{\"type\":\"LOAD\",\"requestId\":" + (requestId++) + ",\"autoplay\":true,"
                + "\"media\":{\"contentId\":\"" + json(url) + "\",\"contentType\":\"" + contentType(url) + "\","
                + "\"streamType\":\"LIVE\",\"metadata\":{\"metadataType\":0,\"title\":\"" + json(title) + "\"}}}");
        waitFor(() -> mediaReply != null, 25000);
        String r = mediaReply;
        if (r == null) return closed ? "a TV fechou a conexão" : "o canal não abriu na TV";
        // Google's player on the TV is a web page: it can't play streams whose server doesn't allow other
        // sites (CORS), or video in H.265. Those channels still work on the phone itself.
        return "PLAYING".equals(r) ? null : "este canal não pode ser transmitido";
    }

    // Pauses or resumes the channel on the TV.
    void pause(boolean pause) {
        try {
            if (transportId != null && loadSession != null) send(transportId, MEDIA, "{\"type\":\""
                    + (pause ? "PAUSE" : "PLAY") + "\",\"mediaSessionId\":" + loadSession + ",\"requestId\":" + (requestId++) + "}");
        } catch (IOException ignored) {}
    }

    // Closes the player on the TV.
    void stop() {
        try {
            if (sessionId != null) send("receiver-0", RECEIVER, "{\"type\":\"STOP\",\"sessionId\":\"" + sessionId
                    + "\",\"requestId\":" + (requestId++) + "}");
        } catch (IOException ignored) {}
    }

    boolean isOpen() { return !closed; }

    @Override
    public void close() {
        closed = true;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    // ---- Messages ----

    private void received(byte[] msg) throws IOException {
        String ns = null, payload = null;
        int[] pos = {0};
        while (pos[0] < msg.length) {
            int key = (int) varint(msg, pos), field = key >>> 3, wire = key & 7;
            if (wire == 0) { varint(msg, pos); continue; }
            if (wire != 2) return; // nothing else is used by Cast
            int len = (int) varint(msg, pos);
            if (field == 4) ns = new String(msg, pos[0], len, StandardCharsets.UTF_8);
            else if (field == 6) payload = new String(msg, pos[0], len, StandardCharsets.UTF_8);
            pos[0] += len;
        }
        if (ns == null || payload == null) return;
        if (DEBUG) System.out.println("<- " + ns.substring(ns.lastIndexOf('.') + 1) + " " + payload);
        String type = find(payload, "\"type\"\\s*:\\s*\"([A-Z_]+)\"");
        if (HEARTBEAT.equals(ns) && "PING".equals(type)) {
            send("receiver-0", HEARTBEAT, "{\"type\":\"PONG\"}");
        } else if (RECEIVER.equals(ns) && "RECEIVER_STATUS".equals(type)) {
            int at = payload.indexOf("\"" + DEFAULT_RECEIVER + "\"");
            if (at >= 0) {
                String app = payload.substring(Math.max(0, payload.lastIndexOf('{', at)));
                String t = find(app, "\"transportId\"\\s*:\\s*\"([^\"]+)\"");
                String s = find(app, "\"sessionId\"\\s*:\\s*\"([^\"]+)\"");
                if (t != null) { transportId = t; sessionId = s; }
            } else if (transportId != null && payload.contains("\"applications\"")) {
                transportId = null; // the player was closed on the TV
            }
        } else if (MEDIA.equals(ns) && type != null) {
            // The answer to our LOAD: the player reaching PLAYING (or BUFFERING) means it took; LOAD_FAILED,
            // LOAD_CANCELLED, INVALID_REQUEST, or going idle with an error, mean it didn't. Statuses
            // about an earlier channel ("requestId":0 or older) are ignored, except to learn it failed.
            String req = find(payload, "\"requestId\"\\s*:\\s*(\\d+)");
            boolean ours = req != null && Integer.parseInt(req) == loadRequest;
            String state = find(payload, "\"playerState\"\\s*:\\s*\"([A-Z]+)\"");
            String session = find(payload, "\"mediaSessionId\"\\s*:\\s*(\\d+)");
            if (ours && session != null) loadSession = session; // each LOAD gets a new media session
            boolean current = session != null && session.equals(loadSession);
            if (type.startsWith("LOAD_") || type.equals("INVALID_REQUEST")) { if (ours) mediaReply = type; }
            else if (current && payload.contains("\"idleReason\":\"ERROR\"")) mediaReply = "ERROR";
            else if (current && ("PLAYING".equals(state) || "BUFFERING".equals(state))) mediaReply = "PLAYING";
        } else if (CONNECTION.equals(ns) && "CLOSE".equals(type)) {
            transportId = null;
        }
        synchronized (replies) { replies.notifyAll(); }
    }

    private synchronized void send(String to, String ns, String payload) throws IOException {
        if (closed || out == null) throw new IOException("sem conexão");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x08); b.write(0);           // protocol_version = CASTV2_1_0
        field(b, 2, ME);                     // source_id
        field(b, 3, to);                     // destination_id
        field(b, 4, ns);                     // namespace
        b.write(0x28); b.write(0);           // payload_type = STRING
        field(b, 6, payload);                // payload_utf8
        byte[] m = b.toByteArray();
        out.write(new byte[] {(byte) (m.length >>> 24), (byte) (m.length >>> 16), (byte) (m.length >>> 8), (byte) m.length});
        out.write(m);
        out.flush();
    }

    private interface Condition { boolean met(); }

    private void waitFor(Condition c, long ms) {
        long end = System.currentTimeMillis() + ms;
        synchronized (replies) {
            while (!c.met() && !closed) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) return;
                try { replies.wait(left); } catch (InterruptedException e) { return; }
            }
        }
    }

    private static void field(ByteArrayOutputStream b, int field, String s) {
        byte[] v = s.getBytes(StandardCharsets.UTF_8);
        b.write((field << 3) | 2);
        int n = v.length;
        while ((n & ~0x7F) != 0) { b.write((n & 0x7F) | 0x80); n >>>= 7; }
        b.write(n);
        b.write(v, 0, v.length);
    }

    private static long varint(byte[] m, int[] pos) {
        long v = 0;
        for (int shift = 0; pos[0] < m.length && shift < 64; shift += 7) {
            int x = m[pos[0]++] & 0xFF;
            v |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) break;
        }
        return v;
    }

    private static String find(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    static String contentType(String url) {
        String u = url.toLowerCase(java.util.Locale.ROOT);
        if (u.contains(".mpd")) return "application/dash+xml";
        if (u.contains(".mp4")) return "video/mp4";
        return "application/x-mpegURL"; // nearly every channel is HLS (.m3u8)
    }

    private static String json(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.toString();
    }
}
