package io.cleanyourtv.ui;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Fonts and text for the home screen, the screensaver and the settings. Font: Inter (variable, assets/fonts, SIL OFL),
 * with the "display" optical size for large text and the "text" size for small text; without it, the system font at a
 * similar weight.
 */
final class Ui {
    private Ui() {}

    /** Android 9 to 11: older TVs and boxes, whose GPUs need the cheaper drawing paths (Shadows, rounded images). */
    static final boolean LEGACY = Build.VERSION.SDK_INT < 31;

    /**
     * Whether the home screen keeps its layers (hero, buttons, shelf, drawer) as GPU textures, so a focus move redraws
     * only the layer that changed. Off by default on Android 9 to 11 (LEGACY), measured on a Mi Box 3's Mali-450.
     * "adb shell setprop debug.cytv.layers hw" (or "none") overrides it for the next start of the home screen, to
     * measure both on a given TV.
     */
    static boolean cachedLayers(android.content.Context c) {
        String p = systemProperty("debug.cytv.layers");
        if ("hw".equals(p)) return true;
        if ("none".equals(p)) return false;
        return !LEGACY;
    }

    /** android.os.SystemProperties.get (hidden API, read-only): "" when absent or not readable. */
    static String systemProperty(String key) {
        try {
            Object v = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, key, "");
            return v == null ? "" : v.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }
    static final Locale RU = new Locale("ru", "RU");
    static final Locale PT = new Locale("pt", "BR");
    /** English, Portuguese, Spanish, German or Russian, from the settings or, with "auto", the TV's own language. */
    static volatile String language = language();
    static volatile boolean en = "en".equals(language), es = "es".equals(language), de = "de".equals(language), fr = "fr".equals(language),
            ru = "ru".equals(language);
    static Typeface thin, light, displayMedium, displaySemibold, displayBold, regular, medium, semibold, bold;
    /** Instrument Serif (assets/fonts, SIL OFL), upright and italic: the Editorial theme's titles. */
    static Typeface serif, serifItalic;
    /** JetBrains Mono (assets/fonts, SIL OFL), regular and medium: the Minimal theme's small print. */
    static Typeface mono, monoMedium;
    /** Michroma (assets/fonts, SIL OFL), wide: the HUD theme's clock and titles. */
    static Typeface wide;
    private static boolean loaded;
    private static final Rect BOUNDS = new Rect();
    private static final RectF OVAL = new RectF();

    static synchronized void init(Context c) {
        if (loaded) return;
        loaded = true;
        AssetManager a = c.getAssets();
        thin = font(a, 32, 200);
        light = font(a, 32, 300);
        displayMedium = font(a, 32, 500);
        displaySemibold = font(a, 32, 600);
        regular = font(a, 14, 400);
        medium = font(a, 14, 500);
        semibold = font(a, 14, 600);
        bold = font(a, 14, 700);
        displayBold = font(a, 32, 700);
        serif = asset(a, "fonts/InstrumentSerif-Regular.ttf", Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        serifItalic = asset(a, "fonts/InstrumentSerif-Italic.ttf", Typeface.create(Typeface.SERIF, Typeface.ITALIC));
        mono = variable(a, "fonts/JetBrainsMono.ttf", 400, Typeface.MONOSPACE);
        monoMedium = variable(a, "fonts/JetBrainsMono.ttf", 500, Typeface.MONOSPACE);
        wide = asset(a, "fonts/Michroma-Regular.ttf", Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
    }

    private static Typeface variable(AssetManager a, String path, int weight, Typeface fallback) {
        if (!has(a, path)) return fallback;
        try {
            Typeface t = new Typeface.Builder(a, path).setFontVariationSettings("'wght' " + weight).build();
            return t != null ? t : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static Typeface asset(AssetManager a, String path, Typeface fallback) {
        if (!has(a, path)) return fallback;
        try {
            return Typeface.createFromAsset(a, path);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Inter at an optical size (14 = text, 32 = display) and weight; the system font if the file is missing. */
    private static Typeface font(AssetManager a, int opticalSize, int weight) {
        if (has(a, FONT)) {
            try {
                Typeface t = new Typeface.Builder(a, FONT)
                        .setFontVariationSettings("'opsz' " + opticalSize + ", 'wght' " + weight).build();
                if (t != null) return t;
            } catch (RuntimeException ignored) {
            }
        }
        return Typeface.create(Typeface.DEFAULT, weight, false);
    }

    private static final String FONT = "fonts/InterVariable.ttf";

    /** "pt", "es", "de", "ru" or "en": the one picked (TV settings or config.properties), or with "auto" the TV's own, else English. */
    private static String language() {
        String lang = Prefs.language();
        if (!lang.equals("auto")) return lang;
        lang = Locale.getDefault().getLanguage();
        return "pt".equals(lang) || "es".equals(lang) || "de".equals(lang) || "fr".equals(lang) || "ru".equals(lang) ? lang : "en";
    }

    /** After the language changes in the settings (and once Prefs can read them). */
    static void applyLanguage() {
        language = language();
        en = "en".equals(language);
        es = "es".equals(language);
        de = "de".equals(language);
        fr = "fr".equals(language);
        ru = "ru".equals(language);
    }

    private static boolean has(AssetManager a, String path) {
        try (InputStream in = a.open(path)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    static Paint text(Typeface face, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    /** Fixed-width digits (the clock and the prices don't jitter when a digit changes). */
    static Paint numbers(Typeface face, float size, int color) {
        Paint p = text(face, size, color);
        p.setFontFeatureSettings("'tnum'");
        return p;
    }

    /** Height of capital letters (and digits) above the baseline. */
    static float cap(Paint p) {
        synchronized (BOUNDS) {
            p.getTextBounds("H8", 0, 2, BOUNDS);
            return -BOUNDS.top;
        }
    }

    /** Half the cap height: added to the center line, it gives the baseline that centers the text. */
    static float halfCap(Paint p) {
        return cap(p) / 2f;
    }

    /** Left bearing of the first character (large, thin fonts start a little inward). */
    static float leftBearing(Paint p, String s) {
        if (s.isEmpty()) return 0;
        synchronized (BOUNDS) {
            p.getTextBounds(s, 0, 1, BOUNDS);
            return BOUNDS.left;
        }
    }

    static int alpha(int color, float a) {
        int base = (color >>> 24) & 255;
        return (Math.round(base * Math.max(0, Math.min(1, a))) << 24) | (color & 0xFFFFFF);
    }

    static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    static String tr(String pt, String english) {
        return fr ? French.text(english) : de ? German.text(english) : ru ? Russian.text(english) : es ? Spanish.text(english) : en ? english : pt;
    }

    /** German comes from German.java here too (keyed by the English, with patterns for text built at runtime). */
    static String tr(String pt, String english, String spanish) {
        return fr ? French.text(english) : de ? German.text(english) : ru ? Russian.text(english) : es ? spanish : en ? english : pt;
    }

    static Locale locale() {
        return fr ? French.LOCALE : ru ? RU : es ? spanish() : de ? german() : en ? Locale.US : PT;
    }

    /** The TV's own Spanish (es-MX, es-AR…) when it is in Spanish, Spain's otherwise. */
    private static Locale spanish() {
        Locale tv = Locale.getDefault();
        return "es".equals(tv.getLanguage()) ? tv : new Locale("es", "ES");
    }

    /** The TV's own German (de-AT, de-CH…) when it is in German, Germany's otherwise. */
    private static Locale german() {
        Locale tv = Locale.getDefault();
        return "de".equals(tv.getLanguage()) ? tv : Locale.GERMANY;
    }

    /** "21:47" (in English, "9:47", like the iPhone in the US). */
    static String clock(long now) {
        return en ? new SimpleDateFormat("h:mm", Locale.US).format(new Date(now))
                : new SimpleDateFormat("HH:mm", locale()).format(new Date(now));
    }

    /** "5:21" (sunrise and sunset; in English, "5:21 AM"). */
    static String time(long at) {
        return once("time", at, () -> en ? new SimpleDateFormat("h:mm a", Locale.US).format(new Date(at))
                : new SimpleDateFormat("H:mm", locale()).format(new Date(at)));
    }

    private static final HashMap<String, String> FORMATTED = new HashMap<>();

    /**
     * A formatted value, made once per language: the bottom drawer is redrawn on every frame of its focus animation,
     * and SimpleDateFormat and String.format there cost ~10 ms a frame on a Mi Box 3.
     */
    static String once(String kind, double value, Supplier<String> format) {
        String key = kind + '|' + language + '|' + value;
        synchronized (FORMATTED) {
            String s = FORMATTED.get(key);
            if (s == null) {
                if (FORMATTED.size() > 200) FORMATTED.clear();
                s = format.get();
                FORMATTED.put(key, s);
            }
            return s;
        }
    }

    /** "domingo, 18 de outubro" (lowercase, like the iPhone in Portuguese); "Sunday, October 18" in English; "Sonntag, 18. Oktober". */
    static String date(long now) {
        return fr ? new SimpleDateFormat("EEEE d MMMM", locale()).format(new Date(now))
                : ru ? new SimpleDateFormat("EEEE, d MMMM", locale()).format(new Date(now))
                : en ? new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(new Date(now))
                : new SimpleDateFormat(de ? "EEEE, d. MMMM" : "EEEE, d 'de' MMMM", locale()).format(new Date(now));
    }

    /** Spinning arc (work in progress). */
    static void spinner(Canvas c, float cx, float cy, float r, float seconds, Paint stroke) {
        OVAL.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(OVAL, (seconds * 360f) % 360f, 270f, false, stroke);
    }
}
