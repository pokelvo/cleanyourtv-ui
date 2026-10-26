package io.cleanyourtv.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.tv.TvContract;
import android.os.Build;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The remote's buttons. remote.json (examples/<model>/remote.json, pushed by launcher-ui/config.sh --remote) lists them: a
 * "native" button is never touched, a "configurable" one runs the action the person picked in Settings > Remote
 * Buttons, or its default. Without remote.json, TCL and Vestel TVs keep the app buttons Free My TV handled.
 *
 * Actions: a package, "input:<tv input id>", "live", "settings", "home", "tmdb:explore", "tmdb:watchlist", or ""
 * (the button does what it did before).
 */
final class RemoteKeys {
    private RemoteKeys() {}

    static final boolean TCL = is("TCL"), VESTEL = is("Vestel");

    private static boolean is(String maker) {
        return maker.equalsIgnoreCase(Build.MANUFACTURER) || maker.equalsIgnoreCase(Build.BRAND);
    }

    static final class Button {
        final String id, name, dflt;
        Button(String id, String name, String dflt) {
            this.id = id;
            this.name = name;
            this.dflt = dflt;
        }
    }

    private static final Object LOCK = new Object();
    private static List<Button> cached;
    private static String cachedRemote = "";
    private static long cachedStamp = Long.MIN_VALUE;

    /** The configurable buttons, from remote.json (re-read when it changes) or the built-in TCL/Vestel ones. */
    static List<Button> buttons(Context c) {
        synchronized (LOCK) {
            File f = new File(TvConfig.dir(c), "remote.json");
            long stamp = f.lastModified() * 31 + f.length();
            if (cached != null && stamp == cachedStamp) return cached;
            cachedStamp = stamp;
            List<Button> out = new ArrayList<>();
            String text = TvConfig.read(f);
            cachedRemote = "";
            if (text != null) {
                try {
                    JSONObject o = new JSONObject(text);
                    cachedRemote = o.optString("remote");
                    JSONArray list = o.getJSONArray("buttons");
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject b = list.getJSONObject(i);
                        if (!"configurable".equals(b.optString("kind"))) continue;
                        String id = b.has("scan") ? "s" + b.getInt("scan") : b.has("key") ? "k" + b.getInt("key") : null;
                        if (id != null) out.add(new Button(id, b.optString("name", id), b.optString("default")));
                    }
                } catch (Exception e) {
                    AppLog.w("remote.json not read: " + e.getMessage());
                    out.clear();
                }
            }
            if (text == null && VESTEL) {
                out.add(new Button("k" + KeyEvent.KEYCODE_STEM_1, Ui.tr("Botão Netflix", "Netflix button"), "com.netflix.ninja"));
            } else if (text == null && TCL) {
                out.add(new Button("k4062", Ui.tr("Botão Netflix", "Netflix button"), "com.netflix.ninja"));
                out.add(new Button("k190", Ui.tr("Botão YouTube", "YouTube button"), "com.google.android.youtube.tv"));
                out.add(new Button("k4099", Ui.tr("Botão Prime Video", "Prime Video button"), "com.amazon.amazonvideo.livingroom"));
                out.add(new Button("k4020", Ui.tr("Botão TCL Channel", "TCL Channel button"), ""));
            }
            cached = out;
            return out;
        }
    }

    /** The remote's name from remote.json, or "" without one. */
    static String remoteName(Context c) {
        buttons(c);
        return cachedRemote;
    }

    static String action(Button b) {
        String picked = Prefs.button(b.id);
        return picked != null ? picked : b.dflt;
    }

    /** Philips remote scan codes (GlobalKey's log: "scan=641" Options, "scan=757" Sources). */
    static final int SCAN_OPTIONS = 641, SCAN_SOURCES = 757;
    /** The app in front (RemoteKeyService follows it). */
    static volatile String front = "";

    /**
     * Philips: the Sources and Options keys open this app's own menus (QuickMenuActivity) from any app. The system's
     * GlobalKey service takes both keys before any app sees them; an accessibility key filter runs earlier. In Philips'
     * own apps (TV, guide, media) Options stays theirs (subtitles, audio language…).
     */
    private static boolean quickMenu(Context c, KeyEvent e) {
        boolean sources = e.getScanCode() == SCAN_SOURCES || e.getKeyCode() == KeyEvent.KEYCODE_TV_INPUT;
        boolean options = e.getScanCode() == SCAN_OPTIONS;
        if (!sources && !options) return false;
        if (options && front.startsWith("org.droidtv.")) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            try {
                c.startActivity(new Intent().setClassName(c, QuickMenuActivity.class.getName())
                        .putExtra(QuickMenuActivity.EXTRA_MENU, sources ? QuickMenuActivity.SOURCES : QuickMenuActivity.OPTIONS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ex) {
                return false;
            }
        }
        return true;
    }

    /** Runs the button's action on its first press. True if the key is one of ours and in use (then consumed). */
    static boolean onKey(Context c, KeyEvent e) {
        if (quickMenu(c, e)) return true;
        // BUTTON_3 is also a game controller's button: keys from a gamepad or a joystick are left alone.
        int source = e.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        int keyCode = e.getKeyCode() == 4065 ? KeyEvent.KEYCODE_BUTTON_3 : e.getKeyCode();   // TCL's own YouTube key
        String scan = "s" + e.getScanCode(), key = "k" + keyCode;
        Button found = null;
        for (Button b : buttons(c)) {
            if (b.id.equals(scan)) { found = b; break; }
            if (b.id.equals(key) && found == null) found = b;
        }
        if (found == null) return false;
        String action = action(found);
        if (action.isEmpty()) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) run(c, action);
        return true;
    }

    static void run(Context c, String action) {
        Intent intent = intent(c, action);
        AppLog.w("remote button -> " + action + (intent == null ? " (not available)" : ""));
        if (intent == null) return;
        try {
            c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    static Intent intent(Context c, String action) {
        switch (action) {
            case "home":
                return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(c.getPackageName());
            case "settings":
                return new Intent(Settings.ACTION_SETTINGS);
            case "live":
                return HomeApps.liveTv(c);
            case "tmdb:explore":    // a fresh Explore: whatever was open in it before is dropped
                return new Intent().setClassName(c, ExploreActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            case "tmdb:watchlist":
                return new Intent().setClassName(c, ExploreActivity.class.getName()).putExtra(ExploreActivity.EXTRA_SECTION, "watchlist")
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            default:
                if (action.startsWith("input:")) {
                    return new Intent(Intent.ACTION_VIEW).setDataAndType(
                            TvContract.buildChannelUriForPassthroughInput(action.substring("input:".length())),
                            "vnd.android.cursor.item/channel");
                }
                return launchIntent(c.getPackageManager(), action);
        }
    }

    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }
}
