package io.cleanyourtv.ui;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/**
 * Right after this app was updated (from the NAS, without ADB): what build-and-install.sh does after "pm install -r".
 * The remote-button service doesn't come back by itself after an update: it is switched off and on again in the
 * accessibility settings (WRITE_SECURE_SETTINGS, granted once over ADB). Then the screensaver delay, as at boot.
 */
public class UpdatedReceiver extends BroadcastReceiver {
    /** When the app was just updated (RemoteKeyService opens the home screen once, within a minute of it). */
    static final String HOME_PREFS = "updated", HOME_AFTER_UPDATE = "home_after_update";

    @Override public void onReceive(Context c, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        // Back to the home screen: Android brings back the task before it (any app), not Home. The remote-button
        // service does it once bound again (a service the system binds may open a screen); a direct try first.
        c.getSharedPreferences(HOME_PREFS, Context.MODE_PRIVATE).edit().putLong(HOME_AFTER_UPDATE, System.currentTimeMillis()).commit();
        try {
            c.startActivity(new Intent(c, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            AppLog.w("updated: can't open the home screen: " + e);
        }
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                rebindKeys(c);
                ScreensaverGuard.apply(c);
            } finally {
                result.finish();
            }
        }).start();
    }

    private static void rebindKeys(Context c) {
        String svc = c.getPackageName() + "/.RemoteKeyService";
        ContentResolver r = c.getContentResolver();
        try {
            String s = Settings.Secure.getString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null || !s.contains(svc)) return;      // the viewer had it off: leave it off
            Settings.Secure.putString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
            Settings.Secure.putInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            Thread.sleep(1000);
            Settings.Secure.putString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, s);
            Settings.Secure.putInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            AppLog.w("updated: remote buttons service bound again");
        } catch (SecurityException e) {
            AppLog.w("updated: no permission to bind the remote buttons again");
        } catch (InterruptedException ignored) {
        }
    }
}
