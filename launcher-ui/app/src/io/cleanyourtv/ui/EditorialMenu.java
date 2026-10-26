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
 * The Editorial theme's small menus: a paper card (cream by day, near black at night) under its button, a kicker in the
 * accent with a count, a question in serif italics, rows with a state dot (accent: no state, filled: on, ring: asleep)
 * and a serif name, the focused row filled with the ink and an arrow, and one line of help at the bottom.
 */
final class EditorialMenu {
    private EditorialMenu() {}

    static PopupWindow show(Activity a, View parent, Rect anchor, String kicker, String meta, String question,
            List<GlassMenu.Entry> entries, String okHint, boolean light, TvUi.OnPick pick) {
        float k = TvUi.k(a);
        int paper = light ? 0xFFF7F2EA : 0xFF141312, ink = light ? 0xFF1F1B18 : 0xFFEFE8DE;
        int dim = light ? 0x991F1B18 : 0x99EFE8DE, accent = light ? 0xFFD9553A : 0xFFE8603F;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Math.round(26 * k), Math.round(34 * k), Math.round(26 * k), Math.round(26 * k));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(paper);
        bg.setCornerRadius(40 * k);
        bg.setStroke(Math.max(1, Math.round(1.5f * k)), light ? 0x1F1F1B18 : 0x26EFE8DE);
        box.setBackground(bg);
        box.setElevation(40 * k);
        box.setMinimumWidth(Math.round(640 * k));

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setPadding(Math.round(26 * k), 0, Math.round(26 * k), 0);
        TextView kick = TvUi.text(a, k, 19, Ui.semibold, accent);
        kick.setText(kicker.toUpperCase(Ui.locale()));
        kick.setLetterSpacing(0.2f);
        head.addView(kick, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (meta != null) {
            TextView m = TvUi.text(a, k, 21, Ui.medium, dim);
            m.setText(meta);
            head.addView(m);
        }
        box.addView(head);
        TextView q = TvUi.text(a, k, 52, Ui.serifItalic, ink);
        q.setText(question);
        q.setPadding(Math.round(26 * k), Math.round(4 * k), Math.round(26 * k), Math.round(18 * k));
        box.addView(q);

        PopupWindow pop = new PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        pop.setElevation(40 * k);
        View first = null;
        int last = entries.size() - 1;
        for (int i = 0; i < entries.size(); i++) {
            final int which = i;
            View row = row(a, k, entries.get(i), ink, dim, accent, paper);
            row.setOnClickListener(v -> {
                pop.dismiss();
                pick.pick(which);
            });
            row.setOnKeyListener((v, code, e) -> code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || (code == KeyEvent.KEYCODE_DPAD_UP && which == 0) || (code == KeyEvent.KEYCODE_DPAD_DOWN && which == last));
            box.addView(row);
            if (first == null) first = row;
        }
        View line = new View(a);
        line.setBackgroundColor(light ? 0x1F1F1B18 : 0x26EFE8DE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(1.5f * k)));
        lp.setMargins(Math.round(26 * k), Math.round(18 * k), Math.round(26 * k), Math.round(20 * k));
        box.addView(line, lp);
        TextView help = TvUi.text(a, k, 21, Ui.medium, dim);
        help.setText(Ui.tr("OK para ", "OK to ") + okHint.toLowerCase(Ui.locale()) + "  ·  " + Ui.tr("Voltar para fechar", "Back to close"));
        help.setPadding(Math.round(26 * k), 0, Math.round(26 * k), 0);
        box.addView(help);

        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int w = box.getMeasuredWidth(), hgt = box.getMeasuredHeight();
        pop.setWidth(w);
        pop.setHeight(hgt);
        int screenW = a.getResources().getDisplayMetrics().widthPixels, screenH = a.getResources().getDisplayMetrics().heightPixels;
        int x = Math.max(Math.round(80 * k), Math.min(screenW - w - Math.round(80 * k), anchor.centerX() - w / 2));
        int y = anchor.bottom + Math.round(30 * k);
        if (y + hgt > screenH - Math.round(24 * k)) y = Math.max(Math.round(24 * k), anchor.top - hgt - Math.round(30 * k));
        pop.setAnimationStyle(android.R.style.Animation_Dialog);
        pop.showAtLocation(parent, Gravity.NO_GRAVITY, x, y);
        View focus = first;
        if (focus != null) focus.post(focus::requestFocus);
        return pop;
    }

    private static View row(Activity a, float k, GlassMenu.Entry e, int ink, int dim, int accent, int paper) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Math.round(28 * k), Math.round(18 * k), Math.round(30 * k), Math.round(18 * k));
        row.setFocusable(true);
        // No state: the accent; on: filled with the ink; asleep: a ring.
        int kind = e.state == null ? 0 : e.stateDot == GlassMenu.DOT_ON ? 1 : 2;
        Dot dot = new Dot(a, k, kind, accent, ink);
        int ds = Math.round(16 * k);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ds, ds);
        dp.rightMargin = Math.round(24 * k);
        row.addView(dot, dp);
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = TvUi.text(a, k, 40, Ui.serif, ink);
        title.setText(e.title);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        String subText = e.sub;
        if (e.state != null) subText = (subText == null ? "" : subText + " — ") + e.state.toLowerCase(Ui.locale());
        TextView sub = null;
        if (subText != null && !subText.isEmpty()) {
            sub = TvUi.text(a, k, 21, Ui.medium, dim);
            sub.setText(subText);
            sub.setSingleLine(true);
            texts.addView(sub);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = TvUi.text(a, k, 30, Ui.medium, paper);
        arrow.setText("→");
        arrow.setPadding(Math.round(24 * k), 0, 0, 0);
        arrow.setVisibility(View.INVISIBLE);
        row.addView(arrow);
        final TextView s = sub;
        row.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? TvUi.round(ink, 30 * k) : null);
            title.setTextColor(has ? paper : ink);
            if (s != null) s.setTextColor(has ? (paper & 0x00FFFFFF) | 0xB3000000 : dim);
            arrow.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            dot.setOnInk(has, paper);
        });
        return row;
    }

    /** The state dot of a row. */
    private static final class Dot extends View {
        private final float k;
        private final int kind, accent, ink;
        private int color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Dot(Context c, float k, int kind, int accent, int ink) {
            super(c);
            this.k = k;
            this.kind = kind;
            this.accent = accent;
            this.ink = ink;
            color = kind == 0 ? accent : ink;
        }

        /** On the focused (ink) row, the ink-colored dots turn to the paper color; the accent stays. */
        void setOnInk(boolean on, int paper) {
            color = kind == 0 ? accent : on ? paper : ink;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float r = getWidth() / 2f;
            paint.setColor(color);
            if (kind == 2) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2 * k);
                c.drawCircle(r, r, r - 2 * k, paint);
            } else {
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(r, r, r - k, paint);
            }
        }
    }
}
