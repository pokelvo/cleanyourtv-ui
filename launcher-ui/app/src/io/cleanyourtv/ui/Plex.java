package io.cleanyourtv.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Plex tab: what is being watched on the Plex server at home (config.properties "plex_url" and "plex_token", built
 * into the app), from the Movies and Shows libraries only. Local network, 3 s timeouts, refreshed when the home screen
 * shows (at most every 2 minutes). The token goes in a header, never in a URL, and never to the log.
 * Each title is a Recs.Pick (kind "plex"), so the home screen's cards draw it like the other tabs' picks.
 */
final class Plex {
    interface Listener { void onPlex(); }

    static final int LOADING = 0, READY = 1, UNREACHABLE = 2;
    private static final int MAX = 12, TIMEOUT_MS = 6000;
    /**
     * Images kept in memory while the home screen shows: all of them (~3 MB each once built), so the carousel never
     * builds one again on every turn; trim() keeps only the cards on screen while an app is in front.
     */
    private static final int KEEP = MAX;
    private static final long FRESH_MS = 2 * 60_000L;
    static final String PACKAGE = "com.plexapp.android";

    private static Plex instance;
    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Backdrop> backdrops = new LinkedHashMap<>();
    private final Map<String, Bitmap> posters = new LinkedHashMap<>();
    private final Set<String> pending = new HashSet<>();
    private volatile List<Recs.Pick> items = new ArrayList<>();
    private volatile int state = LOADING;
    private volatile long fetchedAt;
    private volatile boolean fetching;
    private volatile String machine;
    // Per pick (by its key): the image paths on the server and the item's metadata key.
    private final Map<String, String[]> paths = new java.util.concurrent.ConcurrentHashMap<>();

    static synchronized Plex get(Context c) {
        if (instance == null) instance = new Plex(c.getApplicationContext());
        return instance;
    }

    private Plex(Context c) {
        context = c;
    }

    static boolean on() {
        return !Config.PLEX_URL.trim().isEmpty() && !Config.PLEX_TOKEN.trim().isEmpty();
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    List<Recs.Pick> items() { return items; }
    int state() { return state; }

    static boolean is(Recs.Pick p) {
        return p != null && "plex".equals(p.kind);
    }

    void refreshIfStale() {
        if (!on() || fetching) return;
        long now = System.currentTimeMillis();
        long fresh = state == UNREACHABLE ? 20_000L : FRESH_MS;    // unreachable: try again soon, not in 2 minutes
        if (fetchedAt != 0 && now - fetchedAt < fresh && now >= fetchedAt) return;
        fetching = true;
        io.execute(() -> {
            try {
                List<Recs.Pick> list = fetch();
                items = list;
                state = READY;
            } catch (IOException | RuntimeException e) {
                state = UNREACHABLE;
                AppLog.w("plex: " + e.getClass().getSimpleName());
            } finally {
                fetchedAt = System.currentTimeMillis();
                fetching = false;
                notifyListeners();
            }
        });
    }

    private List<Recs.Pick> fetch() throws IOException {
        try {
            if (machine == null) machine = get("/identity").getJSONObject("MediaContainer").optString("machineIdentifier", null);
            Set<String> sections = new HashSet<>();
            JSONArray dirs = get("/library/sections").getJSONObject("MediaContainer").optJSONArray("Directory");
            for (int i = 0; dirs != null && i < dirs.length(); i++) {
                JSONObject d = dirs.getJSONObject(i);
                String type = d.optString("type");
                if (type.equals("movie") || type.equals("show")) sections.add(d.optString("key"));
            }
            JSONArray meta = null;
            try {
                JSONObject mc = get("/hubs/continueWatching?count=" + MAX).getJSONObject("MediaContainer");
                JSONArray hubs = mc.optJSONArray("Hub");
                meta = hubs != null && hubs.length() > 0 ? hubs.getJSONObject(0).optJSONArray("Metadata") : mc.optJSONArray("Metadata");
            } catch (IOException e) {
                meta = null;
            }
            if (meta == null) meta = get("/library/onDeck").getJSONObject("MediaContainer").optJSONArray("Metadata");
            List<Recs.Pick> out = new ArrayList<>();
            for (int i = 0; meta != null && i < meta.length() && out.size() < MAX; i++) {
                JSONObject m = meta.getJSONObject(i);
                if (!sections.contains(m.optString("librarySectionID"))) continue;
                Recs.Pick p = pick(m);
                if (p != null) out.add(p);
            }
            addRecent(out, sections);
            return out;
        } catch (org.json.JSONException e) {
            throw new IOException("unexpected reply");
        }
    }

    /**
     * After what is being watched, what was added lately (Movies and Shows taking turns, one card per show), so the
     * carousel has up to MAX titles.
     */
    private void addRecent(List<Recs.Pick> out, Set<String> sections) throws IOException, org.json.JSONException {
        Set<String> seen = new HashSet<>();
        for (Recs.Pick p : out) seen.add(p.title);
        List<List<Recs.Pick>> lists = new ArrayList<>();
        for (String key : sections) {
            List<Recs.Pick> list = new ArrayList<>();
            JSONArray meta = get("/library/sections/" + key + "/recentlyAdded?X-Plex-Container-Start=0&X-Plex-Container-Size=40")
                    .getJSONObject("MediaContainer").optJSONArray("Metadata");
            for (int i = 0; meta != null && i < meta.length(); i++) {
                Recs.Pick p = pick(meta.getJSONObject(i));
                if (p == null || p.progress > 0 || !seen.add(p.title)) continue;
                p.recent = true;
                if (meta.getJSONObject(i).optString("type").equals("episode")) {
                    p.meta = Ui.tr("Série", "Show");     // a new show, not a given episode
                    p.shortMeta = p.meta;
                }
                list.add(p);
            }
            lists.add(list);
        }
        for (int i = 0; out.size() < MAX; i++) {
            boolean any = false;
            for (List<Recs.Pick> l : lists) {
                if (i < l.size() && out.size() < MAX) {
                    out.add(l.get(i));
                    any = true;
                }
            }
            if (!any) break;
        }
    }

    /** One title: the show's name and its season and episode (or the movie), the time left and its images. */
    private Recs.Pick pick(JSONObject m) {
        String type = m.optString("type");
        boolean episode = type.equals("episode");
        if (!episode && !type.equals("movie")) return null;
        Recs.Pick p = new Recs.Pick();
        p.kind = "plex";
        p.id = m.optInt("ratingKey");
        p.title = episode ? m.optString("grandparentTitle", m.optString("title")) : m.optString("title");
        long offset = m.optLong("viewOffset"), duration = m.optLong("duration");
        p.progress = duration > 0 && offset > 0 ? Math.max(0, Math.min(1, offset / (float) duration)) : -1;
        String left = duration > 0 ? Ui.tr("Faltam ", "Left: ") + minutes(Math.max(1, (duration - offset) / 60_000L)) : "";
        if (duration > 0) p.left = "−" + minutes(Math.max(1, (duration - offset) / 60_000L));
        if (duration > 0) p.leftMin = (int) Math.max(1, (duration - offset) / 60_000L);
        StringBuilder meta = new StringBuilder(episode ? Ui.tr("Série", "Show") : Ui.tr("Filme", "Movie"));
        if (episode) {
            if (m.has("parentIndex")) meta.append(" · ").append(Ui.tr("Temporada ", "Season ")).append(m.optInt("parentIndex"));
            if (m.has("index")) meta.append(" · ").append(Ui.tr("Episódio ", "Episode ")).append(m.optInt("index"));
        }
        if (!left.isEmpty()) meta.append(" · ").append(left);
        p.meta = meta.toString();
        StringBuilder small = new StringBuilder();
        if (episode && m.has("parentIndex") && m.has("index")) small.append("S").append(m.optInt("parentIndex")).append(" · E").append(m.optInt("index"));
        if (!left.isEmpty()) small.append(small.length() > 0 ? " · " : "").append(left);
        p.shortMeta = small.length() > 0 ? small.toString() : p.meta;
        p.app = "Plex";
        p.pkg = PACKAGE;
        p.detailed = true;
        String art = episode ? m.optString("grandparentArt", m.optString("art")) : m.optString("art");
        String thumb = episode ? m.optString("grandparentThumb", m.optString("thumb")) : m.optString("thumb");
        p.backdrop = art.isEmpty() ? (thumb.isEmpty() ? null : thumb) : art;
        paths.put(Recs.key(p), new String[] {p.backdrop, thumb.isEmpty() ? p.backdrop : thumb, m.optString("key")});
        return p;
    }

    /** "1 h 04", "23 min". */
    private static String minutes(long m) {
        return m >= 60 ? (m / 60) + " h " + String.format(java.util.Locale.ROOT, "%02d", m % 60) : m + " min";
    }

    // MARK: - Images (the server scales them: /photo/:/transcode; kept on disk)

    /** The pick's backdrop (wide card and blurred background), or null: built in the background, then notifies. */
    Backdrop backdrop(Recs.Pick p) {
        String[] s = p == null ? null : paths.get(Recs.key(p));
        if (s == null || s[0] == null) return null;
        synchronized (backdrops) {
            Backdrop b = backdrops.get(s[0]);
            if (b != null || !pending.add("b" + s[0])) return b;
        }
        io.execute(() -> {
            try {
                Bitmap src = decode(image(s[0], Backdrop.W, Backdrop.H));
                Backdrop b = Backdrop.make(src, true, Scene.SHADERS, false);     // tall cards show the poster
                synchronized (backdrops) {
                    backdrops.put(s[0], b);
                    while (backdrops.size() > KEEP) backdrops.remove(backdrops.keySet().iterator().next());
                }
                notifyListeners();
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                AppLog.w("plex image: " + e.getClass().getSimpleName());
            } finally {
                synchronized (backdrops) { pending.remove("b" + s[0]); }
            }
        });
        return null;
    }

    /** The poster for a tall card (at its focused size), or null: loaded in the background, then notifies. */
    Bitmap poster(Recs.Pick p) {
        String[] s = p == null ? null : paths.get(Recs.key(p));
        if (s == null || s[1] == null) return null;
        synchronized (posters) {
            Bitmap b = posters.get(s[1]);
            if (b != null || !pending.add("p" + s[1])) return b;
        }
        io.execute(() -> {
            try {
                Bitmap b = crop(decode(image(s[1], Backdrop.TALL_W * 2, Backdrop.TALL_H * 2)), Backdrop.TALL_W, Backdrop.TALL_H);
                synchronized (posters) {
                    posters.put(s[1], b);
                    while (posters.size() > KEEP) posters.remove(posters.keySet().iterator().next());
                }
                notifyListeners();
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                AppLog.w("plex poster: " + e.getClass().getSimpleName());
            } finally {
                synchronized (posters) { pending.remove("p" + s[1]); }
            }
        });
        return null;
    }

    /** The middle of `src` at the card's shape, scaled to w × h (a 2:3 poster on a 320 × 380 card is not stretched). */
    private static Bitmap crop(Bitmap src, int w, int h) {
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        float cw = w / scale, ch = h / scale;
        float x = (src.getWidth() - cw) / 2, y = (src.getHeight() - ch) / 2;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new android.graphics.Canvas(out).drawBitmap(src, new android.graphics.Rect(Math.round(x), Math.round(y), Math.round(x + cw), Math.round(y + ch)),
                new android.graphics.Rect(0, 0, w, h), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        return out;
    }

    /** The home screen left the screen: only the images of `keep` stay in memory. */
    void trim(List<Recs.Pick> keep) {
        Set<String> paths0 = new HashSet<>(), paths1 = new HashSet<>();
        for (Recs.Pick p : keep) {
            String[] s = paths.get(Recs.key(p));
            if (s != null) {
                paths0.add(s[0]);
                paths1.add(s[1]);
            }
        }
        synchronized (backdrops) { backdrops.keySet().retainAll(paths0); }
        synchronized (posters) { posters.keySet().retainAll(paths1); }
    }

    private static Bitmap decode(File f) throws IOException {
        Bitmap b = BitmapFactory.decodeFile(f.getPath());
        if (b == null) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            throw new IOException("unreadable image");
        }
        return b;
    }

    /** The image at path, scaled and cropped by the server to w × h, from the disk cache or downloaded. */
    private File image(String path, int w, int h) throws IOException {
        File dir = new File(context.getCacheDir(), "plex");
        String name = Integer.toHexString((path + "@" + w + "x" + h).hashCode()) + ".jpg";
        File f = new File(dir, name);
        if (f.exists()) return f;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String url = "/photo/:/transcode?width=" + w + "&height=" + h + "&minSize=1&upscale=1&format=jpeg&url="
                + URLEncoder.encode(path, StandardCharsets.UTF_8.name());
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = open(url);
        c.setReadTimeout(10_000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(dir);
        return f;
    }

    /** At most 60 images on disk (the oldest go first). */
    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= 60) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        //noinspection ResultOfMethodCallIgnored
        for (int i = 0; i < files.length - 60; i++) files[i].delete();
    }

    // MARK: - Network

    private HttpURLConnection open(String path) throws IOException {
        String base = Config.PLEX_URL.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-Plex-Token", Config.PLEX_TOKEN.trim());
        c.setRequestProperty("X-Plex-Product", "CleanYourTV UI");
        return c;
    }

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        HttpURLConnection c = open(path);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        } finally {
            c.disconnect();
        }
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onPlex(); });
    }

    // MARK: - Opening a title

    /**
     * OK on a card: the title in the Plex app, which resumes it where it stopped (plex://server://… is the link the
     * Android TV app accepts for an item); without the server's id, or if the link fails, the Plex app itself.
     */
    boolean open(Activity a, Recs.Pick p) {
        String[] s = paths.get(Recs.key(p));
        if (machine != null && s != null && !s[2].isEmpty()) {
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("plex://server://" + machine
                        + "/com.plexapp.plugins.library" + s[2])).setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (RuntimeException ignored) {
            }
        }
        Intent launch = HomeApps.launchIntent(a.getPackageManager(), PACKAGE);
        if (launch == null) return false;
        a.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }
}
