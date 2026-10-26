package io.cleanyourtv.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.List;

/**
 * The glass theme's small menus (Sources, Power, an app held with OK): a smoked-glass card under or over its button,
 * a spaced-out title with a count on the right, rows with a round line icon, a second line and a state chip, and the
 * keys at the bottom ("OK  Switch", "←  Back to close"). Back closes it.
 */
final class GlassMenu {
    private GlassMenu() {}

    static final class Entry {
        final String icon, title, sub;
        /** A chip at the right ("Connected"), with a dot of this color (0: none), or null. */
        String state;
        int stateDot;
        /** A check mark at the right (the current choice). */
        boolean checked;

        Entry(String icon, String title, String sub) {
            this.icon = icon;
            this.title = title;
            this.sub = sub;
        }

        Entry state(String text, int dot) {
            state = text;
            stateDot = dot;
            return this;
        }
    }

    /** Entry.stateDot for a device that is on, and for one asleep (the Editorial menu reads them as filled or a ring). */
    static final int DOT_ON = 0xFF8CD6F2, DOT_ASLEEP = 0x99FFFFFF;

    private static final int TEXT = 0xFFF5F6F8, DIM = 0xA6F5F6F8, FAINT = 0x73F5F6F8, DARK = 0xFF1C1C1E, DARK_DIM = 0x991C1C1E;

    static PopupWindow show(Activity a, View parent, Rect anchor, String title, String meta, List<Entry> entries,
            String okHint, TvUi.OnPick pick) {
        float k = TvUi.k(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * k);
        box.setPadding(pad, Math.round(26 * k), pad, Math.round(20 * k));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C272D);
        bg.setCornerRadius(40 * k);
        bg.setStroke(Math.max(1, Math.round(1.5f * k)), 0x33FFFFFF);
        box.setBackground(bg);
        box.setElevation(30 * k);
        box.setMinimumWidth(Math.round(640 * k));

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setPadding(Math.round(22 * k), 0, Math.round(22 * k), Math.round(18 * k));
        TextView h = TvUi.text(a, k, 22, Ui.semibold, 0xB3FFFFFF);
        h.setText(title.toUpperCase(Ui.locale()));
        h.setLetterSpacing(0.14f);
        head.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (meta != null) {
            TextView m = TvUi.text(a, k, 21, Ui.medium, 0x99FFFFFF);
            m.setText(meta);
            head.addView(m);
        }
        box.addView(head);

        PopupWindow pop = new PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        pop.setElevation(30 * k);
        View first = null;
        for (int i = 0; i < entries.size(); i++) {
            final int which = i;
            View row = row(a, k, entries.get(i), i == entries.size() - 1);
            row.setOnClickListener(v -> {
                pop.dismiss();
                pick.pick(which);
            });
            int last = entries.size() - 1;
            row.setOnKeyListener((v, code, e) -> code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || (code == KeyEvent.KEYCODE_DPAD_UP && which == 0) || (code == KeyEvent.KEYCODE_DPAD_DOWN && which == last));
            box.addView(row);
            if (first == null) first = row;
        }

        View line = new View(a);
        line.setBackgroundColor(0x26FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(1.5f * k)));
        lp.setMargins(0, Math.round(16 * k), 0, Math.round(18 * k));
        box.addView(line, lp);
        LinearLayout foot = new LinearLayout(a);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(Math.round(22 * k), 0, Math.round(22 * k), 0);
        foot.addView(key(a, k, "OK"));
        foot.addView(hint(a, k, okHint), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        foot.addView(key(a, k, "←"));
        foot.addView(hint(a, k, Ui.tr("Voltar para fechar", "Back to close")));
        box.addView(foot);

        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int w = box.getMeasuredWidth(), hgt = box.getMeasuredHeight();
        int screenW = a.getResources().getDisplayMetrics().widthPixels;
        // Its measured size, kept: left to wrap, the rows' weights stretched it to the screen's edge.
        pop.setWidth(w);
        pop.setHeight(hgt);
        int x = Math.max(Math.round(80 * k), Math.min(screenW - w - Math.round(80 * k), anchor.centerX() - w / 2));
        int y = anchor.bottom + Math.round(26 * k);
        int screenH = a.getResources().getDisplayMetrics().heightPixels;
        if (y + hgt > screenH - Math.round(24 * k)) y = Math.max(Math.round(24 * k), anchor.top - hgt - Math.round(26 * k));
        pop.setAnimationStyle(android.R.style.Animation_Dialog);
        pop.showAtLocation(parent, Gravity.NO_GRAVITY, x, y);
        View focus = first;
        if (focus != null) focus.post(focus::requestFocus);
        return pop;
    }

    /** One choice: round icon, title and second line, the state chip or check at the right; white when focused. */
    private static View row(Activity a, float k, Entry e, boolean last) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Math.round(18 * k), Math.round(16 * k), Math.round(24 * k), Math.round(16 * k));
        row.setFocusable(true);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!last) rp.bottomMargin = Math.round(6 * k);
        row.setLayoutParams(rp);

        Badge icon = new Badge(a, k, e.icon);
        int size = Math.round(64 * k);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(size, size);
        ip.rightMargin = Math.round(22 * k);
        row.addView(icon, ip);

        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = TvUi.text(a, k, 29, Ui.semibold, TEXT);
        title.setText(e.title);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        TextView sub = null;
        if (e.sub != null && !e.sub.isEmpty()) {
            sub = TvUi.text(a, k, 21, Ui.medium, DIM);
            sub.setText(e.sub);
            sub.setSingleLine(true);
            texts.addView(sub);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Chip chip = null;
        if (e.state != null) {
            chip = new Chip(a, k, e.state, e.stateDot);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.leftMargin = Math.round(24 * k);
            row.addView(chip, cp);
        } else if (e.checked) {
            TextView check = TvUi.text(a, k, 28, Ui.semibold, TEXT);
            check.setText("✓");
            check.setPadding(Math.round(24 * k), 0, 0, 0);
            row.addView(check);
            final TextView c = check;
            row.setTag(c);
        }
        final TextView s = sub;
        final Chip ch = chip;
        row.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? TvUi.round(0xFFF4F5F6, 28 * k) : null);
            title.setTextColor(has ? DARK : TEXT);
            if (s != null) s.setTextColor(has ? DARK_DIM : DIM);
            if (ch != null) ch.setFocused(has);
            if (v.getTag() instanceof TextView) ((TextView) v.getTag()).setTextColor(has ? DARK : TEXT);
            icon.setFocused(has);
        });
        return row;
    }

    private static TextView key(Context c, float k, String text) {
        TextView t = TvUi.text(c, k, 20, Ui.semibold, 0xD9FFFFFF);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Math.round(56 * k));
        t.setPadding(Math.round(12 * k), Math.round(6 * k), Math.round(12 * k), Math.round(6 * k));
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(10 * k);
        d.setStroke(Math.max(1, Math.round(1.5f * k)), 0x66FFFFFF);
        t.setBackground(d);
        return t;
    }

    private static TextView hint(Context c, float k, String text) {
        TextView t = TvUi.text(c, k, 22, Ui.medium, 0xB3FFFFFF);
        t.setText(text);
        t.setPadding(Math.round(14 * k), 0, Math.round(26 * k), 0);
        return t;
    }

    /** The round icon of a row: a ring on the glass, a light gray disc on the white focused row. */
    private static final class Badge extends View {
        private final float k;
        private final String icon;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean focused;

        Badge(Context c, float k, String icon) {
            super(c);
            this.k = k;
            this.icon = icon;
        }

        void setFocused(boolean f) {
            focused = f;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float r = getWidth() / 2f;
            paint.setStyle(focused ? Paint.Style.FILL : Paint.Style.FILL_AND_STROKE);
            paint.setColor(focused ? 0xFFE6E8EB : 0x1FFFFFFF);
            c.drawCircle(r, r, r - k, paint);
            if (!focused) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.5f * k);
                paint.setColor(0x33FFFFFF);
                c.drawCircle(r, r, r - k, paint);
            }
            Icons.draw(c, icon, r, r, r * 0.95f, focused ? DARK : TEXT);
        }
    }

    /** "● Connected" in a rounded outline. */
    private static final class Chip extends View {
        private final float k;
        private final String text;
        private final int dot;
        private final Paint ink;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean focused;

        Chip(Context c, float k, String text, int dot) {
            super(c);
            this.k = k;
            this.text = text;
            this.dot = dot;
            ink = Ui.text(Ui.semibold, 21 * k, TEXT);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(1.5f * k);
        }

        void setFocused(boolean f) {
            focused = f;
            invalidate();
        }

        @Override protected void onMeasure(int w, int h) {
            float tw = ink.measureText(text);
            setMeasuredDimension(Math.round(tw + (dot != 0 ? 58 : 36) * k), Math.round(46 * k));
        }

        @Override protected void onDraw(Canvas c) {
            float hh = getHeight() / 2f, inset = k;
            line.setColor(focused ? 0x331C1C1E : 0x40FFFFFF);
            c.drawRoundRect(inset, inset, getWidth() - inset, getHeight() - inset, hh, hh, line);
            float x = 18 * k;
            if (dot != 0) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(focused && dot == 0x99FFFFFF ? 0x991C1C1E : dot);
                if (dot == 0x99FFFFFF) {      // asleep: an empty ring
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.8f * k);
                }
                c.drawCircle(x + 5 * k, hh, 5 * k, p);
                x += 22 * k;
            }
            ink.setColor(focused ? DARK : TEXT);
            c.drawText(text, x, hh + Ui.halfCap(ink), ink);
        }
    }
}
