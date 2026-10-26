package io.cleanyourtv.ui;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings changed on the TV itself (CleanYourTV UI > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    // MARK: - Language and units

    /** "auto", "pt", "en", "es", "de", "fr" or "ru". */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("ru") ? "ru" : v.startsWith("pt") ? "pt" : v.startsWith("es") ? "es" : v.startsWith("de") ? "de" : v.startsWith("fr") ? "fr"
                : v.startsWith("en") ? "en" : "auto";
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** Appearance: "auto" (dark from sunset to sunrise), "light" or "dark" (the Glass theme is always dark). */
    static String theme() {
        String v = sp().getString("theme", "auto");
        if (v.equals("glass")) return "dark";      // Glass was a value of this setting before it became a theme
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    /**
     * The home screen's theme: "classic", "glass" (dark aurora, glass tiles with line icons) or "editorial" (cream or
     * black, serif titles, arched posters, app pills) or "colors" (dark, a wide picture, colored app tiles, quick
     * settings in a side panel).
     */
    static String style() {
        String v = sp().getString("style", null);
        if (v == null) return "glass".equals(sp().getString("theme", "")) ? "glass" : "classic";
        return v.equals("glass") || v.equals("editorial") || v.equals("colors") || v.equals("minimal") || v.equals("hud") ? v : "classic";
    }

    static void setStyle(String v) { put("style", v); }

    static boolean glass() { return style().equals("glass"); }

    static boolean editorial() { return style().equals("editorial"); }

    static boolean colors() { return style().equals("colors"); }

    /** Black, a lime accent, a huge clock, the apps as plain names, menus as a side panel. */
    static boolean minimal() { return style().equals("minimal"); }

    /** Night blue with a faint grid, cyan, cut corners, a queue column, the sources as cards. */
    static boolean hud() { return style().equals("hud"); }

    /** Glass theme: the app tiles show line icons (true) or the apps' own logos and banners (false). */
    static boolean glassIcons() { return sp().getBoolean("glassIcons", true); }

    static void setGlassIcons(boolean on) { put("glassIcons", on); }

    static void setTheme(String v) { put("theme", v); }

    // MARK: - City

    static String city() { return sp().getString("city", Config.CITY).trim(); }
    static String latitude() { return sp().getString("latitude", Config.LATITUDE); }
    static String longitude() { return sp().getString("longitude", Config.LONGITUDE); }

    static void setCity(String name, String latitude, String longitude) {
        sp().edit().putString("city", name).putString("latitude", latitude).putString("longitude", longitude).apply();
        version++;
    }

    // MARK: - Widgets and coins

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    static boolean quotes() { return sp().getBoolean("quotes", Config.SHOW_CRYPTO); }
    static void setQuotes(boolean on) { put("quotes", on); }

    static boolean coin(int i) { return sp().getBoolean("coin" + i, true); }
    static void setCoin(int i, boolean on) { put("coin" + i, on); }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    // MARK: - Hidden apps

    /** Packages kept out of All Apps (config.json "hidden_apps"), on top of HomeApps' own list. */
    /** Order of the apps grid under the shelf (packages), set by Move; apps not in it follow, alphabetically. */
    static List<String> gridOrder() {
        List<String> out = new ArrayList<>();
        for (String p : sp().getString("gridOrder", "").split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setGridOrder(List<String> packages) { put("gridOrder", String.join(",", packages)); }

    static java.util.Set<String> hidden() {
        return new java.util.HashSet<>(java.util.Arrays.asList(sp().getString("hidden", "").split(",")));
    }

    static void setHidden(List<String> packages) { put("hidden", String.join(",", packages)); }

    // MARK: - Custom tiles

    /** "logo source|#RRGGBB" for a styled shelf tile, or "" (CustomTiles). */
    static String tile(String pkg) { return sp().getString("tile." + pkg, ""); }

    static void setTile(String pkg, String logo, String color) {
        boolean none = (logo == null || logo.trim().isEmpty()) && (color == null || color.trim().isEmpty());
        put("tile." + pkg, none ? "" : (logo == null ? "" : logo.trim()) + "|" + (color == null ? "" : color.trim()));
    }

    // MARK: - Recommendations

    /** What a TMDB watch provider opens: a package, "off", or "" (the built-in match). */
    static String provider(String id) { return sp().getString("provider." + id, ""); }
    static void setProvider(String id, String value) { put("provider." + id, value == null ? "" : value); }

    /** Changes whenever a service's app changes, so the picks are fetched again. */
    static int providersStamp() {
        int h = 0;
        for (java.util.Map.Entry<String, ?> e : sp().getAll().entrySet()) {
            if (e.getKey().startsWith("provider.")) h = h * 31 + (e.getKey() + "=" + e.getValue()).hashCode();
        }
        return h;
    }

    /** Picks from every provider in the region, not only the ones with an app on the TV. */
    static boolean showAllProviders() { return sp().getBoolean("showAllProviders", false); }
    static void setShowAllProviders(boolean on) { put("showAllProviders", on); }

    // MARK: - Remote buttons

    // The key service runs in its own small process (:keys), so that a busy home screen can never delay the TV's keys.
    // SharedPreferences are not shared between processes, so the button choices live in a tiny file both read.
    // A line is "<button>=<action>": the button is "s<scancode>" or "k<keycode>"; RemoteKeys lists the actions.
    private static File keysFile() {
        return new File(app.getFilesDir(), "remote-keys.txt");
    }

    private static final java.util.Map<String, String> buttons = new java.util.HashMap<>();
    private static long keysStamp = Long.MIN_VALUE;

    private static boolean writeKeys(String text) {
        File tmp = new File(app.getFilesDir(), "remote-keys." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return tmp.renameTo(keysFile());
    }

    /** Every choice made for a button, re-read only when the file changed. */
    static java.util.Map<String, String> buttons() {
        synchronized (buttons) {
            File f = keysFile();
            long stamp = f.lastModified() * 31 + f.length();
            if (stamp != keysStamp) {
                keysStamp = stamp;
                buttons.clear();
                if (f.isFile()) {
                    try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            int eq = line.indexOf('=');
                            if (eq > 1) buttons.put(line.substring(0, eq), line.substring(eq + 1));
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
            return new java.util.HashMap<>(buttons);
        }
    }

    /** The action picked for a button, or null when the person never picked one (remote.json's default applies). */
    static String button(String id) { return buttons().get(id); }

    static void setButton(String id, String action) {
        java.util.Map<String, String> all = buttons();
        if (action == null) all.remove(id); else all.put(id, action);
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : all.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        if (writeKeys(sb.toString())) version++;
    }
}
