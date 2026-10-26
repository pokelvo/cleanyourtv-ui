package io.cleanyourtv.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.List;

/**
 * The Minimal theme's menus: a near-black panel along the right edge (the home screen dimmed behind it), a lime
 * kicker with the count, a large title, numbered rows with a hairline between them (the focused one with a lime bar
 * and an arrow), and the keys at the bottom. Back closes it.
 */
final class MinimalMenu {
    private MinimalMenu() {}

    private static final int TEXT = 0xFFFFFFFF, DIM = 0x99FFFFFF, LINE = 0x29FFFFFF;

    static PopupWindow show(Activity a, View parent, String kicker, String count, String title, List<GlassMenu.Entry> entries,
            String okHint, TvUi.OnPick pick) {
        float k = TvUi.k(a);
        int screenW = a.getResources().getDisplayMetrics().widthPixels, screenH = a.getResources().getDisplayMetrics().heightPixels;
        int w = Math.round(720 * k);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF0F0F0F);
        int pad = Math.round(64 * k);
        box.setPadding(pad, Math.round(70 * k), Math.round(76 * k), Math.round(56 * k));
        PopupWindow pop = new PopupWindow(box, w, screenH, true);
        pop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));

        LinearLayout head = new LinearLayout(a);
        TextView kick = TvUi.text(a, k, 19, Ui.monoMedium, HomeView.MN_LIME);
        kick.setText(kicker.toUpperCase(Ui.locale()));
        kick.setLetterSpacing(0.14f);
        head.addView(kick, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView n = TvUi.text(a, k, 19, Ui.mono, DIM);
        n.setText(count);
        head.addView(n);
        box.addView(head);
        TextView t = TvUi.text(a, k, 62, Ui.regular, TEXT);
        t.setText(title);
        t.setLetterSpacing(-0.02f);
        t.setLineSpacing(0, 0.95f);
        t.setPadding(0, Math.round(26 * k), 0, Math.round(56 * k));
        box.addView(t);

        View first = null;
        int last = entries.size() - 1;
        for (int i = 0; i < entries.size(); i++) {
            final int which = i;
            View row = row(a, k, entries.get(i), i);
            row.setOnClickListener(v -> {
                pop.dismiss();
                pick.pick(which);
            });
            row.setOnKeyListener((v, code, e) -> code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || (code == KeyEvent.KEYCODE_DPAD_UP && which == 0) || (code == KeyEvent.KEYCODE_DPAD_DOWN && which == last));
            box.addView(row);
            View line = new View(a);
            line.setBackgroundColor(LINE);
            box.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(1.5f * k))));
            if (first == null) first = row;
        }
        box.addView(new View(a), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout foot = new LinearLayout(a);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.addView(key(a, k, "OK"));
        foot.addView(hint(a, k, okHint));
        foot.addView(key(a, k, "←"));
        foot.addView(hint(a, k, Ui.tr("Fechar", "Close")));
        box.addView(foot);

        // A hairline down the panel's left edge.
        FrameLayout frame = new FrameLayout(a);
        frame.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View edge = new View(a);
        edge.setBackgroundColor(LINE);
        frame.addView(edge, new FrameLayout.LayoutParams(Math.max(1, Math.round(1.5f * k)), ViewGroup.LayoutParams.MATCH_PARENT));
        pop.setContentView(frame);

        pop.setAnimationStyle(android.R.style.Animation_Dialog);
        pop.showAtLocation(parent, Gravity.NO_GRAVITY, screenW - w, 0);
        dimBehind(pop, 0.6f);
        View focus = first;
        if (focus != null) focus.post(focus::requestFocus);
        return pop;
    }

    /** Darkens the home screen behind the panel (done by the system compositor: nothing more to draw). */
    static void dimBehind(PopupWindow pop, float amount) {
        try {
            View root = pop.getContentView().getRootView();
            WindowManager wm = (WindowManager) pop.getContentView().getContext().getSystemService(Context.WINDOW_SERVICE);
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) root.getLayoutParams();
            p.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            p.dimAmount = amount;
            wm.updateViewLayout(root, p);
        } catch (RuntimeException ignored) {
        }
    }

    /** "01  TV en direct / CHAÎNES TV": number, title, port and state with a square (lime: on, outline: asleep). */
    private static View row(Activity a, float k, GlassMenu.Entry e, int index) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Math.round(36 * k), Math.round(34 * k), Math.round(24 * k), Math.round(34 * k));
        r.setFocusable(true);
        TextView num = TvUi.text(a, k, 19, Ui.mono, DIM);
        num.setText(index < 9 ? "0" + (index + 1) : String.valueOf(index + 1));
        num.setMinWidth(Math.round(64 * k));
        r.addView(num);
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = TvUi.text(a, k, 40, Ui.regular, TEXT);
        title.setText(e.title);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        LinearLayout subRow = new LinearLayout(a);
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        subRow.setPadding(0, Math.round(8 * k), 0, 0);
        if (e.sub != null) {
            TextView s = TvUi.text(a, k, 17, Ui.mono, DIM);
            s.setText(e.sub.toUpperCase(Ui.locale()));
            s.setLetterSpacing(0.16f);
            subRow.addView(s);
        }
        if (e.state != null) {
            Square sq = new Square(a, k, e.stateDot == GlassMenu.DOT_ON);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Math.round(12 * k), Math.round(12 * k));
            sp.leftMargin = Math.round(14 * k);
            sp.rightMargin = Math.round(12 * k);
            subRow.addView(sq, sp);
            TextView st = TvUi.text(a, k, 17, Ui.mono, DIM);
            st.setText(e.state.toUpperCase(Ui.locale()));
            st.setLetterSpacing(0.16f);
            subRow.addView(st);
        }
        if (subRow.getChildCount() > 0) texts.addView(subRow);
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = TvUi.text(a, k, 34, Ui.regular, HomeView.MN_LIME);
        arrow.setText("→");
        arrow.setVisibility(View.INVISIBLE);
        r.addView(arrow);
        Bar bar = new Bar(a, k);
        r.setBackground(bar);
        r.setOnFocusChangeListener((v, has) -> {
            bar.on = has;
            v.invalidate();
            num.setTextColor(has ? HomeView.MN_LIME : DIM);
            arrow.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
        });
        return r;
    }

    private static TextView key(Context c, float k, String text) {
        TextView t = TvUi.text(c, k, 18, Ui.mono, TEXT);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Math.round(66 * k));
        t.setPadding(Math.round(14 * k), Math.round(8 * k), Math.round(14 * k), Math.round(8 * k));
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setStroke(Math.max(1, Math.round(1.5f * k)), 0x66FFFFFF);
        t.setBackground(d);
        return t;
    }

    private static TextView hint(Context c, float k, String text) {
        TextView t = TvUi.text(c, k, 18, Ui.mono, 0xCCFFFFFF);
        t.setText(text.toUpperCase(Ui.locale()));
        t.setLetterSpacing(0.16f);
        t.setPadding(Math.round(16 * k), 0, Math.round(40 * k), 0);
        return t;
    }

    /** The focused row's background: a lighter band with a lime bar on its left. */
    private static final class Bar extends android.graphics.drawable.Drawable {
        private final float k;
        boolean on;
        private final Paint paint = new Paint();

        Bar(Context c, float k) {
            this.k = k;
        }

        @Override public void draw(Canvas c) {
            if (!on) return;
            android.graphics.Rect b = getBounds();
            paint.setColor(0xFF1C1C1C);
            c.drawRect(b, paint);
            paint.setColor(HomeView.MN_LIME);
            c.drawRect(b.left, b.top, b.left + 6 * k, b.bottom, paint);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** The state square: filled lime (on) or an outline (asleep). */
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
                paint.setColor(HomeView.MN_LIME);
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
