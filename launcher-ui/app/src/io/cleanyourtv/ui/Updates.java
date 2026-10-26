package io.cleanyourtv.ui;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Is there a newer home screen, and (with the NAS) installing it.
 *
 * <ul>
 * <li>NAS (Config.NAS_URL, see launcher-ui/nas): the NAS keeps the code, watches the GitHub fork, merges and builds.
 * This app asks /api/status with the commit it was built from (Config.BUILD_ID); on OK it asks /api/update, follows
 * the NAS until the APK is ready, then installs it with PackageInstaller: Android asks the viewer to confirm. No PC,
 * no ADB.</li>
 * <li>GitHub only (no nas_url): build.sh bakes the fork and the last 10 launcher-ui commits the build has
 * (Config.UPDATE_REPO, UPDATE_KNOWN); GitHub's list of the latest ones says what is new. Installing is then done on
 * the PC (launcher-ui/update.sh).</li>
 * </ul>
 */
final class Updates {
    interface Listener { void onUpdates(); }

    private static final long GITHUB_EVERY_MS = 6 * 60 * 60_000L, NAS_EVERY_MS = 15 * 60_000L, AGAIN_MS = 2 * 60_000L;
    private static final int PAGE = 10;
    private static final long POLL_MS = 3000, JOB_LIMIT_MS = 20 * 60_000L;

    private static Updates instance;
    private final Context app;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final SharedPreferences prefs;
    /** What is new, newest first; "more" when the GitHub list stops before what the build knows. */
    volatile List<String> titles = new ArrayList<>();
    volatile boolean more;
    volatile long checkedAt;
    /** NAS only: its last job ("idle", "working", "ready", "conflict", "error"), its message and conflicting files. */
    volatile String nasState = "idle", nasMessage = "";
    volatile List<String> nasFiles = new ArrayList<>();
    /** The NAS didn't answer the last time. */
    volatile boolean unreachable;
    /** An update this app started: from OK on the button until Android's install screen (or a failure). */
    volatile boolean running;
    /** A short line for the home screen to show once (a toast), or null. */
    volatile String notice;
    /** The APK is ready but the home screen isn't showing: install at the next resume. */
    volatile boolean installWhenBack;
    private volatile boolean fetching;

    static synchronized Updates get(Context c) {
        if (instance == null) instance = new Updates(c.getApplicationContext());
        return instance;
    }

    private Updates(Context c) {
        app = c;
        prefs = c.getSharedPreferences("updates", Context.MODE_PRIVATE);
        // The saved answer only counts for the build that asked (an update changes what is known).
        if (!nas() && Config.UPDATE_KNOWN.equals(prefs.getString("known", null))) {
            checkedAt = prefs.getLong("at", 0);
            more = prefs.getBoolean("more", false);
            String t = prefs.getString("titles", "");
            titles = t.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(t.split("\n")));
        }
    }

    static boolean nas() {
        return !Config.NAS_URL.isEmpty();
    }

    /** False when this build knows neither the NAS nor its GitHub repo: no button. */
    static boolean enabled() {
        return nas() || (!Config.UPDATE_REPO.isEmpty() && !Config.UPDATE_KNOWN.isEmpty());
    }

    boolean available() { return !titles.isEmpty(); }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    private void changed() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onUpdates(); });
    }

    void refreshIfStale() { refresh(nas() ? NAS_EVERY_MS : GITHUB_EVERY_MS, false); }

    /** On a press of the button (GitHub only): ask again unless the last answer is under 2 minutes old. */
    void refreshSoon() { refresh(AGAIN_MS, true); }

    /**
     * NAS: on a press of the button, a fresh answer first (the NAS is on the LAN: well under a second), then `then` on
     * the main thread, answer or not (3 s at most).
     */
    void askNasThen(Runnable then) {
        if (!nas() || running) {
            then.run();
            return;
        }
        boolean[] done = {false};
        Runnable once = () -> {
            if (done[0]) return;
            done[0] = true;
            then.run();
        };
        main.postDelayed(once, 3000);
        io.execute(() -> {
            try {
                readNas("GET", "/api/status");
            } catch (IOException | org.json.JSONException | RuntimeException e) {
                unreachable = true;
            }
            main.post(once);
        });
    }

    private void refresh(long maxAge, boolean again) {
        long now = System.currentTimeMillis();
        if (!enabled() || fetching || running || (now - checkedAt < maxAge && now >= checkedAt)) return;
        fetching = true;
        io.execute(() -> {
            try {
                if (nas()) readNas("GET", "/api/status");    // the NAS asks GitHub itself, every 6 hours
                else checkGithub();
            } catch (IOException | org.json.JSONException | RuntimeException e) {
                AppLog.w("updates: " + e.getMessage());
                if (nas()) {
                    unreachable = true;
                    changed();
                }
            } finally {
                fetching = false;
            }
        });
    }

    // MARK: - NAS

    /** Reads the NAS's status for this build. Returns the JSON. */
    private JSONObject readNas(String method, String path) throws IOException, org.json.JSONException {
        JSONObject s = new JSONObject(call(method, Config.NAS_URL + path + "?build=" + Config.BUILD_ID, null));
        Set<String> all = new LinkedHashSet<>();
        addAll(all, s.optJSONArray("local_new"));
        addAll(all, s.optJSONArray("fork_new"));
        List<String> fresh = new ArrayList<>(all);
        String st = s.optString("state", "idle"), msg = s.optString("message", "");
        List<String> files = new ArrayList<>();
        addAll(files, s.optJSONArray("files"));
        long now = System.currentTimeMillis();
        main.post(() -> {
            titles = fresh;
            more = false;
            nasState = st;
            nasMessage = msg;
            nasFiles = files;
            unreachable = false;
            checkedAt = now;
        });
        changed();
        return s;
    }

    private static void addAll(java.util.Collection<String> out, JSONArray a) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
    }

    /** OK on "Update": the NAS merges and builds, then this app installs. Progress comes through notice. */
    void startNasUpdate() {
        if (!nas() || running) return;
        running = true;
        installWhenBack = false;
        notice = Ui.tr("O NAS está preparando a atualização (1 a 3 min).", "The NAS is preparing the update (1 to 3 min).");
        changed();
        io.execute(() -> {
            try {
                call("POST", Config.NAS_URL + "/api/update", null);
                long until = System.currentTimeMillis() + JOB_LIMIT_MS;
                while (System.currentTimeMillis() < until) {
                    Thread.sleep(POLL_MS);
                    JSONObject s = readNas("GET", "/api/status");
                    String st = s.optString("state");
                    if ("working".equals(st)) continue;
                    if (s.optBoolean("apk_ready")) {
                        ready();
                        return;
                    }
                    if ("conflict".equals(st) || "error".equals(st)) {
                        finish(null);   // the button's menu says what happened
                        return;
                    }
                }
                finish(Ui.tr("O NAS demorou demais. Tente de novo.", "The NAS took too long. Try again."));
            } catch (InterruptedException e) {
                finish(null);
            } catch (IOException | org.json.JSONException | RuntimeException e) {
                AppLog.w("update: " + e);
                finish(Ui.tr("O NAS não respondeu.", "The NAS didn't answer."));
            }
        });
    }

    private void finish(String message) {
        running = false;
        notice = message;
        changed();
    }

    /** The APK is ready on the NAS: install now if the home screen shows, else when it comes back. */
    private void ready() {
        main.post(() -> {
            if (HomeActivity.visible) install();
            else {
                installWhenBack = true;
                notice = null;
            }
        });
    }

    /** Called when the home screen resumes. */
    void resumed() {
        if (installWhenBack) {
            installWhenBack = false;
            install();
        }
    }

    /** Streams the APK from the NAS into a PackageInstaller session; Android then asks to confirm (InstallReceiver). */
    private void install() {
        if (!app.getPackageManager().canRequestPackageInstalls()) {
            finish(Ui.tr("Permita que o CleanYourTV UI instale apps e tente de novo.",
                    "Allow CleanYourTV UI to install apps, then try again."));
            try {
                app.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + app.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
            }
            return;
        }
        notice = Ui.tr("Baixando a atualização…", "Downloading the update…");
        changed();
        io.execute(() -> {
            PackageInstaller pi = app.getPackageManager().getPackageInstaller();
            int id = -1;
            try {
                PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                p.setAppPackageName(app.getPackageName());
                id = pi.createSession(p);
                try (PackageInstaller.Session session = pi.openSession(id)) {
                    HttpURLConnection c = (HttpURLConnection) new URL(Config.NAS_URL + "/api/apk").openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(30000);
                    try {
                        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                        long size = c.getContentLengthLong();
                        try (InputStream in = c.getInputStream(); OutputStream out = session.openWrite("base.apk", 0, size)) {
                            byte[] b = new byte[65536];
                            int n;
                            while ((n = in.read(b)) > 0) out.write(b, 0, n);
                            session.fsync(out);
                        }
                    } finally {
                        c.disconnect();
                    }
                    Intent done = new Intent(app, InstallReceiver.class);
                    PendingIntent pending = PendingIntent.getBroadcast(app, 0, done,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    session.commit(pending.getIntentSender());
                }
                finish(null);       // Android's screen takes it from here; on success this process is replaced
            } catch (IOException | RuntimeException e) {
                AppLog.w("install: " + e);
                if (id >= 0) {
                    try { pi.abandonSession(id); } catch (RuntimeException ignored) {}
                }
                finish(Ui.tr("Não deu para baixar a atualização do NAS.", "Couldn't download the update from the NAS."));
            }
        });
    }

    // MARK: - GitHub only

    private void checkGithub() throws IOException, org.json.JSONException {
        Set<String> known = new HashSet<>(Arrays.asList(Config.UPDATE_KNOWN.split(",")));
        JSONArray list = new JSONArray(call("GET", "https://api.github.com/repos/" + Config.UPDATE_REPO + "/commits?sha="
                + Config.UPDATE_BRANCH + "&path=launcher-ui&per_page=" + PAGE, null));
        List<String> fresh = new ArrayList<>();
        boolean reachedKnown = false;
        for (int i = 0; i < list.length(); i++) {
            JSONObject c = list.getJSONObject(i);
            String sha = c.getString("sha");
            if (known.contains(sha.substring(0, Math.min(12, sha.length())))) {
                reachedKnown = true;
                break;
            }
            String message = c.getJSONObject("commit").optString("message", "");
            int nl = message.indexOf('\n');
            fresh.add(nl < 0 ? message : message.substring(0, nl));
        }
        long now = System.currentTimeMillis();
        boolean past = !reachedKnown && list.length() >= PAGE;
        prefs.edit().putString("known", Config.UPDATE_KNOWN).putLong("at", now).putBoolean("more", past)
                .putString("titles", String.join("\n", fresh)).apply();
        main.post(() -> {
            titles = fresh;
            more = past;
            checkedAt = now;
        });
        changed();
    }

    private static String call(String method, String url, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", "CleanYourTV-UI");
        c.setRequestProperty("Accept", "application/json");
        try {
            if ("POST".equals(method)) {
                c.setDoOutput(true);
                try (OutputStream o = c.getOutputStream()) {
                    if (body != null) o.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code != 200 && code != 202) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
