package io.cleanyourtv.ui;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stock and ETF quotes for the drawer's quotes card (instead of crypto): last price and the change over the last 24
 * hours of quotes (half-hour points; over a weekend, the last 24 hours the market was open), from Yahoo Finance's public chart endpoint (no key), every 10 minutes while the home screen shows.
 */
final class Stocks {
    interface Listener { void onStocks(); }

    /** Yahoo symbol and the name shown. */
    static final String[][] LIST = {
        {"^GSPC", "S&P 500"},
        {"^IXIC", "Nasdaq"},
        {"^STOXX50E", "Euro Stoxx 50"},
        {"^N225", "Nikkei 225"},
        {"GC=F", "Gold"},
    };
    private static final long EVERY_MS = 10 * 60_000L;

    private static Stocks instance;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    final double[] prices = new double[LIST.length], changes = new double[LIST.length];
    final String[] currency = new String[LIST.length];
    volatile long at;
    private volatile boolean fetching;

    static synchronized Stocks get() {
        if (instance == null) instance = new Stocks();
        return instance;
    }

    private Stocks() {
        java.util.Arrays.fill(prices, Double.NaN);
        java.util.Arrays.fill(changes, Double.NaN);
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    boolean any() {
        for (double p : prices) if (!Double.isNaN(p)) return true;
        return false;
    }

    void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (fetching || (now - at < EVERY_MS && now >= at)) return;
        fetching = true;
        io.execute(() -> {
            boolean got = false;
            for (int i = 0; i < LIST.length; i++) {
                try {
                    JSONObject res = get(LIST[i][0]).getJSONObject("chart").getJSONArray("result").getJSONObject(0);
                    JSONObject meta = res.getJSONObject("meta");
                    double last = meta.optDouble("regularMarketPrice", Double.NaN);
                    double prev = dayAgo(res);
                    prices[i] = last;
                    changes[i] = prev > 0 && !Double.isNaN(last) ? (last - prev) / prev * 100 : Double.NaN;
                    currency[i] = meta.optString("currency", "");
                    got = true;
                } catch (IOException | org.json.JSONException | RuntimeException e) {
                    AppLog.w("stocks " + LIST[i][0] + ": " + e.getClass().getSimpleName());
                }
            }
            if (got) at = System.currentTimeMillis();
            fetching = false;
            main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onStocks(); });
        });
    }

    /** The close 24 hours before the last point (NaN if the series is shorter). */
    private static double dayAgo(JSONObject res) throws org.json.JSONException {
        org.json.JSONArray ts = res.getJSONArray("timestamp");
        org.json.JSONArray close = res.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).getJSONArray("close");
        long target = ts.getLong(ts.length() - 1) - 24 * 3600;
        double found = Double.NaN;
        for (int i = 0; i < ts.length() && ts.getLong(i) <= target; i++) if (!close.isNull(i)) found = close.getDouble(i);
        return found;
    }

    private static JSONObject get(String symbol) throws IOException, org.json.JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://query1.finance.yahoo.com/v8/finance/chart/"
                + URLEncoder.encode(symbol, StandardCharsets.UTF_8.name()) + "?range=5d&interval=30m").openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        } finally {
            c.disconnect();
        }
    }

    /** "174,68 €", "7 623,68" (an index has no currency sign), "$418.51" in English. */
    String price(int i) {
        double p = prices[i];
        if (Double.isNaN(p)) return "—";
        String n = String.format(Ui.locale(), p >= 1000 ? "%,.0f" : "%,.2f", p);
        if (LIST[i][0].startsWith("^")) return n;
        String cur = currency[i] == null ? "" : currency[i];
        String sign = cur.equals("EUR") ? "€" : cur.equals("USD") ? "$" : cur.equals("GBp") ? "p" : cur;
        return Ui.en ? sign + n : n + " " + sign;
    }

    String change(int i) {
        double ch = changes[i];
        return Double.isNaN(ch) ? "" : String.format(Ui.locale(), "%+.2f %%", ch);
    }
}
