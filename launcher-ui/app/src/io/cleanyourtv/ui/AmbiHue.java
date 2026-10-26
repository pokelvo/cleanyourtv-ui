package io.cleanyourtv.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Ambilight + Hue in one click, on the TV itself, with no menu and no network (Philips OLED907, RTT2.211108.001).
 *
 * <ul>
 * <li>On/off: Philips' own HueService (org.droidtv.alhuewizard) takes ALHUEON / ALHUEOFF from any app. ON finds the
 * bridge and drives the lamps in about 0.3 s; OFF puts the lamps back as they were and frees the bridge.</li>
 * <li>State: the bridge link service (org.droidtv.ambihuecommon, exported) says who holds the bridge:
 * {@link #STREAMING} while HueService drives the lamps, 2 when the bridge is free. Its client class comes from Philips'
 * shared library "tvfw" (uses-library in the manifest); reflection, so the app still builds against android.jar only.
 * Only getBridgeStatus is called: never the bridge's keys.</li>
 * </ul>
 *
 * HueService only starts when Settings > Ambilight > Ambilight extension > Ambilight+hue > "Mise en marche" is on; an
 * app may not change that setting (TvSettingsManager and MtkTvConfig refuse writes, measured on 2026-10-25), so ON
 * reads back as off when it isn't. Everything here blocks: call it off the main thread.
 */
final class AmbiHue {
    private AmbiHue() {}

    private static final ComponentName HUE_SERVICE =
            new ComponentName("org.droidtv.alhuewizard", "org.droidtv.ambihue.HueService");
    private static final String ACTION = "org.droidtv.ambihue.HueService.ALHUE";
    /** getBridgeStatus while HueService holds the bridge (2: free). */
    private static final int STREAMING = 0;

    /** Asks HueService to drive the Hue lamps from Ambilight, or to let them go. False if the TV has no HueService. */
    static boolean set(Context c, boolean on) {
        try {
            return c.startService(new Intent(ACTION + (on ? "ON" : "OFF")).setComponent(HUE_SERVICE)) != null;
        } catch (RuntimeException e) {
            AppLog.w("ambihue set: " + e);
            return false;
        }
    }

    /** Ambilight + Hue on now (HueService holds the bridge)? null when it can't be read: not this TV, or no answer. */
    static Boolean isOn(Context c) {
        CountDownLatch done = new CountDownLatch(1);
        Integer[] status = {null};
        ServiceConnection conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName n, IBinder b) {
                // onServiceConnected runs on the main thread: the binder call goes to a thread of its own.
                new Thread(() -> {
                    try {
                        status[0] = bridgeStatus(b);
                    } catch (Throwable t) {
                        AppLog.w("ambihue state: " + (t.getCause() != null ? t.getCause() : t));
                    }
                    done.countDown();
                }).start();
            }
            @Override public void onServiceDisconnected(ComponentName n) {}
        };
        Intent link = new Intent("org.droidtv.intent.action.HUELINKSERVICE").setPackage("org.droidtv.ambihuecommon");
        try {
            if (!c.bindService(link, conn, Context.BIND_AUTO_CREATE)) return null;
        } catch (RuntimeException e) {
            return null;
        }
        try {
            done.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        } finally {
            try {
                c.unbindService(conn);
            } catch (RuntimeException ignored) {
            }
        }
        return status[0] == null ? null : status[0] == STREAMING;
    }

    // MARK: - The last choice, kept across power-ons

    private static final String WANTED = "ambihue-wanted";

    /**
     * The viewer's last choice on the button. A file, not SharedPreferences: the home screen (:home) writes it and the
     * remote-button service (:keys) reads it, and SharedPreferences keeps a stale copy per process.
     */
    static void remember(Context c, boolean on) {
        java.io.File f = new java.io.File(c.getFilesDir(), WANTED), tmp = new java.io.File(c.getFilesDir(), WANTED + ".tmp");
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(tmp)) {
            o.write(on ? '1' : '0');
        } catch (java.io.IOException e) {
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    /** The last choice, or null if the button was never used. */
    static Boolean wanted(Context c) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(new java.io.File(c.getFilesDir(), WANTED))) {
            int b = in.read();
            return b == '1' ? Boolean.TRUE : b == '0' ? Boolean.FALSE : null;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean keeping = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * At power on (boot or the screen coming back), Philips starts the Hue lamps by itself whenever "Ambilight+hue" is
     * on in its settings. For 90 s, the last choice is put back: off → OFF as soon as HueService has started; on →
     * ON if HueService hasn't started by itself after 20 s. Runs on a thread of its own; once at a time.
     */
    static void keepLastChoice(Context c) {
        if (wanted(c) == null || !keeping.compareAndSet(false, true)) return;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                long start = android.os.SystemClock.elapsedRealtime();
                while (android.os.SystemClock.elapsedRealtime() - start < 90_000) {
                    android.os.SystemClock.sleep(3000);
                    Boolean want = wanted(app), now = isOn(app);
                    if (want == null || now == null) continue;
                    if (!want && now) {
                        AppLog.w("ambihue: Philips turned it on at power on, off again (last choice)");
                        set(app, false);
                        return;
                    }
                    if (want && !now && android.os.SystemClock.elapsedRealtime() - start > 20_000) {
                        AppLog.w("ambihue: on again (last choice)");
                        set(app, true);
                        return;
                    }
                }
            } finally {
                keeping.set(false);
            }
        }, "ambihue-keep").start();
    }

    private static java.lang.reflect.Constructor<?> proxy;
    private static java.lang.reflect.Method getBridgeStatus;

    private static int bridgeStatus(IBinder b) throws Exception {
        if (proxy == null) {
            Class<?> px = Class.forName("org.droidtv.ambihue.LinkServiceImpl$Proxy");
            proxy = px.getDeclaredConstructor(IBinder.class);
            proxy.setAccessible(true);
            getBridgeStatus = px.getDeclaredMethod("getBridgeStatus");
            getBridgeStatus.setAccessible(true);
        }
        return (int) getBridgeStatus.invoke(proxy.newInstance(b));
    }
}
