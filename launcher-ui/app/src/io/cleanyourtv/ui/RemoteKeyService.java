package io.cleanyourtv.ui;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 */
public class RemoteKeyService extends AccessibilityService {
    static final String ACTION_SLEEP = "io.cleanyourtv.ui.SLEEP", ACTION_POWER_MENU = "io.cleanyourtv.ui.POWER_MENU",
            ACTION_RESTART = "io.cleanyourtv.ui.RESTART";

    /**
     * The home screen's Power menu: an app may not turn the TV off or restart it, an accessibility service may ask the
     * system for the same thing as the remote's Power key: sleep (standby) or the system's power menu (Restart).
     */
    @Override public int onStartCommand(android.content.Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_SLEEP.equals(a)) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        else if (ACTION_POWER_MENU.equals(a)) performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
        else if (ACTION_RESTART.equals(a)) {
            // Restart in one go: the system's power menu, then its Restart button pressed for you.
            performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
            pressRestart(0);
        }
        return START_NOT_STICKY;
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_POWER) lastKeyAt = android.os.SystemClock.uptimeMillis();
        return RemoteKeys.onKey(this, event);
    }

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Looks for the power menu's Restart button for up to 3 s, then clicks it. */
    private void pressRestart(int tries) {
        main.postDelayed(() -> {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                for (String label : new String[] {"Redémarrer", "Restart", "Reiniciar", "Neu starten"}) {
                    for (android.view.accessibility.AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(label)) {
                        for (android.view.accessibility.AccessibilityNodeInfo c = n; c != null; c = c.getParent()) {
                            if (c.isClickable()) {
                                c.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
                                return;
                            }
                        }
                    }
                }
            }
            if (tries < 15) pressRestart(tries + 1);
        }, 200);
    }

    // At power on, Philips opens the antenna (playtv): the home screen comes back instead, unless a key was pressed.
    private static final String ANTENNA = "org.droidtv.playtv";
    private static final long AFTER_ON_MS = 15_000, AFTER_BOOT_MS = 4 * 60_000;
    private long screenOnAt, lastKeyAt;
    private final android.content.BroadcastReceiver screenOn = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, android.content.Intent i) {
            screenOnAt = android.os.SystemClock.uptimeMillis();
            AmbiHue.keepLastChoice(c);     // Philips starts the Hue lamps at power on: the last choice instead
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        screenOnAt = android.os.SystemClock.uptimeMillis();    // the service starts with the TV
        registerReceiver(screenOn, new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON));
        if (android.os.SystemClock.elapsedRealtime() < AFTER_BOOT_MS) AmbiHue.keepLastChoice(this);
        // Just after boot, Philips has already opened the antenna before this service runs: the home screen instead.
        if (android.os.SystemClock.elapsedRealtime() < AFTER_BOOT_MS) {
            main.postDelayed(() -> {
                if (lastKeyAt == 0 && !getPackageName().equals(RemoteKeys.front)) goHome();
            }, 2500);
        }
        // Just updated (from the NAS): Android put back whatever app was before the home screen; the home screen.
        android.content.SharedPreferences up = getSharedPreferences(UpdatedReceiver.HOME_PREFS, MODE_PRIVATE);
        long at = up.getLong(UpdatedReceiver.HOME_AFTER_UPDATE, 0);
        if (at != 0) {
            up.edit().remove(UpdatedReceiver.HOME_AFTER_UPDATE).apply();
            if (System.currentTimeMillis() - at < 60_000) main.postDelayed(this::goHome, 800);
        }
    }

    @Override public void onDestroy() {
        try {
            unregisterReceiver(screenOn);
        } catch (RuntimeException ignored) {
        }
        super.onDestroy();
    }

    private void homeInsteadOfAntenna() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - screenOnAt > AFTER_ON_MS || lastKeyAt > screenOnAt) return;
        screenOnAt = 0;     // once per power on
        goHome();
    }

    private void goHome() {
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
                    .setPackage(getPackageName()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
    }

    /** Which app is in front (the Options key stays Philips' own in Philips' apps); the menu itself doesn't count. */
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        CharSequence cls = e.getClassName();
        if (cls != null && cls.toString().endsWith("QuickMenuActivity")) return;
        RemoteKeys.front = e.getPackageName().toString();
        if (ANTENNA.equals(RemoteKeys.front)) homeInsteadOfAntenna();
    }

    @Override public void onInterrupt() {}
}
