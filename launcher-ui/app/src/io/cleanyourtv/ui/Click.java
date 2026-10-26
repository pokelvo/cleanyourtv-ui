package io.cleanyourtv.ui;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;

/**
 * The TV's own Android TV / Google TV click: the system UI sound file (Effect_Tick.ogg, what Android plays for a focus
 * move and a key click), read from the system and played through SoundPool, on the home screen only. Android's own
 * "sound effects" setting is off on this TV (it would add clicks to every app), so the home screen plays the file
 * itself, at the level the system uses for its effects (-6 dB). Without the file, no sound.
 */
final class Click {
    private static final String[] DIRS = {"/product/media/audio/ui/", "/system/media/audio/ui/"};
    private static final float LEVEL = 0.5f;
    private SoundPool pool;
    private int tick;

    Click(Context c) {
        String file = find("Effect_Tick.ogg");
        if (file == null) {
            AppLog.w("click sound unavailable: no Effect_Tick.ogg");
            return;
        }
        try {
            pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            tick = pool.load(file, 1);
        } catch (RuntimeException e) {
            AppLog.w("click sound unavailable: " + e.getMessage());
            release();
        }
    }

    private static String find(String name) {
        for (String d : DIRS) {
            File f = new File(d + name);
            if (f.canRead()) return f.getPath();
        }
        return null;
    }

    /** Moving the focus. */
    void move() {
        if (pool != null) pool.play(tick, LEVEL, LEVEL, 1, 0, 1f);
    }

    /** Opening (OK): Android TV plays the same tick for a click. */
    void open() {
        if (pool != null) pool.play(tick, LEVEL, LEVEL, 1, 0, 1f);
    }

    void release() {
        if (pool != null) pool.release();
        pool = null;
    }
}
