package io.cleanyourtv.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/**
 * Keeps the screensaver on, after config.properties "screensaver_minutes" without a key (OLED: no image stays still for
 * hours). Some firmwares (Philips/TP Vision) put "never" back at every boot, so it is set again when the TV starts and
 * each time the home screen shows. Needs WRITE_SECURE_SETTINGS (granted by build-and-install.sh); without it, nothing.
 */
public class ScreensaverGuard extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        apply(c);
    }

    static void apply(Context c) {
        int minutes = Config.SCREENSAVER_MINUTES;
        if (minutes <= 0) return;
        try {
            android.content.ContentResolver r = c.getContentResolver();
            if (Settings.Secure.getInt(r, "screensaver_enabled", 0) != 1) Settings.Secure.putInt(r, "screensaver_enabled", 1);
            if (Settings.Secure.getInt(r, "screensaver_activate_on_sleep", 0) != 1) Settings.Secure.putInt(r, "screensaver_activate_on_sleep", 1);
            int ms = minutes * 60_000;
            if (Settings.System.getInt(r, Settings.System.SCREEN_OFF_TIMEOUT, 0) != ms) {
                Settings.System.putInt(r, Settings.System.SCREEN_OFF_TIMEOUT, ms);
                AppLog.w("screensaver: after " + minutes + " min again");
            }
        } catch (SecurityException e) {
            AppLog.w("screensaver: no permission to set it");
        }
    }
}
