package io.cleanyourtv.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.Locale;

/**
 * Line icons drawn in code for the "glass" theme (the app tiles show these instead of the apps' logos): one stroke
 * weight, round ends, in a `size` × `size` box centered on (cx, cy). Main thread only (shared paint and path).
 */
final class Icons {
    private Icons() {}

    private static final Paint LINE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint DOT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path PATH = new Path();
    private static final RectF R = new RectF();

    static {
        LINE.setStyle(Paint.Style.STROKE);
        LINE.setStrokeCap(Paint.Cap.ROUND);
        LINE.setStrokeJoin(Paint.Join.ROUND);
    }

    /** The icon for an app, from its package and name ("grid" when nothing fits). */
    static String forApp(String pkg, String label) {
        String s = ((pkg == null ? "" : pkg) + " " + (label == null ? "" : label)).toLowerCase(Locale.ROOT);
        if (s.contains("plex")) return "stack";
        if (s.contains("youtube")) return "play";
        if (s.contains("orange") || s.contains("molotov") || s.contains("canal") || s.contains("livetv")) return "tv";
        if (s.contains("tf1") || s.contains("m6") || s.contains("france.tv") || s.contains("francetv") || s.contains("arte")
                || s.contains("radio")) return "broadcast";
        if (s.contains("tivimate") || s.contains("iptv") || s.contains("guide")) return "list";
        if (s.contains("vending") || s.contains("play store") || s.contains("store")) return "bag";
        if (s.contains("vpn") || s.contains("surfshark") || s.contains("nord")) return "shield";
        if (s.contains("netflix") || s.contains("prime") || s.contains("disney") || s.contains("video") || s.contains("cinema")) return "film";
        if (s.contains("spotify") || s.contains("music") || s.contains("deezer")) return "music";
        if (s.contains("file") || s.contains("media") || s.contains("multim") || s.contains("explorer")) return "folder";
        if (s.contains("chrome") || s.contains("browser") || s.contains("firefox")) return "globe";
        if (s.contains("setting") || s.contains("réglage") || s.contains("param")) return "sliders";
        if (s.contains("game") || s.contains("playstation")) return "gamepad";
        return "grid";
    }

    /** The icon for a symbol name used elsewhere in the app (Symbols), so the glass theme stays in one style. */
    static String forSymbol(String symbol) {
        if (symbol == null) return "grid";
        switch (symbol) {
            case "gamepad": return "gamepad";
            case "speaker": return "speaker";
            case "live_tv": return "broadcast";
            case "tv": return "tv";
            case "settings": return "home";
            case "tv_settings": return "monitor";
            case "sparkles": return "sparkles";
            case "apps": return "grid";
            case "search": return "search";
            default: return "plug";
        }
    }

    static void draw(Canvas c, String name, float cx, float cy, float size, int color) {
        float u = size / 24f;      // drawn on a 24-unit grid, like the usual icon sets
        LINE.setColor(color);
        DOT.setColor(color);
        c.save();
        c.translate(cx - 12 * u, cy - 12 * u);
        c.scale(u, u);
        LINE.setStrokeWidth(1.9f);
        switch (name) {
            case "tv":
                rect(c, 3, 5, 21, 17, 2.5f);
                line(c, 9, 20.5f, 15, 20.5f);
                line(c, 9, 2, 12, 5);
                line(c, 15, 2, 12, 5);
                break;
            case "stack":
                rect(c, 4, 9, 20, 21, 2.5f);
                line(c, 6, 6, 18, 6);
                line(c, 8, 3, 16, 3);
                PATH.reset();
                PATH.moveTo(10.5f, 12.5f);
                PATH.lineTo(14.5f, 15);
                PATH.lineTo(10.5f, 17.5f);
                PATH.close();
                c.drawPath(PATH, LINE);
                break;
            case "play":
                c.drawCircle(12, 12, 9, LINE);
                PATH.reset();
                PATH.moveTo(10, 8.5f);
                PATH.lineTo(15.5f, 12);
                PATH.lineTo(10, 15.5f);
                PATH.close();
                c.drawPath(PATH, LINE);
                break;
            case "broadcast":
                c.drawCircle(12, 12, 2, LINE);
                arc(c, 12, 12, 5.5f, -45, 90);
                arc(c, 12, 12, 5.5f, 135, 90);
                arc(c, 12, 12, 9, -45, 90);
                arc(c, 12, 12, 9, 135, 90);
                break;
            case "list":
                rect(c, 3.5f, 4, 20.5f, 20, 2.5f);
                line(c, 7.5f, 9, 9, 9);
                line(c, 11.5f, 9, 16.5f, 9);
                line(c, 7.5f, 12.5f, 9, 12.5f);
                line(c, 11.5f, 12.5f, 16.5f, 12.5f);
                line(c, 7.5f, 16, 9, 16);
                line(c, 11.5f, 16, 16.5f, 16);
                break;
            case "gamepad":
                PATH.reset();
                PATH.moveTo(7, 7);
                PATH.lineTo(17, 7);
                PATH.cubicTo(20.5f, 7, 22, 13, 21.5f, 16);
                PATH.cubicTo(21, 19, 18.5f, 19.5f, 16.5f, 17);
                PATH.lineTo(15, 15);
                PATH.lineTo(9, 15);
                PATH.lineTo(7.5f, 17);
                PATH.cubicTo(5.5f, 19.5f, 3, 19, 2.5f, 16);
                PATH.cubicTo(2, 13, 3.5f, 7, 7, 7);
                PATH.close();
                c.drawPath(PATH, LINE);
                line(c, 6.5f, 11, 9.5f, 11);
                line(c, 8, 9.5f, 8, 12.5f);
                dot(c, 15.5f, 10.5f, 1.1f);
                dot(c, 17.5f, 12.5f, 1.1f);
                break;
            case "plug":
                line(c, 12, 16, 12, 21);
                PATH.reset();
                PATH.moveTo(7, 8);
                PATH.lineTo(17, 8);
                PATH.lineTo(17, 12);
                PATH.cubicTo(17, 14.5f, 15, 16, 12, 16);
                PATH.cubicTo(9, 16, 7, 14.5f, 7, 12);
                PATH.close();
                c.drawPath(PATH, LINE);
                line(c, 9.5f, 3, 9.5f, 8);
                line(c, 14.5f, 3, 14.5f, 8);
                break;
            case "folder":
                PATH.reset();
                PATH.moveTo(3, 7);
                PATH.cubicTo(3, 5.5f, 4, 4.5f, 5.5f, 4.5f);
                PATH.lineTo(9.5f, 4.5f);
                PATH.lineTo(11.5f, 7);
                PATH.lineTo(18.5f, 7);
                PATH.cubicTo(20, 7, 21, 8, 21, 9.5f);
                PATH.lineTo(21, 17);
                PATH.cubicTo(21, 18.5f, 20, 19.5f, 18.5f, 19.5f);
                PATH.lineTo(5.5f, 19.5f);
                PATH.cubicTo(4, 19.5f, 3, 18.5f, 3, 17);
                PATH.close();
                c.drawPath(PATH, LINE);
                break;
            case "bag":
                PATH.reset();
                PATH.moveTo(5, 8);
                PATH.lineTo(19, 8);
                PATH.lineTo(18, 20);
                PATH.lineTo(6, 20);
                PATH.close();
                c.drawPath(PATH, LINE);
                arc(c, 12, 8, 3.5f, 180, 180);
                PATH.reset();
                PATH.moveTo(10.5f, 11.5f);
                PATH.lineTo(14, 14);
                PATH.lineTo(10.5f, 16.5f);
                PATH.close();
                c.drawPath(PATH, LINE);
                break;
            case "shield":
                PATH.reset();
                PATH.moveTo(12, 2.5f);
                PATH.lineTo(19.5f, 5.5f);
                PATH.lineTo(19.5f, 11);
                PATH.cubicTo(19.5f, 16, 16.5f, 19.5f, 12, 21.5f);
                PATH.cubicTo(7.5f, 19.5f, 4.5f, 16, 4.5f, 11);
                PATH.lineTo(4.5f, 5.5f);
                PATH.close();
                c.drawPath(PATH, LINE);
                PATH.reset();
                PATH.moveTo(8.5f, 12);
                PATH.lineTo(11, 14.5f);
                PATH.lineTo(15.5f, 9.5f);
                c.drawPath(PATH, LINE);
                break;
            case "sparkles":
                star(c, 10, 13, 6.5f);
                star(c, 18, 6, 3);
                star(c, 18.5f, 18, 2.5f);
                break;
            case "plus":
                line(c, 12, 5, 12, 19);
                line(c, 5, 12, 19, 12);
                break;
            case "wifi":
                arc(c, 12, 19, 15, 235, 70);
                arc(c, 12, 19, 10, 235, 70);
                arc(c, 12, 19, 5, 235, 70);
                dot(c, 12, 19, 1.4f);
                break;
            case "minus":
                line(c, 5, 12, 19, 12);
                break;
            case "power":
                arc(c, 12, 13, 8, -55, 290);
                line(c, 12, 3, 12, 11);
                break;
            case "move":    // four arrows
                line(c, 12, 3, 12, 21);
                line(c, 3, 12, 21, 12);
                PATH.reset();
                PATH.moveTo(9.5f, 5.5f);
                PATH.lineTo(12, 3);
                PATH.lineTo(14.5f, 5.5f);
                PATH.moveTo(9.5f, 18.5f);
                PATH.lineTo(12, 21);
                PATH.lineTo(14.5f, 18.5f);
                PATH.moveTo(5.5f, 9.5f);
                PATH.lineTo(3, 12);
                PATH.lineTo(5.5f, 14.5f);
                PATH.moveTo(18.5f, 9.5f);
                PATH.lineTo(21, 12);
                PATH.lineTo(18.5f, 14.5f);
                c.drawPath(PATH, LINE);
                break;
            case "home":
                PATH.reset();
                PATH.moveTo(3.5f, 11);
                PATH.lineTo(12, 4);
                PATH.lineTo(20.5f, 11);
                c.drawPath(PATH, LINE);
                PATH.reset();
                PATH.moveTo(6, 9.5f);
                PATH.lineTo(6, 20);
                PATH.lineTo(18, 20);
                PATH.lineTo(18, 9.5f);
                c.drawPath(PATH, LINE);
                rect(c, 10, 14, 14, 20, 1);
                break;
            case "sliders":
                line(c, 4, 7, 12.4f, 7);
                line(c, 17.6f, 7, 20, 7);
                line(c, 4, 17, 6.4f, 17);
                line(c, 11.6f, 17, 20, 17);
                fillRing(c, 15, 7, 2.6f);
                fillRing(c, 9, 17, 2.6f);
                break;
            case "monitor":
                rect(c, 2.5f, 4, 21.5f, 16.5f, 2.5f);
                line(c, 12, 16.5f, 12, 20);
                line(c, 8, 20, 16, 20);
                PATH.reset();
                PATH.moveTo(6, 12);
                PATH.lineTo(9, 9);
                PATH.lineTo(12, 11.5f);
                PATH.lineTo(15, 8);
                PATH.lineTo(18, 10.5f);
                c.drawPath(PATH, LINE);
                break;
            case "speaker":
                rect(c, 6, 2.5f, 18, 21.5f, 3);
                c.drawCircle(12, 14.5f, 3.2f, LINE);
                dot(c, 12, 7, 1.2f);
                break;
            case "film":
                rect(c, 3, 4, 21, 20, 2.5f);
                line(c, 7.5f, 4, 7.5f, 20);
                line(c, 16.5f, 4, 16.5f, 20);
                line(c, 3, 12, 7.5f, 12);
                line(c, 16.5f, 12, 21, 12);
                break;
            case "music":
                line(c, 9, 18, 9, 5);
                line(c, 9, 5, 19, 3);
                line(c, 19, 3, 19, 16);
                c.drawCircle(6.5f, 18, 2.5f, LINE);
                c.drawCircle(16.5f, 16, 2.5f, LINE);
                break;
            case "globe":
                c.drawCircle(12, 12, 9, LINE);
                line(c, 3, 12, 21, 12);
                R.set(8, 3, 16, 21);
                c.drawOval(R, LINE);
                break;
            case "search":
                c.drawCircle(10.5f, 10.5f, 6.5f, LINE);
                line(c, 15.5f, 15.5f, 20.5f, 20.5f);
                break;
            case "sync":
                arc(c, 12, 12, 8, 200, 130);
                arc(c, 12, 12, 8, 20, 130);
                PATH.reset();
                PATH.moveTo(17, 3.5f);
                PATH.lineTo(17.2f, 7.4f);
                PATH.lineTo(13.4f, 7.6f);
                c.drawPath(PATH, LINE);
                PATH.reset();
                PATH.moveTo(7, 20.5f);
                PATH.lineTo(6.8f, 16.6f);
                PATH.lineTo(10.6f, 16.4f);
                c.drawPath(PATH, LINE);
                break;
            default:    // "grid": an app with no icon of its own
                rect(c, 4, 4, 10.5f, 10.5f, 2);
                rect(c, 13.5f, 4, 20, 10.5f, 2);
                rect(c, 4, 13.5f, 10.5f, 20, 2);
                rect(c, 13.5f, 13.5f, 20, 20, 2);
                break;
        }
        c.restore();
    }

    private static void rect(Canvas c, float l, float t, float r, float b, float radius) {
        R.set(l, t, r, b);
        c.drawRoundRect(R, radius, radius, LINE);
    }

    private static void line(Canvas c, float x0, float y0, float x1, float y1) {
        c.drawLine(x0, y0, x1, y1, LINE);
    }

    private static void arc(Canvas c, float cx, float cy, float r, float start, float sweep) {
        R.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(R, start, sweep, false, LINE);
    }

    private static void dot(Canvas c, float x, float y, float r) {
        c.drawCircle(x, y, r, DOT);
    }

    /** A slider's knob: a ring (the line stops at its edge). */
    private static void fillRing(Canvas c, float x, float y, float r) {
        c.drawCircle(x, y, r, LINE);
    }

    /** Four-pointed star (the sparkles). */
    private static void star(Canvas c, float x, float y, float r) {
        float q = r * 0.28f;
        PATH.reset();
        PATH.moveTo(x, y - r);
        PATH.quadTo(x + q, y - q, x + r, y);
        PATH.quadTo(x + q, y + q, x, y + r);
        PATH.quadTo(x - q, y + q, x - r, y);
        PATH.quadTo(x - q, y - q, x, y - r);
        PATH.close();
        c.drawPath(PATH, LINE);
    }
}
