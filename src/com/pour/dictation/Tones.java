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

    private final AudioManager audioManager;
    private ToneGenerator tones;

    public Tones(Context ctx) {
        Context app = ctx.getApplicationContext();
        audioManager =
                (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        ToneGenerator tg = null;
        try {
            tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60);
        } catch (Exception e) {
            Log.w("Tones", "Suppressed exception", e);
        }
        tones = tg;
    }

    public void beep(boolean start) {
        try {
            if (tones != null) {
                tones.startTone(start ? ToneGenerator.TONE_PROP_BEEP
                        : ToneGenerator.TONE_PROP_BEEP2, 150);
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
