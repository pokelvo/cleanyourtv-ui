package io.cleanyourtv.ui;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Philips JointSPACE (API 6) on the TV itself, for Ambilight and Ambilight + Hue. The app was paired once (PIN on the
 * TV); its device id and key are in config.properties ("jointspace_user", "jointspace_pass"), built in, never logged.
 * The TV's server has a self-signed certificate and handles one connection at a time: it is only trusted for the TV
 * (127.0.0.1), and Digest auth is done by hand, each request on its own connection. Call from a background thread.
 */
final class JointSpace {
    private JointSpace() {}

    /** The "Ambilight + hue" on/off switch (Settings > Ambilight > Ambilight extension > Ambilight + hue). */
    static final long HUE_NODE = 2130968816L;
    private static final String host = "127.0.0.1";

    /**
     * Off: on this firmware (OLED907, RTT2.211108.001) the server never closes the connections it couldn't serve, and
     * org.droidtv.xtv then spins at ~100% CPU, slowing the whole TV until it restarts. The Ambilight button opens
     * Philips' own Ambilight menu instead. Kept for firmwares where the API behaves.
     */
    /**
     * Off on this TV (OLED907, firmware RTT2.211108.001): measured on 2026-10-25, any call from the TV itself (even one,
     * well spaced, TLS 1.2, long waits) hangs and sends org.droidtv.xtv to 100% CPU until a reboot; the same calls from
     * another computer work. The Ambilight button opens Philips' Ambilight menu instead.
     */
    static boolean on() {
        return false;
    }

    /** After a failure, no call for 10 minutes: retrying on a server that is stuck only makes it worse. */
    private static volatile long pausedUntil;

    private static void pause() {
        pausedUntil = android.os.SystemClock.uptimeMillis() + 10 * 60_000L;
    }

    /** Ambilight + Hue now: {ambilight on, hue on, hue switch usable (a Hue bridge is set up)}. */
    static boolean[] state() throws IOException {
        try {
            boolean ambi = "On".equalsIgnoreCase(new JSONObject(call("GET", "ambilight/power", null)).optString("power"));
            JSONObject v = new JSONObject(call("POST", "menuitems/settings/current",
                    "{\"nodes\":[{\"nodeid\":" + HUE_NODE + "}]}")).getJSONArray("values").getJSONObject(0).getJSONObject("value");
            boolean hue = v.optJSONObject("data") != null && v.getJSONObject("data").optBoolean("value");
            return new boolean[] {ambi, hue, v.optBoolean("Controllable")};
        } catch (org.json.JSONException e) {
            throw new IOException("unexpected reply");
        }
    }

    /** Turns Ambilight and (when set up) Ambilight + Hue on or off together. */
    static void set(boolean on) throws IOException {
        // Ambilight first: it answers at once. The Hue switch can take 15 s or more (the TV links to the bridge), and the
        // server handles one request at a time, so it goes last, with a long wait.
        call("POST", "ambilight/power", "{\"power\":\"" + (on ? "On" : "Off") + "\"}");
        slow = true;
        try {
            call("POST", "menuitems/settings/update", "{\"values\":[{\"value\":{\"Nodeid\":" + HUE_NODE
                    + ",\"Controllable\":true,\"Available\":true,\"data\":{\"value\":" + on + "}}}]}");
        } catch (IOException e) {
            AppLog.w("hue: " + e.getMessage());    // no Hue bridge set up: Ambilight alone
        } finally {
            slow = false;
        }
    }

    private static volatile boolean slow;

    // MARK: - HTTP with Digest auth, one connection per request

    /**
     * One try only: the TV's server (inside org.droidtv.xtv) handles one connection at a time and never closes the
     * ones a client gave up on, so retries after a timeout pile up until it stops answering.
     */
    private static String call(String method, String path, String body) throws IOException {
        try {
            return call1(method, path, body);
        } catch (IOException e) {
            pause();
            throw e;
        }
    }

    /** The last Digest challenge (realm, nonce, qop, opaque): later calls answer it at once, on a single connection. */
    private static Map<String, String> challenge;
    private static int count;

    private static synchronized String call1(String method, String path, String body) throws IOException {
        if (challenge != null) {
            String[] r = once(method, path, body, answer(method, path));
            if (!"401".equals(r[0]) || r[2] == null) return check(r);
            learn(r[2]);    // the nonce went stale: answer the new one
        } else {
            String[] r = once(method, path, body, null);
            if (!"401".equals(r[0]) || r[2] == null) return check(r);
            learn(r[2]);
        }
        return check(once(method, path, body, answer(method, path)));
    }

    private static void learn(String header) {
        Map<String, String> f = new HashMap<>();
        Matcher m = Pattern.compile("(\\w+)=\"?([^\",]+)\"?").matcher(header);
        while (m.find()) f.put(m.group(1), m.group(2));
        challenge = f;
        count = 0;
    }

    private static String answer(String method, String path) {
        Map<String, String> f = challenge;
        String uri = "/6/" + path, user = Config.JS_USER.trim();
        String ha1 = md5(user + ":" + f.get("realm") + ":" + Config.JS_PASS.trim()), ha2 = md5(method + ":" + uri);
        String nc = String.format("%08x", ++count), cnonce = Long.toHexString(new SecureRandom().nextLong());
        String auth;
        if (f.containsKey("qop")) {
            auth = "Digest username=\"" + user + "\", realm=\"" + f.get("realm") + "\", nonce=\"" + f.get("nonce") + "\", uri=\"" + uri
                    + "\", qop=auth, nc=" + nc + ", cnonce=\"" + cnonce + "\", response=\""
                    + md5(ha1 + ":" + f.get("nonce") + ":" + nc + ":" + cnonce + ":auth:" + ha2) + "\"";
        } else {
            auth = "Digest username=\"" + user + "\", realm=\"" + f.get("realm") + "\", nonce=\"" + f.get("nonce") + "\", uri=\"" + uri
                    + "\", response=\"" + md5(ha1 + ":" + f.get("nonce") + ":" + ha2) + "\"";
        }
        if (f.containsKey("opaque")) auth += ", opaque=\"" + f.get("opaque") + "\"";
        return auth;
    }

    private static String check(String[] r) throws IOException {
        if (!"200".equals(r[0])) throw new IOException("HTTP " + r[0]);
        return r[1];
    }

    /** {status, body, WWW-Authenticate}. */
    private static String[] once(String method, String path, String body, String auth) throws IOException {
        String tv = host;
        HttpsURLConnection c = (HttpsURLConnection) new URL("https://" + tv + ":1926/6/" + path).openConnection();
        c.setSSLSocketFactory(trustTv());
        c.setHostnameVerifier((h, s) -> tv.equals(h));      // only the TV itself
        c.setConnectTimeout(5000);
        c.setReadTimeout(60000);    // never give up early: an abandoned request stays open on the TV's server
        c.setRequestMethod(method);
        c.setRequestProperty("Connection", "close");
        c.setRequestProperty("Content-Type", "application/json");
        if (auth != null) c.setRequestProperty("Authorization", auth);
        try {
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) {
                try (InputStream i = in) {
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = i.read(b)) > 0) out.write(b, 0, n);
                }
            }
            return new String[] {String.valueOf(code), out.toString(StandardCharsets.UTF_8.name()), c.getHeaderField("WWW-Authenticate")};
        } finally {
            c.disconnect();
        }
    }

    private static SSLSocketFactory factory;

    /** The TV's own self-signed certificate (this connection only goes to 127.0.0.1). */
    private static synchronized SSLSocketFactory trustTv() throws IOException {
        if (factory != null) return factory;
        try {
            // TLS 1.2 only: Android 11 offers TLS 1.3 first, and the TV's server stalls on that handshake.
            SSLContext ctx = SSLContext.getInstance("TLSv1.2");
            ctx.init(null, new TrustManager[] {new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String type) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String type) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            return factory = new Tls12(ctx.getSocketFactory());
        } catch (Exception e) {
            throw new IOException("TLS unavailable");
        }
    }

    /** Sockets with only TLS 1.2 enabled. */
    private static final class Tls12 extends SSLSocketFactory {
        private final SSLSocketFactory d;
        Tls12(SSLSocketFactory d) { this.d = d; }
        private java.net.Socket only(java.net.Socket s) {
            if (s instanceof javax.net.ssl.SSLSocket) ((javax.net.ssl.SSLSocket) s).setEnabledProtocols(new String[] {"TLSv1.2"});
            return s;
        }
        @Override public String[] getDefaultCipherSuites() { return d.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return d.getSupportedCipherSuites(); }
        @Override public java.net.Socket createSocket(java.net.Socket s, String h, int p, boolean a) throws IOException { return only(d.createSocket(s, h, p, a)); }
        @Override public java.net.Socket createSocket(String h, int p) throws IOException { return only(d.createSocket(h, p)); }
        @Override public java.net.Socket createSocket(String h, int p, java.net.InetAddress l, int lp) throws IOException { return only(d.createSocket(h, p, l, lp)); }
        @Override public java.net.Socket createSocket(java.net.InetAddress h, int p) throws IOException { return only(d.createSocket(h, p)); }
        @Override public java.net.Socket createSocket(java.net.InetAddress h, int p, java.net.InetAddress l, int lp) throws IOException { return only(d.createSocket(h, p, l, lp)); }
    }

    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
