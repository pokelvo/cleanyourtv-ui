package io.cleanyourtv.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The HUD theme's menus: a cyan line drops from the button to a panel with cut corners; the choices are cards side by
 * side (icon, number, name, port and state with a square), a track with diamonds under them marks the focused one,
 * and the keys at the bottom. ← → move, Back closes it.
 */
final class HudMenu {
    private HudMenu() {}

    private static final int CYAN = HomeView.HUD_CYAN, TEXT = 0xFFFFFFFF, DIM = 0x99E6F4FF;

    static PopupWindow show(Activity a, View parent, Rect anchor, String title, String meta, List<GlassMenu.Entry> entries,
            String okHint, TvUi.OnPick pick) {
        float k = TvUi.k(a);
        int screenW = a.getResources().getDisplayMetrics().widthPixels;
        int n = entries.size();
        int cardW = Math.round((n > 3 ? 280 : 326) * k), gap = Math.round(24 * k), pad = Math.round(40 * k);
        int w = Math.max(Math.round(760 * k), n * cardW + (n - 1) * gap + 2 * pad);
        int x = Math.max(Math.round(80 * k), Math.min(screenW - w - Math.round(72 * k), anchor.centerX() - w / 2));
        int lineH = Math.round(70 * k);

        LinearLayout outer = new LinearLayout(a);
        outer.setOrientation(LinearLayout.VERTICAL);
        Drop drop = new Drop(a, k, anchor.centerX() - x);
        outer.addView(drop, new LinearLayout.LayoutParams(w, lineH));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Math.round(40 * k), pad, Math.round(32 * k));
        box.setBackground(new Panel(k, 26 * k, 26 * k, 0xFF101722, 0x6635D0F0, 1.5f * k));
        outer.addView(box, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = TvUi.text(a, k, 30, Ui.wide, TEXT);
        t.setText(title.toUpperCase(Ui.locale()));
        t.setLetterSpacing(0.18f);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (meta != null) {
            TextView m = TvUi.text(a, k, 18, Ui.mono, DIM);
            m.setText(meta.toUpperCase(Ui.locale()));
            m.setLetterSpacing(0.14f);
            head.addView(m);
        }
        box.addView(head);

        PopupWindow pop = new PopupWindow(outer, w, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Math.round(34 * k), 0, Math.round(26 * k));
        Track track = new Track(a, k, n, cardW, gap);
        List<View> cards = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int which = i;
            View card = card(a, k, entries.get(i), i, track, cardW);
            card.setOnClickListener(v -> {
                pop.dismiss();
                pick.pick(which);
            });
            card.setOnKeyListener((v, code, e) -> code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                    || (code == KeyEvent.KEYCODE_DPAD_LEFT && which == 0) || (code == KeyEvent.KEYCODE_DPAD_RIGHT && which == n - 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, Math.round(264 * k));
            if (i > 0) lp.leftMargin = gap;
            row.addView(card, lp);
            cards.add(card);
        }
        box.addView(row);
        box.addView(track, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(24 * k)));
        LinearLayout foot = new LinearLayout(a);
        foot.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        foot.setPadding(0, Math.round(26 * k), 0, 0);
        if (n > 1) {
            foot.addView(key(a, k, "◀ ▶"));
            foot.addView(hint(a, k, Ui.tr("Percorrer", "Browse")));
        }
        foot.addView(key(a, k, "OK"));
        foot.addView(hint(a, k, okHint));
        foot.addView(key(a, k, "←"));
        TextView close = hint(a, k, Ui.tr("Fechar", "Close"));
        close.setPadding(close.getPaddingLeft(), 0, 0, 0);
        foot.addView(close);
        box.addView(foot);

        pop.setAnimationStyle(android.R.style.Animation_Dialog);
        pop.showAtLocation(parent, Gravity.NO_GRAVITY, x, anchor.bottom);
        MinimalMenu.dimBehind(pop, 0.5f);
        View focus = cards.isEmpty() ? null : cards.get(0);
        if (focus != null) focus.post(focus::requestFocus);
        return pop;
    }

    private static View card(Activity a, float k, GlassMenu.Entry e, int index, Track track, int cardW) {
        LinearLayout v = new LinearLayout(a);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(Math.round(30 * k), Math.round(30 * k), Math.round(22 * k), Math.round(28 * k));
        v.setFocusable(true);
        Panel rest = new Panel(k, 0, 20 * k, 0xFF161E2A, 0x14FFFFFF, 1.5f * k);
        Panel on = new Panel(k, 0, 20 * k, 0xFF0F1C28, CYAN, 3 * k);
        v.setBackground(rest);
        IconView icon = new IconView(a, e.icon);
        v.addView(icon, new LinearLayout.LayoutParams(Math.round(48 * k), Math.round(48 * k)));
        v.addView(new View(a), new LinearLayout.LayoutParams(1, 0, 1));
        TextView num = TvUi.text(a, k, 17, Ui.mono, DIM);
        num.setText(index < 9 ? "0" + (index + 1) : String.valueOf(index + 1));
        v.addView(num);
        TextView name = TvUi.text(a, k, 30, Ui.bold, TEXT);
        name.setText(e.title);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, Math.round(4 * k), 0, Math.round(6 * k));
        // A long name shrinks to fit the card (down to 20) before it is cut.
        float room = cardW - 52 * k, px = name.getTextSize(), wide = name.getPaint().measureText(e.title);
        if (wide > room) name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.max(20 * k, px * room / wide));
        v.addView(name);
        LinearLayout sub = new LinearLayout(a);
        sub.setGravity(Gravity.CENTER_VERTICAL);
        if (e.sub != null) {
            TextView s = TvUi.text(a, k, 17, Ui.mono, DIM);
            s.setText(e.sub.toUpperCase(Ui.locale()));
            s.setLetterSpacing(0.14f);
            s.setSingleLine(true);
            sub.addView(s);
        }
        if (e.state != null) {
            Square sq = new Square(a, k, e.stateDot == GlassMenu.DOT_ON);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Math.round(12 * k), Math.round(12 * k));
            sp.leftMargin = Math.round(12 * k);
            sp.rightMargin = Math.round(10 * k);
            sub.addView(sq, sp);
            TextView st = TvUi.text(a, k, 17, Ui.mono, DIM);
            st.setText(e.state.toUpperCase(Ui.locale()));
            st.setLetterSpacing(0.14f);
            st.setSingleLine(true);
            sub.addView(st);
        }
        v.addView(sub);
        v.setOnFocusChangeListener((x, has) -> {
            v.setBackground(has ? on : rest);
            num.setTextColor(has ? CYAN : DIM);
            icon.setColor(has ? CYAN : 0xD9E6F4FF);
            if (has) track.select(index);
        });
        return v;
    }

    private static TextView key(Context c, float k, String text) {
        TextView t = TvUi.text(c, k, 17, Ui.mono, TEXT);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Math.round(64 * k));
        t.setPadding(Math.round(12 * k), Math.round(8 * k), Math.round(12 * k), Math.round(8 * k));
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setStroke(Math.max(1, Math.round(1.5f * k)), 0x66E6F4FF);
        t.setBackground(d);
        return t;
    }

    private static TextView hint(Context c, float k, String text) {
        TextView t = TvUi.text(c, k, 17, Ui.mono, 0xCCE6F4FF);
        t.setText(text.toUpperCase(Ui.locale()));
        t.setLetterSpacing(0.16f);
        t.setPadding(Math.round(16 * k), 0, Math.round(36 * k), 0);
        return t;
    }

    /** A dark fill and an outline with the top-left and bottom-right corners cut. */
    private static final class Panel extends android.graphics.drawable.Drawable {
        private final float tl, br, stroke;
        private final int color, line;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Panel(float k, float tl, float br, int color, int line, float stroke) {
            this.tl = tl;
            this.br = br;
            this.color = color;
            this.line = line;
            this.stroke = stroke;
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float i = stroke / 2;
            path.reset();
            path.moveTo(b.left + i + tl, b.top + i);
            path.lineTo(b.right - i, b.top + i);
            path.lineTo(b.right - i, b.bottom - i - br);
            path.lineTo(b.right - i - br, b.bottom - i);
            path.lineTo(b.left + i, b.bottom - i);
            path.lineTo(b.left + i, b.top + i + tl);
            path.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(line);
            c.drawPath(path, paint);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** The cyan line from the button down to the panel, ending on a small diamond. */
    private static final class Drop extends View {
        private final float k, x;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Drop(Context c, float k, float x) {
            super(c);
            this.k = k;
            this.x = x;
        }

        @Override protected void onDraw(Canvas c) {
            paint.setColor(CYAN);
            paint.setStrokeWidth(2.5f * k);
            float h = getHeight();
            c.drawLine(x, 8 * k, x, h - 6 * k, paint);
            path.reset();
            path.moveTo(x, h - 12 * k);
            path.lineTo(x + 7 * k, h - 5 * k);
            path.lineTo(x, h + 2 * k);
            path.lineTo(x - 7 * k, h - 5 * k);
            path.close();
            c.drawPath(path, paint);
        }
    }

    /** Under the cards: a line with a diamond under each one, cyan up to the focused card. */
    private static final class Track extends View {
        private final float k;
        private final int n, cardW, gap;
        private int at;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Track(Context c, float k, int n, int cardW, int gap) {
            super(c);
            this.k = k;
            this.n = n;
            this.cardW = cardW;
            this.gap = gap;
        }

        void select(int i) {
            at = i;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float cy = getHeight() / 2f, right = n * cardW + (n - 1) * gap;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * k);
            paint.setColor(0x33FFFFFF);
            c.drawLine(0, cy, right, cy, paint);
            paint.setColor(CYAN);
            paint.setStrokeWidth(3 * k);
            c.drawLine(0, cy, at * (cardW + gap) + cardW, cy, paint);
            for (int i = 0; i < n; i++) {
                float x = i * (cardW + gap) + cardW / 2f, s = (i == at ? 10 : 8) * k;
                path.reset();
                path.moveTo(x, cy - s);
                path.lineTo(x + s, cy);
                path.lineTo(x, cy + s);
                path.lineTo(x - s, cy);
                path.close();
                paint.setStrokeWidth(1.5f * k);
                paint.setStyle(i == at ? Paint.Style.FILL : Paint.Style.FILL_AND_STROKE);
                paint.setColor(i == at ? CYAN : 0xFF101722);
                c.drawPath(path, paint);
                if (i != at) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(0x99E6F4FF);
                    c.drawPath(path, paint);
                }
            }
        }
    }

    private static final class IconView extends View {
        private final String icon;
        private int color = 0xD9E6F4FF;

        IconView(Context c, String icon) {
            super(c);
            this.icon = icon;
        }

        void setColor(int c) {
            color = c;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float s = getWidth();
            Icons.draw(c, icon, s / 2, s / 2, s, color);
        }
    }

    private static final class Square extends View {
        private final float k;
        private final boolean on;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Square(Context c, float k, boolean on) {
            super(c);
            this.k = k;
            this.on = on;
        }

        @Override protected void onDraw(Canvas c) {
            float s = getWidth();
            if (on) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(CYAN);
                c.drawRect(0, 0, s, s, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.5f * k);
                paint.setColor(DIM);
                c.drawRect(k, k, s - k, s - k, paint);
            }
        }
    }
}
