package com.pour.dictation;

import android.content.Context;
import android.util.Log;
import android.media.AudioManager;
import android.media.ToneGenerator;

/**
 * The only sounds Pour makes: one short tone on record start, one on
 * stop. Also mutes the music stream for the brief moment a framework
 * recognizer session starts, hiding the speech service's own blip.
 */
public class Tones {

    private final Context appCtx;
    private final AudioManager audioManager;
    private ToneGenerator tones;

    public Tones(Context ctx) {
        Context app = ctx.getApplicationContext();
        appCtx = app;
        audioManager =
                (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        ToneGenerator tg = null;
        try {
            // Music stream, not notification: while recording holds
            // exclusive audio focus (1.0.57) the system keeps the
            // notification stream quiet, which silenced our own cues.
            // The music stream stays audible and routes to headsets.
            tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
        } catch (Exception e) {
            Log.w("Tones", "Suppressed exception", e);
        }
        tones = tg;
    }

    /** Whether the user wants start/stop beeps (Settings, default on). */
    public static boolean cuesEnabled(Context ctx) {
        try {
            return Ui.prefs(ctx).getBoolean("record_cues", true);
        } catch (Exception e) {
            return true;
        }
    }

    public void beep(boolean start) {
        if (!cuesEnabled(appCtx)) return;
        try {
            if (tones != null) {
                // Start: one beep. Stop: a double beep, which needs a
                // longer window or its second beep is cut off and the
                // two cues sound identical.
                tones.startTone(start ? ToneGenerator.TONE_PROP_BEEP
                        : ToneGenerator.TONE_PROP_BEEP2, start ? 200 : 450);
            }
        } catch (Exception e) {
            Log.w("Tones", "Suppressed exception", e);
        }
    }

    public void setMusicMuted(boolean muted) {
        try {
            if (audioManager != null) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        muted ? AudioManager.ADJUST_MUTE
                                : AudioManager.ADJUST_UNMUTE,
                        0);
            }
        } catch (Exception e) {
            Log.w("Tones", "Suppressed exception", e);
        }
    }

    public void release() {
        setMusicMuted(false);
        if (tones != null) {
            try { tones.release(); } catch (Exception e) {
                Log.w("Tones", "Suppressed exception", e);
            }
            tones = null;
        }
    }
}
