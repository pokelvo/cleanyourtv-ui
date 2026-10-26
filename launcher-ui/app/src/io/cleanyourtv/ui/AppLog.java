package io.cleanyourtv.ui;

import android.util.Log;

/** The app's log (adb logcat -s CleanYourTV-UI). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("CleanYourTV-UI", message);
    }
}
