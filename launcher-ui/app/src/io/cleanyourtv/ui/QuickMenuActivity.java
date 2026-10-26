package io.cleanyourtv.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The Sources and Options menus over any app, opened by the remote's Sources and Options keys (RemoteKeys, on a Philips
 * where the system's GlobalKey service would take them). A dark glass panel at the top right over a shade from the
 * right (the picture stays visible on the left); rows turn white when focused, as in the home screen's menus.
 * Sources: Live TV and the HDMI inputs, with the ones sending a signal marked. Options: the Philips picture, sound and
 * Ambilight screens, all settings and the software update (each row opens the Philips screen itself).
 * The same key again, Back, or a choice closes it.
 */
public class QuickMenuActivity extends Activity {
    static final String EXTRA_MENU = "menu", SOURCES = "sources", OPTIONS = "options";

    /** Options: Philips action, English label (Ui.tr), symbol. Rows whose action opens nothing are left out. */
    private static final String[][] OPTIONS_ROWS = {
        {"org.droidtv.action.EXPERIENCE_MENU", "Frequent settings", "tune"},
        {"org.droidtv.action.PICTURE_FORMAT", "Picture format", "tv"},
        {"org.droidtv.action.AMBILIGHT_OSD", "Ambilight", "sparkles"},
        {"org.droidtv.action.QUICKSETTINGSPEAKER", "Speakers", "speaker"},
        {Settings.ACTION_SETTINGS, "All settings", "settings"},
        {"org.droidtv.upgradeapp.ui.UPGRADESOFTWARE", "Software update", "sync"},
    };
    private static final int PANEL = 0xF2202329, INK = 0xFFF5F5F7, DIM = 0x99F5F5F7, PILL_INK = 0xFF14161A, GREEN = 0xFF30D158;

    private String menu;
    private FrameLayout root;
    private float k;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        k = TvUi.k(this);
        root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT,
                new int[] {0xB3000000, 0x59000000, 0x00000000}));
        setContentView(root);
        show(getIntent().getStringExtra(EXTRA_MENU));
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String m = intent.getStringExtra(EXTRA_MENU);
        if (m == null || m.equals(menu)) finish();      // the same key again closes it
        else show(m);
    }

    @Override protected void onStop() {
        super.onStop();
        finish();       // never left behind another app
    }

    private void show(String m) {
        menu = OPTIONS.equals(m) ? OPTIONS : SOURCES;
        root.removeAllViews();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(px(14), px(18), px(14), px(14));
        panel.setBackground(TvUi.round(PANEL, 26 * k));
        panel.setElevation(30 * k);
        TextView title = TvUi.text(this, k, 22, Ui.semibold, 0x8CF5F5F7);
        title.setText((SOURCES.equals(menu) ? Ui.tr("Fontes", "Sources") : Ui.tr("Opções", "Options")).toUpperCase(Ui.locale()));
        title.setLetterSpacing(0.1f);
        title.setPadding(px(22), 0, px(22), px(12));
        panel.addView(title);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        if (SOURCES.equals(menu)) fillSources(rows);
        else fillOptions(rows);
        if (rows.getChildCount() == 0) {
            finish();
            return;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(rows);
        panel.addView(scroll);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(560), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.setMargins(0, px(80), px(80), px(60));
        root.addView(panel, lp);
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.94f);
        panel.setTranslationY(-12 * k);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220).setInterpolator(new DecelerateInterpolator(1.6f)).start();
        View first = rows.getChildAt(0);
        first.post(first::requestFocus);
    }

    /** Live TV first, then every HDMI input; the ones with a device sending a signal get a green dot. */
    private void fillSources(LinearLayout rows) {
        List<HomeApps.Item> items = new ArrayList<>(HomeApps.allInputs(this));
        for (HomeApps.Item it : items) {
            boolean input = it.kind == HomeApps.INPUT;
            if (input && !it.connected && !it.standby) continue;    // an empty port: nothing to show
            String symbol = it.kind == HomeApps.LIVE_TV ? "live_tv" : it.symbol;
            addRow(rows, symbol, it.label, input ? it.connected : null, input && it.standby,
                    () -> HomeApps.open(this, it, root, 0, 0, 1, 1));
        }
    }

    private void fillOptions(LinearLayout rows) {
        for (String[] o : OPTIONS_ROWS) {
            Intent intent = new Intent(o[0]);
            if (getPackageManager().resolveActivity(intent, 0) == null) continue;
            addRow(rows, o[2], Ui.tr(o[1], o[1]), null, false, () -> {
                try {
                    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException e) {
                    AppLog.w("options: " + e.getMessage());
                }
            });
        }
    }

    /** A row: symbol, name and, for an input, its state; white with dark ink when focused. */
    private void addRow(LinearLayout rows, String symbol, String label, Boolean connected, boolean standby, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(18), px(14), px(22), px(14));
        row.setFocusable(true);
        Icon icon = new Icon(this, symbol);
        row.addView(icon, new LinearLayout.LayoutParams(px(44), px(44)));
        TextView name = TvUi.text(this, k, 28, Ui.medium, INK);
        name.setText(label);
        name.setSingleLine(true);
        name.setPadding(px(18), 0, 0, 0);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView state = null;
        if (connected != null) {
            state = TvUi.text(this, k, 22, Ui.medium, connected ? GREEN : DIM);
            state.setText(connected ? "● " + Ui.tr("Conectado", "Connected")
                    : standby ? Ui.tr("Em espera", "In standby") : Ui.tr("Sem sinal", "No signal"));
            row.addView(state);
        }
        final TextView stateView = state;
        final boolean on = connected != null && connected;
        row.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? TvUi.round(0xFFFFFFFF, 16 * k) : null);
            name.setTextColor(has ? PILL_INK : INK);
            icon.setColor(has ? PILL_INK : INK);
            if (stateView != null) stateView.setTextColor(has ? (on ? 0xFF1F8F3A : 0x991C1C1E) : (on ? GREEN : DIM));
        });
        row.setOnClickListener(v -> {
            finish();
            action.run();
        });
        int index = rows.getChildCount();
        row.setOnKeyListener((v, code, e) -> code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || (code == KeyEvent.KEYCODE_DPAD_UP && index == 0));
        rows.addView(row);
    }

    private int px(float v) {
        return Math.round(v * k);
    }

    /** A Material symbol drawn in the row's ink. */
    private static final class Icon extends View {
        private final String symbol;
        private int color = INK;

        Icon(Context c, String symbol) {
            super(c);
            this.symbol = symbol;
        }

        void setColor(int c) {
            color = c;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            Symbols.draw(getContext(), c, symbol, getWidth() / 2f, getHeight() / 2f, getHeight() * 0.62f, color);
        }
    }
}
