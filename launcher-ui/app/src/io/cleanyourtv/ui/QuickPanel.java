package io.cleanyourtv.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.List;

/**
 * The Colors theme's quick settings: a dark panel along the right edge with three switches at the top (Wi-Fi,
 * Ambilight, standby; an active one in orange), the sources as rows with a colored square icon, and "All settings" at
 * the bottom. Back closes it.
 */
final class QuickPanel {
    private QuickPanel() {}

    /** A switch at the top: OK runs `action` and closes the panel. */
    static final class Tile {
        final String icon, title, sub;
        final boolean on;
        final Runnable action;

        Tile(String icon, String title, String sub, boolean on, Runnable action) {
            this.icon = icon;
            this.title = title;
            this.sub = sub;
            this.on = on;
            this.action = action;
        }
    }

    private static final int TEXT = 0xFFFFFFFF, DIM = 0xB3FFFFFF, DARK = 0xFF1C1C1E, DARK_DIM = 0x991C1C1E;

    static PopupWindow show(Activity a, View parent, String corner, List<Tile> tiles, List<GlassMenu.Entry> sources,
            TvUi.OnPick pickSource, Runnable allSettings) {
        float k = TvUi.k(a);
        int screenW = a.getResources().getDisplayMetrics().widthPixels, screenH = a.getResources().getDisplayMetrics().heightPixels;
        int w = Math.round(650 * k), hgt = screenH - Math.round(52 * k);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(30 * k);
        box.setPadding(pad, Math.round(40 * k), pad, Math.round(26 * k));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xFF2A2420, 0xFF161819});
        bg.setCornerRadius(42 * k);
        bg.setStroke(Math.max(1, Math.round(1.5f * k)), 0x26FFFFFF);
        box.setBackground(bg);
        box.setElevation(40 * k);
        PopupWindow pop = new PopupWindow(box, w, hgt, true);
        pop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        pop.setElevation(40 * k);

        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Math.round(10 * k), 0, Math.round(10 * k), Math.round(30 * k));
        TextView title = TvUi.text(a, k, 38, Ui.displayBold, TEXT);
        title.setText(Ui.tr("Ajustes rápidos", "Quick settings"));
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView time = TvUi.text(a, k, 26, Ui.medium, DIM);
        time.setText(corner);
        head.addView(time);
        box.addView(head);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            View v = tile(a, k, t);
            v.setOnClickListener(x -> {
                pop.dismiss();
                t.action.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Math.round(138 * k), 1);
            if (i > 0) lp.leftMargin = Math.round(16 * k);
            row.addView(v, lp);
        }
        box.addView(row);

        TextView label = TvUi.text(a, k, 23, Ui.semibold, DIM);
        label.setText(Ui.tr("Fontes", "Sources"));
        label.setPadding(Math.round(10 * k), Math.round(36 * k), 0, Math.round(14 * k));
        box.addView(label);
        View first = null;
        for (int i = 0; i < sources.size(); i++) {
            final int which = i;
            GlassMenu.Entry e = sources.get(i);
            View r = sourceRow(a, k, e);
            r.setOnClickListener(x -> {
                pop.dismiss();
                pickSource.pick(which);
            });
            box.addView(r);
            if (first == null) first = r;
        }
        View spacer = new View(a);
        box.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        View line = new View(a);
        line.setBackgroundColor(0x26FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(1.5f * k)));
        lp.bottomMargin = Math.round(14 * k);
        box.addView(line, lp);
        GlassMenu.Entry all = new GlassMenu.Entry("sliders", Ui.tr("Todos os ajustes", "All settings"), null);
        View allRow = sourceRow(a, k, all);
        allRow.setOnClickListener(x -> {
            pop.dismiss();
            allSettings.run();
        });
        box.addView(allRow);

        pop.setAnimationStyle(android.R.style.Animation_Dialog);
        pop.showAtLocation(parent, Gravity.NO_GRAVITY, screenW - w - Math.round(26 * k), Math.round(26 * k));
        View focus = first != null ? first : allRow;
        focus.post(focus::requestFocus);
        return pop;
    }

    /** A switch: icon, name and state; orange when active; white when focused. */
    private static View tile(Activity a, float k, Tile t) {
        LinearLayout v = new LinearLayout(a);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(Math.round(22 * k), Math.round(20 * k), Math.round(16 * k), Math.round(16 * k));
        v.setFocusable(true);
        Badge icon = new Badge(a, k, t.icon, 0, 0);
        v.addView(icon, new LinearLayout.LayoutParams(Math.round(40 * k), Math.round(40 * k)));
        TextView name = TvUi.text(a, k, 23, Ui.semibold, TEXT);
        name.setText(t.title);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, Math.round(10 * k), 0, 0);
        v.addView(name);
        TextView sub = TvUi.text(a, k, 21, Ui.medium, DIM);
        sub.setText(t.sub);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        v.addView(sub);
        GradientDrawable rest = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                t.on ? new int[] {0xFFFFA93C, 0xFFFF7A2E} : new int[] {0x1FFFFFFF, 0x14FFFFFF});
        rest.setCornerRadius(28 * k);
        if (!t.on) rest.setStroke(Math.max(1, Math.round(1.5f * k)), 0x1FFFFFFF);
        v.setBackground(rest);
        v.setOnFocusChangeListener((x, has) -> {
            v.setBackground(has ? TvUi.round(0xFFFFFFFF, 28 * k) : rest);
            name.setTextColor(has ? DARK : TEXT);
            sub.setTextColor(has ? DARK_DIM : DIM);
            icon.setInk(has ? DARK : TEXT);
        });
        return v;
    }

    /** A source (or "All settings"): a colored square with the icon, the name, the port and state; white when focused. */
    private static View sourceRow(Activity a, float k, GlassMenu.Entry e) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Math.round(20 * k), Math.round(16 * k), Math.round(26 * k), Math.round(16 * k));
        r.setFocusable(true);
        int[] pair = "broadcast".equals(e.icon) ? new int[] {0xFF4A6BE6, 0xFF1E2E9A} : "gamepad".equals(e.icon) ? new int[] {0xFF6A78F2, 0xFF3237B0}
                : "speaker".equals(e.icon) ? new int[] {0xFF2FB0A5, 0xFF0D6B66} : new int[] {0xFF4A4F57, 0xFF2C3036};
        Badge badge = new Badge(a, k, e.icon, pair[0], pair[1]);
        int bs = Math.round(70 * k);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(bs, bs);
        bp.rightMargin = Math.round(22 * k);
        r.addView(badge, bp);
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = TvUi.text(a, k, 28, Ui.semibold, TEXT);
        name.setText(e.title);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        TextView sub = null;
        String line = e.sub;
        if (e.state != null) line = (line == null ? "" : line + " · ") + e.state;
        if (line != null) {
            sub = TvUi.text(a, k, 22, Ui.medium, DIM);
            sub.setText(e.stateDot == GlassMenu.DOT_ON && e.sub != null ? withDot(e.sub, e.state) : line);
            sub.setSingleLine(true);
            texts.addView(sub);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView chevron = TvUi.text(a, k, 30, Ui.medium, DARK);
        chevron.setText("›");
        chevron.setVisibility(View.INVISIBLE);
        r.addView(chevron);
        final TextView s = sub;
        r.setOnFocusChangeListener((x, has) -> {
            r.setBackground(has ? TvUi.round(0xFFFFFFFF, 28 * k) : null);
            name.setTextColor(has ? DARK : TEXT);
            if (s != null) s.setTextColor(has ? DARK_DIM : DIM);
            chevron.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
        });
        return r;
    }

    /** "HDMI 2 · ● Connected", the dot in green. */
    private static CharSequence withDot(String sub, String state) {
        android.text.SpannableString t = new android.text.SpannableString(sub + " · ● " + state);
        int at = sub.length() + 3;
        t.setSpan(new android.text.style.ForegroundColorSpan(0xFF34C759), at, at + 1, 0);
        return t;
    }

    /** A rounded square in two colors (or none) with a line icon. */
    private static final class Badge extends View {
        private final float k;
        private final String icon;
        private final int from, to;
        private int ink = TEXT;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        Badge(Context c, float k, String icon, int from, int to) {
            super(c);
            this.k = k;
            this.icon = icon;
            this.from = from;
            this.to = to;
        }

        void setInk(int color) {
            ink = color;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float s = getWidth();
            if (from != 0) {
                paint.setShader(new LinearGradient(0, 0, s, s, from, to, Shader.TileMode.CLAMP));
                rect.set(0, 0, s, s);
                c.drawRoundRect(rect, s * 0.26f, s * 0.26f, paint);
                Icons.draw(c, icon, s / 2, s / 2, s * 0.52f, 0xFFFFFFFF);
            } else {
                Icons.draw(c, icon, s / 2, s / 2, s * 0.9f, ink);
            }
        }
    }
}
