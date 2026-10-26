package io.cleanyourtv.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;

/**
 * The home screen (Android's "Home" role, set from the computer by launcher-ui/build-and-install.sh). It runs in the
 * :home process. If it ever fails, tools/restore.sh gives the home screen back to the TV's original one.
 */
public class HomeActivity extends Activity {
    private HomeView home;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        home = new HomeView(this);
        setContentView(home);
        getWindow().setBackgroundDrawable(null);
        openFrom(getIntent());
    }

    @Override protected void onResume() {
        super.onResume();
        TvConfig.apply(this);
        ScreensaverGuard.apply(this);
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
        visible = true;
        Updates.get(this).resumed();    // an update made ready while an app was in front installs now
    }

    /** The home screen is in front (an update installs only then: Android's confirmation shows over it). */
    static volatile boolean visible;

    @Override protected void onPause() {
        visible = false;
        if (home != null) home.pause();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home != null) home.reset(true);
        openFrom(intent);
    }

    /** `--es open explore|watchlist`: Explore from the computer (adb), as the remote's buttons open it. */
    private void openFrom(Intent intent) {
        String what = intent == null ? null : intent.getStringExtra("open");
        if (what == null) return;
        intent.removeExtra("open");
        if (what.equals("explore")) RemoteKeys.run(this, "tmdb:explore");
        else if (what.equals("watchlist")) RemoteKeys.run(this, "tmdb:watchlist");
    }

    @Override protected void onDestroy() {
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
