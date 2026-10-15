package com.pour.dictation;

import android.Manifest;
import android.content.Context;
import android.util.Log;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * Owns a whole dictation session: SpeechRecognizer lifecycle, auto-restart,
 * transcript accumulation, tones, the mic-blip mute, and saving the note.
 * UI-agnostic — the phone capture Activity
 * service both drive it through the Host callbacks.
 */
public class DictationEngine implements Engine {

    public interface Host {
        void onStatus(String s);
        void onTranscript(String full, String live);
        void onRecordingChanged(boolean recording);
        void onPermissionDenied();
        /** A session's words were saved; noteId -1 means nothing was saved. */
        void onNoteSaved(long noteId);
    }

    private final Context app;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Tones tones;

    private SpeechRecognizer recognizer;
    private Intent recognizerIntent;

    private boolean wantListening = false;
    private boolean sessionActive = false;
    /** startListening() sent, no terminal callback yet — never overlap it. */
    private boolean listenRequested = false;
    private int consecutiveErrors = 0;
    /** Utterances the recognizer finished: never rewritten. */
    private final StringBuilder transcript = new StringBuilder();
    /** Current utterance's words, promoted from partials as they stabilize.
     * Append-only: once a word is shown in white it never un-prints; the
     * final result replaces the whole utterance authoritatively. */
    private final StringBuilder uttWhite = new StringBuilder();
    /** Words currently promoted to white (kept in sync with uttWhite). */
    private int uttWhiteCount = 0;
    /** Words of the previous partial, for finding the stable prefix. */
    private ArrayList<String> prevPartialWords = new ArrayList<>();
    /** Latest partial hypothesis not yet confirmed by onResults. */
    private String pendingPartial = null;
    /** True while stop() waits for the recognizer to finalize. */
    private boolean finalizing = false;
    private boolean destroyed = false;
    /** True after pause(): the transcript is intact, start() resumes. */
    private boolean paused = false;
    /** Append mode: seed text prepended at start, note updated at stop. */
    private long appendNoteId = -1;
    /** Section-append mode: the next session extends a section's body. */
    private long appendSectionId = -1;
    private String appendSeed = null;
    private android.media.AudioManager audioManager;
    private android.media.AudioManager.OnAudioFocusChangeListener
            focusListener;
    private Object focusRequest; // AudioFocusRequest on API 26+.

    @Override
    public void setAppendTarget(long noteId, String existingText) {
        appendNoteId = noteId;
        appendSectionId = -1;
        appendSeed = existingText == null ? "" : existingText;
    }

    @Override
    public void setAppendSectionTarget(long sectionId, String existingText) {
        appendSectionId = sectionId;
        appendNoteId = -1;
        appendSeed = existingText == null ? "" : existingText;
    }

    /** DraftStore key: note ids as-is; section ids negated and shifted
     * so they can never collide with a note id (-1 is the fresh-session
     * key). */
    private long draftKey() {
        return appendSectionId != -1 ? -(appendSectionId + 1) : appendNoteId;
    }

    private boolean inAppendMode() {
        return appendNoteId != -1 || appendSectionId != -1;
    }

    public DictationEngine(Context ctx, Host host) {
        this.app = ctx.getApplicationContext();
        this.host = host;
        this.tones = new Tones(app);
        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        // Keep one session alive through thinking pauses instead of
        // cutting at every silence, but don't leave the user hanging in
        // "Processing..." — with instant restarts, a finalize/restart
        // cycle is seamless, so finalize promptly after real pauses.
        recognizerIntent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                4000);
        recognizerIntent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                2500);
        initRecognizer();
    }

    /**
     * (Re)create the recognizer. Used at construction and to recover from
     * ERROR_CLIENT, which wedges the client object.
     */
    private void initRecognizer() {
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception e) {
                Log.w("DictationEngine", "Suppressed exception", e);
            }
            recognizer = null;
        }
        listenRequested = false;
        sessionActive = false;
        if (SpeechRecognizer.isRecognitionAvailable(app)) {
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(app);
                recognizer.setRecognitionListener(new Listener());
            } catch (Exception e) {
                recognizer = null;
            }
        }
    }

    @Override
    public boolean isAvailable() {
        return recognizer != null;
    }

    @Override
    public boolean hasMicPermission() {
        return app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public boolean isRecording() {
        return wantListening;
    }

    /**
     * Begin a dictation session. Returns false when it could not start
     * (no recognizer or no mic permission); the host is told why.
     */
    @Override
    public boolean start() {
        DebugLog.log(app, "DictationEngine.start() called");
        if (destroyed) return false;
        if (wantListening) return true;
        if (recognizer == null) {
            initRecognizer();
        }
        if (recognizer == null) {
            host.onStatus("Speech recognition isn't available on this device.");
            return false;
        }
        if (!hasMicPermission()) {
            host.onPermissionDenied();
            return false;
        }
        // A phone call (or anything else taking audio focus) owns the mic
        // right now — starting would record silence.
        if (!requestAudioFocus()) {
            host.onStatus("Something else is using the microphone. "
                    + "Try again in a moment.");
            return false;
        }
        wantListening = true;
        paused = false;
        consecutiveErrors = 0;
        finalizing = false;
        if (appendSeed != null && !appendSeed.trim().isEmpty()
                && transcript.length() == 0) {
            transcript.append(appendSeed.trim());
        }
        handler.removeCallbacksAndMessages(null);
        host.onRecordingChanged(true);
        beep(true);
        startSession();
        // In append mode the seed shows immediately, confirming the
        // writer is continuing the right chapter before a word is said.
        pushTranscript(displayText(), "");
        return true;
    }

    /**
     * Stop dictation. Asks the recognizer to finalize (stopListening) instead
     * of cancelling, so the in-flight utterance — the grey partial text —
     * still comes back as a result and gets saved. If the recognizer never
     * answers, a 3s fallback saves whatever partials we have.
     */
    @Override
    public void stop() {
        // A paused session still has words to save: stopping it must
        // finalize, not no-op.
        DebugLog.log(app, "DictationEngine.stop() called");
        if (!wantListening && !paused) return;
        wantListening = false;
        paused = false;
        abandonAudioFocus();
        handler.removeCallbacksAndMessages(null);
        sessionActive = false;
        pushTranscript(displayText(), "");
        setMusicMuted(false);
        beep(false);
        host.onStatus("Saving\u2026");
        host.onRecordingChanged(false);
        finalizing = true;
        try {
            if (recognizer != null) recognizer.stopListening();
        } catch (Exception e) {
            finalizing = false;
        }
        if (finalizing) {
            handler.postDelayed(new Runnable() {
                @Override public void run() { finishStop(); }
            }, 3000);
        } else {
            finishStop();
        }
    }

    /** The recognizer answered stop (or the fallback fired): save for real. */
    private void finishStop() {
        if (!finalizing) return;
        finalizing = false;
        handler.removeCallbacksAndMessages(null);
        try {
            if (recognizer != null) {
                recognizer.cancel();
                recognizer.destroy();
            }
        } catch (Exception e) {
            Log.w("DictationEngine", "Suppressed exception", e);
        }
        recognizer = null;
        abandonAudioFocus();
        closeUtteranceHarvest();
        saveNote();
    }

    /**
     * Pause without saving: an incoming call (or any audio-focus loss)
     * stops the recognizer, but the words stay in the transcript buffer
     * and are flushed to the crash draft. start() resumes from here.
     */
    @Override
    public void pause() {
        DebugLog.log(app, "DictationEngine.pause() called");
        if (!wantListening || paused) return;
        paused = true;
        wantListening = false;
        abandonAudioFocus();
        handler.removeCallbacksAndMessages(null);
        sessionActive = false;
        closeUtteranceHarvest();
        setMusicMuted(false);
        flushDraft();
        try {
            if (recognizer != null) recognizer.cancel();
        } catch (Exception e) {
            Log.w("DictationEngine", "Suppressed exception", e);
        }
        host.onRecordingChanged(false);
        host.onStatus("Paused. Tap to resume.");
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public void flushDraft() {
        DraftStore.flushNow(app, draftKey(), transcript.toString());
    }

    /**
     * Take transient audio focus for the session. Returns false when
     * something else (a call, another recorder) holds the mic.
     */
    private boolean requestAudioFocus() {
        try {
            if (audioManager == null) {
                audioManager = (android.media.AudioManager) app
                        .getSystemService(Context.AUDIO_SERVICE);
            }
            if (audioManager == null) return true;
            if (focusListener == null) {
                focusListener =
                        new android.media.AudioManager
                                .OnAudioFocusChangeListener() {
                            @Override public void onAudioFocusChange(
                                    int change) {
                                if (change == android.media.AudioManager
                                        .AUDIOFOCUS_LOSS) {
                                    // Permanent loss (e.g. phone call):
                                    // freeze the session, keep the words.
                                    // Transient loss (notification, etc.)
                                    // should not interrupt recording.
                                    pause();
                                }
                            }
                        };
            }
            int res;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.media.AudioFocusRequest req =
                        new android.media.AudioFocusRequest.Builder(
                                android.media.AudioManager
                                        .AUDIOFOCUS_GAIN_TRANSIENT)
                                .setOnAudioFocusChangeListener(focusListener)
                                .build();
                focusRequest = req;
                res = audioManager.requestAudioFocus(req);
            } else {
                res = audioManager.requestAudioFocus(focusListener,
                        android.media.AudioManager.STREAM_MUSIC,
                        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            }
            return res == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Exception e) {
            // Focus APIs are best-effort; never block dictation on them.
            return true;
        }
    }

    private void abandonAudioFocus() {
        try {
            if (audioManager == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 26
                    && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(
                        (android.media.AudioFocusRequest) focusRequest);
                focusRequest = null;
            } else if (focusListener != null) {
                audioManager.abandonAudioFocus(focusListener);
            }
        } catch (Exception e) {
            Log.w("DictationEngine", "Suppressed exception", e);
        }
    }

    private static ArrayList<String> splitWords(String s) {
        ArrayList<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String w : s.trim().split("\\s+")) {
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }

    private static int commonPrefixLen(ArrayList<String> a,
                                       ArrayList<String> b) {
        int n = Math.min(a.size(), b.size());
        int i = 0;
        while (i < n && a.get(i).equals(b.get(i))) i++;
        return i;
    }

    /** Push transcript to the host and autosave the in-progress words,
     * so a killed app only ever costs a few seconds of dictation. */
    private void pushTranscript(String full, String live) {
        String combined = full
                + (live == null || live.isEmpty() ? "" : " " + live);
        DraftStore.autosave(app, draftKey(), combined);
        host.onTranscript(full, live);
    }

    /** What the user should see: finished text plus the current utterance. */
    private String displayText() {
        if (uttWhite.length() == 0) return transcript.toString();
        if (transcript.length() == 0) return uttWhite.toString();
        return transcript.toString() + " " + uttWhite.toString();
    }

    private static String joinWords(java.util.List<String> words) {
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(w);
        }
        return sb.toString();
    }

    /** Clear all per-utterance promotion state. */
    private void clearUtt() {
        uttWhite.setLength(0);
        uttWhiteCount = 0;
        prevPartialWords.clear();
        pendingPartial = null;
    }

    /**
     * The current utterance is over (error, timeout, or stop): keep every
     * word we have — promoted partials plus the unconfirmed grey tail —
     * then clear utterance state. White never un-prints: if the recognizer
     * revised, the shown words stand and the next final result corrects.
     */
    private void closeUtteranceHarvest() {
        if (pendingPartial != null && !pendingPartial.trim().isEmpty()) {
            ArrayList<String> w = splitWords(pendingPartial);
            ArrayList<String> white = splitWords(uttWhite.toString());
            if (white.size() <= w.size()
                    && w.subList(0, white.size()).equals(white)) {
                for (int i = white.size(); i < w.size(); i++) {
                    uttWhite.append(" ").append(w.get(i));
                }
                uttWhiteCount = w.size();
            }
            // Else diverged: keep the white the user already saw.
        }
        if (uttWhite.length() > 0) {
            if (transcript.length() > 0) transcript.append(" ");
            transcript.append(uttWhite);
        }
        clearUtt();
        pushTranscript(displayText(), "");
    }

    /** Call from the host's onPause so the music stream never stays muted. */
    @Override
    public void onHostPause() {
        tones.setMusicMuted(false);
        // Lifecycle flush: words hit the disk before anything can go
        // wrong while the host is backgrounded.
        if (wantListening) flushDraft();
    }

    @Override
    public void destroy() {
        destroyed = true;
        wantListening = false;
        paused = false;
        abandonAudioFocus();
        listenRequested = false;
        sessionActive = false;
        handler.removeCallbacksAndMessages(null);
        tones.release();
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception e) {
                Log.w("DictationEngine", "Suppressed exception", e);
            }
            recognizer = null;
        }
    }

    /** Short tone when a recording starts or stops — the only sounds we make. */
    private void beep(boolean start) {
        tones.beep(start);
    }

    /**
     * The speech service can play its own start-of-listening blip on the
     * music stream. Mute it for the brief moment a session starts; it is
     * always unmuted on the first callback, on stop, and on host pause.
     */
    private void setMusicMuted(boolean muted) {
        tones.setMusicMuted(muted);
    }

    private void saveNote() {
        String full = transcript.toString();
        long savedId;
        boolean nothingNew = inAppendMode() && appendSeed != null
                && full.trim().equals(appendSeed.trim());
        if (nothingNew) {
            // Appended nothing new — leave the target untouched.
            savedId = -1;
        } else if (appendSectionId != -1) {
            savedId = NoteSaver.appendToSection(app, appendSectionId, full);
        } else if (appendNoteId != -1) {
            savedId = NoteSaver.appendToNote(app, appendNoteId, full);
        } else {
            savedId = NoteSaver.save(app, full);
        }
        long targetNote = draftKey();
        appendNoteId = -1;
        appendSectionId = -1;
        appendSeed = null;
        // Keep the draft when real words failed to save (db error) so
        // they stay recoverable; otherwise the draft has served its
        // purpose. Clears only this session's note draft — an
        // unrecovered draft from another note is left alone.
        boolean failedWithContent = savedId == -1 && !nothingNew
                && !full.trim().isEmpty();
        if (!failedWithContent) DraftStore.clear(app, targetNote);
        transcript.setLength(0);
        host.onTranscript("", "");
        if (savedId != -1) {
            // Back to idle: the status line clears instead of showing a
            // placeholder subtitle.
            host.onStatus("");
            host.onNoteSaved(savedId);
        } else if (failedWithContent) {
            // The words are safe in the recovery draft; say so instead
            // of claiming there was nothing to save.
            host.onStatus("Couldn't save — your words are kept as a draft.");
        } else if (nothingNew) {
            host.onStatus("Stopped. Nothing new dictated.");
        } else {
            host.onStatus("Stopped. Nothing to save.");
        }
    }

    private void startSession() {
        if (recognizer == null || sessionActive || listenRequested) return;
        try {
            setMusicMuted(true);
            listenRequested = true;
            recognizer.startListening(recognizerIntent);
        } catch (SecurityException e) {
            listenRequested = false;
            setMusicMuted(false);
            wantListening = false;
            host.onRecordingChanged(false);
            host.onPermissionDenied();
        } catch (Exception e) {
            listenRequested = false;
            setMusicMuted(false);
        }
    }

    /**
     * Restart with no delay for the happy path (a finished result, or a
     * silence timeout): any gap here clips the start of the next sentence.
     * Errors keep the exponential backoff in restartSoon().
     */
    private void restartNow() {
        consecutiveErrors = 0;
        handler.post(new Runnable() {
            @Override public void run() {
                if (wantListening && !sessionActive) startSession();
            }
        });
    }

    private void restartSoon() {
        consecutiveErrors++;
        if (consecutiveErrors >= 10) {
            // The speech service is failing in a tight loop (each retry can
            // blip the speaker) — stop and say so instead of looping forever,
            // but don't drop the words we already have.
            consecutiveErrors = 0;
            wantListening = false;
            setMusicMuted(false);
            closeUtteranceHarvest();
            saveNote();
            host.onRecordingChanged(false);
            host.onStatus("The speech service kept failing. "
                    + "Check your connection and try again.");
            return;
        }
        // Back off exponentially so retries don't chatter: 0.5s, 1s, 2s, 4s…
        int shift = Math.min(consecutiveErrors, 4);
        long delay = Math.min(250L * (1L << shift), 4000L);
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (wantListening && !sessionActive) startSession();
            }
        }, delay);
    }

    private class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) {
            sessionActive = true;
            listenRequested = false;
            consecutiveErrors = 0;
            // New utterance: nothing promoted yet.
            clearUtt();
            setMusicMuted(false);
            host.onStatus("Listening\u2026");
        }
        @Override public void onBeginningOfSpeech() {
            host.onStatus("Listening\u2026");
        }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() {
            sessionActive = false;
            host.onStatus("Processing\u2026");
        }
        @Override public void onError(int error) {
            sessionActive = false;
            listenRequested = false;
            setMusicMuted(false);
            if (finalizing) {
                // stop() asked the recognizer to finalize and it errored
                // instead — save whatever we have.
                finishStop();
                return;
            }
            if (!wantListening) {
                host.onStatus("");
                return;
            }
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                wantListening = false;
                host.onRecordingChanged(false);
                host.onPermissionDenied();
                return;
            }
            if (error == SpeechRecognizer.ERROR_CLIENT
                    || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                // Transient contention — usually from restarting while the
                // previous session tears down. Bank the words, rebuild the
                // client if it's wedged, and retry with backoff. Never kill
                // a dictation session over this.
                closeUtteranceHarvest();
                if (error == SpeechRecognizer.ERROR_CLIENT) initRecognizer();
                if (recognizer == null || !wantListening) {
                    stop();
                    return;
                }
                restartSoon();
                return;
            }
            if (error == SpeechRecognizer.ERROR_NO_MATCH
                    || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                // Just silence — bank the utterance's words and come
                // straight back so the next sentence isn't clipped.
                closeUtteranceHarvest();
                if (wantListening) restartNow();
                return;
            }
            closeUtteranceHarvest();
            restartSoon();
        }
        @Override public void onResults(Bundle results) {
            sessionActive = false;
            listenRequested = false;
            consecutiveErrors = 0;
            setMusicMuted(false);
            ArrayList<String> list = results.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION);
            boolean gotFinal = false;
            if (list != null && !list.isEmpty()) {
                String text = list.get(0).trim();
                if (!text.isEmpty()) {
                    // The final result is authoritative: it replaces
                    // whatever the partials promoted for this utterance.
                    if (transcript.length() > 0) transcript.append(" ");
                    transcript.append(text);
                    gotFinal = true;
                    pushTranscript(transcript.toString(), "");
                }
            }
            if (gotFinal) {
                clearUtt();
            } else {
                // No usable final — keep the partials' words instead of
                // dropping them.
                closeUtteranceHarvest();
            }
            if (wantListening) {
                restartNow();
            } else if (finalizing) {
                finishStop();
            } else {
                host.onStatus("");
            }
        }
        @Override public void onPartialResults(Bundle partialResults) {
            ArrayList<String> list = partialResults.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION);
            if (list == null || list.isEmpty()) return;
            String p = list.get(0);
            pendingPartial = p;
            ArrayList<String> w = splitWords(p);
            int common = commonPrefixLen(prevPartialWords, w);
            // Words the recognizer has moved past are stable: promote them
            // to white as you speak, and never demote — once shown, a word
            // stays until the final result replaces the utterance. The
            // newest word(s) stay grey until confirmed by the next partial.
            for (int i = uttWhiteCount; i < common; i++) {
                if (uttWhite.length() > 0) uttWhite.append(" ");
                uttWhite.append(w.get(i));
                uttWhiteCount++;
            }
            prevPartialWords = w;
            String grey = common >= uttWhiteCount
                    ? joinWords(w.subList(common, w.size()))
                    : joinWords(w);
            pushTranscript(displayText(), grey.toString());
        }
        @Override public void onEvent(int eventType, Bundle params) { }
    }
}
