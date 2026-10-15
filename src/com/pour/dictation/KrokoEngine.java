package com.pour.dictation;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.OnlineStream;
import java.io.File;

/**
 * Kroko streaming STT engine using sherpa-onnx.
 *
 * Model: sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06 (CC-BY-SA)
 * For personal/free use only — not commercially licensed.
 */
public class KrokoEngine implements Engine {

    private static final String TAG = "KrokoEngine";
    private static final int SAMPLE_RATE = 16000;

    static {
        try {
            System.loadLibrary("onnxruntime");
            System.loadLibrary("sherpa-onnx-c-api");
            System.loadLibrary("sherpa-onnx-jni");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libs", e);
        }
    }

    private final Context app;
    private final DictationEngine.Host host;
    private final android.os.Handler mainHandler;

    // Shared recognizer: loaded once, reused across sessions (like original).
    private static final Object LOCK = new Object();
    private static OnlineRecognizer shared = null;
    private static boolean loadFailed = false;

    private OnlineRecognizer recognizer;
    private OnlineStream stream;
    private AudioRecord recorder;
    private Thread workerThread;
    private volatile boolean recording = false;

    private final StringBuilder transcript = new StringBuilder();
    private String lastPartial = "";
    private long appendNoteId = -1;
    private String appendSeed = "";
    private long appendSectionId = -1;
    private String appendSectionSeed = "";
    private WaveformView waveformView;
    /** True after pause(): transcript intact, start() resumes. */
    private boolean paused = false;

    /** Draft key matching DictationEngine: section appends use negative
     * keys to avoid colliding with note ids. */
    private long draftKey() {
        return appendSectionId != -1 ? -(appendSectionId + 1) : appendNoteId;
    }

    public KrokoEngine(Context ctx, DictationEngine.Host host) {
        this.app = ctx.getApplicationContext();
        this.host = host;
        this.mainHandler = new android.os.Handler(
            android.os.Looper.getMainLooper());
    }

    public void setAppendTarget(long noteId, String existingText) {
        appendNoteId = noteId;
        appendSeed = existingText != null ? existingText : "";
        appendSectionId = -1;
    }

    public void setAppendSectionTarget(long sectionId, String existingText) {
        appendSectionId = sectionId;
        appendSectionSeed = existingText != null ? existingText : "";
        appendNoteId = -1;
    }

    public static File modelDir(Context ctx) {
        return new File(ctx.getFilesDir(), "kroko-model");
    }

    public static boolean isModelReady(Context ctx) {
        File dir = modelDir(ctx);
        return new File(dir, "encoder.onnx").isFile()
            && new File(dir, "decoder.onnx").isFile()
            && new File(dir, "joiner.onnx").isFile()
            && new File(dir, "tokens.txt").isFile();
    }

    @Override
    public boolean isAvailable() { return true; }

    public void setWaveformView(WaveformView v) {
        waveformView = v;
        if (v != null) v.clear();
    }

    /** Release the waveform reference (call from Activity.onDestroy
     * to avoid leaking the view's context). */
    public void clearWaveformView() {
        waveformView = null;
    }

    @Override
    public boolean hasMicPermission() {
        return android.content.pm.PackageManager.PERMISSION_GRANTED ==
            app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO);
    }

    @Override
    public boolean isRecording() { return recording; }

    private void copyAssetsIfNeeded() {
        File dir = modelDir(app);
        if (isModelReady(app)) return;
        DebugLog.log(app, "Copying Kroko model from assets...");
        try {
            if (!dir.isDirectory()) dir.mkdirs();
            String[] files = {"encoder.onnx", "decoder.onnx", "joiner.onnx",
                "tokens.txt"};
            for (String name : files) {
                File dest = new File(dir, name);
                if (dest.isFile() && dest.length() > 0) continue;
                java.io.InputStream in = app.getAssets().open(
                    "kroko/" + name);
                java.io.FileOutputStream out =
                    new java.io.FileOutputStream(dest);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                in.close(); out.close();
                DebugLog.log(app, "Copied: " + name);
            }
        } catch (Exception e) {
            DebugLog.log(app, "Kroko asset copy failed: " + e.getMessage());
        }
    }

    /** Load the model on a worker thread (e.g. from the splash screen). */
    public static void warmup(final Context ctx) {
        final Context appCtx = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    copyAssetsIfNeededStatic(appCtx);
                    getShared(appCtx);
                    DebugLog.log(appCtx, "Kroko warmup complete");
                } catch (Throwable ignored) { }
            }
        }, "KrokoWarmup").start();
    }

    private static void copyAssetsIfNeededStatic(Context app) {
        File dir = modelDir(app);
        if (isModelReady(app)) return;
        try {
            if (!dir.isDirectory()) dir.mkdirs();
            String[] files = {"encoder.onnx", "decoder.onnx", "joiner.onnx",
                "tokens.txt"};
            for (String name : files) {
                File dest = new File(dir, name);
                if (dest.isFile() && dest.length() > 0) continue;
                java.io.InputStream in = app.getAssets().open(
                    "kroko/" + name);
                java.io.FileOutputStream out =
                    new java.io.FileOutputStream(dest);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                in.close(); out.close();
            }
        } catch (Exception ignored) { }
    }

    /** Get or create the shared recognizer (loaded once per process). */
    private static OnlineRecognizer getShared(Context app) {
        synchronized (LOCK) {
            if (shared != null) return shared;
            if (loadFailed) {
                throw new IllegalStateException("kroko load failed earlier");
            }
            try {
                DebugLog.log(app, "Creating Kroko recognizer...");
                File dir = modelDir(app);
                String encoder = new File(dir, "encoder.onnx").getAbsolutePath();
                String decoder = new File(dir, "decoder.onnx").getAbsolutePath();
                String joiner = new File(dir, "joiner.onnx").getAbsolutePath();
                String tokens = new File(dir, "tokens.txt").getAbsolutePath();

                OnlineTransducerModelConfig transducer =
                    new OnlineTransducerModelConfig();
                transducer.setEncoder(encoder);
                transducer.setDecoder(decoder);
                transducer.setJoiner(joiner);

                OnlineModelConfig modelConfig = new OnlineModelConfig();
                modelConfig.setTransducer(transducer);
                modelConfig.setTokens(tokens);
                modelConfig.setNumThreads(2);
                modelConfig.setModelType("zipformer2");

                EndpointConfig endpointConfig = new EndpointConfig();
                endpointConfig.setRule2(new EndpointRule(true, 2.5f, 0.0f));

                OnlineRecognizerConfig config = new OnlineRecognizerConfig();
                config.setModelConfig(modelConfig);
                config.setEndpointConfig(endpointConfig);

                // Null AssetManager = load from file paths
                shared = new OnlineRecognizer(null, config);
                DebugLog.log(app, "Kroko recognizer created");
                return shared;
            } catch (Throwable t) {
                loadFailed = true;
                DebugLog.log(app, "Kroko recognizer failed: " + t.getMessage());
                throw new IllegalStateException("kroko init failed", t);
            }
        }
    }

    @Override
    public boolean start() {
        DebugLog.log(app, "KrokoEngine.start() called");
        // Resume from pause: transcript buffer is intact, just restart
        // audio capture. Don't reseed from appendSeed (already in buffer).
        boolean resuming = paused;
        paused = false;
        copyAssetsIfNeeded();

        if (!isModelReady(app)) {
            host.onStatus("Kroko model not ready");
            return false;
        }
        if (!hasMicPermission()) {
            host.onPermissionDenied();
            return false;
        }

        try {
            recognizer = getShared(app);
            DebugLog.log(app, "Using shared Kroko recognizer");

            stream = recognizer.createStream("");

            // On resume the transcript buffer already holds everything;
            // only seed on a fresh start.
            if (!resuming) {
                if (!appendSeed.isEmpty()) transcript.append(appendSeed);
                else if (!appendSectionSeed.isEmpty())
                    transcript.append(appendSectionSeed);
            }

            int bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (bufSize <= 0) {
                DebugLog.log(app, "Kroko: invalid buffer size");
                return false;
            }
            AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize * 4);
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                DebugLog.log(app, "Kroko: AudioRecord not initialized");
                try { ar.release(); } catch (Exception ignored) { }
                return false;
            }
            recorder = ar;
            try {
                recorder.startRecording();
            } catch (Exception e) {
                DebugLog.log(app, "Kroko: startRecording failed: " + e.getMessage());
                try { recorder.release(); } catch (Exception ignored) { }
                recorder = null;
                return false;
            }
            DebugLog.log(app, "Kroko: AudioRecord started");

            recording = true;
            host.onRecordingChanged(true);
            host.onStatus("Listening...");

            final OnlineRecognizer rec = recognizer;
            final OnlineStream s = stream;
            final AudioRecord rar = recorder;
            workerThread = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        feedLoop(rec, s, rar);
                    } catch (Throwable t) {
                        DebugLog.log(app, "Kroko feedLoop crashed: " + t.getMessage());
                    }
                    DebugLog.log(app, "Kroko feedLoop exited");
                }
            }, "KrokoWorker");
            workerThread.start();

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Start failed", e);
            DebugLog.log(app, "Kroko start failed: " + e.getMessage());
            host.onStatus("Kroko failed: " + e.getMessage());
            return false;
        }
    }

    private void feedLoop(OnlineRecognizer rec, OnlineStream s,
                          AudioRecord ar) {
        short[] buf = new short[4096];
        DebugLog.log(app, "Kroko feedLoop started");
        while (recording) {
            int n;
            try {
                n = ar.read(buf, 0, buf.length);
            } catch (Exception e) {
                DebugLog.log(app, "Kroko: ar.read failed: " + e.getMessage());
                break;
            }
            if (n <= 0) continue;
            float[] samples = new float[n];
            for (int i = 0; i < n; i++) {
                samples[i] = buf[i] / 32768.0f;
            }
            // Waveform
            // Capture the view reference locally: clearWaveformView() may
            // null the field on the UI thread while this loop runs, and the
            // posted Runnable would otherwise NPE.
            final WaveformView wv = waveformView;
            if (wv != null) {
                float sum = 0;
                for (int i = 0; i < n; i++) sum += samples[i] * samples[i];
                final float rms = (float) Math.sqrt(sum / n);
                wv.post(new Runnable() {
                    @Override public void run() {
                        wv.pushLevel(rms);
                    }
                });
            }
            try {
                s.acceptWaveform(samples, SAMPLE_RATE);
                while (rec.isReady(s)) {
                    rec.decode(s);
                }
                String text = rec.getResult(s).getText();
                if (text == null) text = "";
                text = text.trim();
                boolean endpoint = false;
                try {
                    endpoint = rec.isEndpoint(s);
                } catch (Throwable ignored) { }
                if (endpoint) {
                    if (!text.isEmpty()) {
                        if (transcript.length() > 0) transcript.append(" ");
                        transcript.append(text);
                        // Log length only, never the spoken words.
                        DebugLog.log(app, "Kroko endpoint: "
                                + text.length() + " chars");
                    }
                    try {
                        rec.reset(s);
                    } catch (Throwable ignored) { }
                    lastPartial = "";
                    final String full = transcript.toString();
                    // Autosave the in-progress words (throttled).
                    DraftStore.autosave(app, draftKey(), full);
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            host.onTranscript(full, "");
                        }
                    });
                } else if (!text.equals(lastPartial)) {
                    lastPartial = text;
                    if (!text.isEmpty()) {
                        // Log length only, never the spoken words.
                        DebugLog.log(app, "Kroko partial: "
                                + text.length() + " chars");
                    }
                    final String full = transcript.toString();
                    final String partial = text;
                    // Autosave combined transcript + live partial.
                    String combined = full
                            + (partial.isEmpty() ? "" : " " + partial);
                    DraftStore.autosave(app, draftKey(), combined);
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            host.onTranscript(full, partial);
                        }
                    });
                }
            } catch (Throwable t) {
                // Log and continue — don't kill the loop on transient errors
                DebugLog.log(app, "Kroko loop error (continuing): " + t.getMessage());
            }
        }
        DebugLog.log(app, "Kroko feedLoop exited");
    }

    @Override
    public void stop() {
        recording = false;
        paused = false;
        if (workerThread != null) {
            try { workerThread.join(500); } catch (InterruptedException ignored) {}
            workerThread = null;
        }
        if (recorder != null) {
            try { recorder.stop(); recorder.release(); }
            catch (Exception ignored) {}
            recorder = null;
        }
        // Commit any pending partial.
        if (!lastPartial.isEmpty()) {
            if (transcript.length() > 0) transcript.append(" ");
            transcript.append(lastPartial);
            lastPartial = "";
        }
        if (stream != null) {
            try { stream.release(); } catch (Exception ignored) {}
            stream = null;
        }
        // Do NOT release the shared recognizer — keep it loaded for next time.
        recognizer = null;
        host.onRecordingChanged(false);

        String finalText = transcript.toString().trim();
        transcript.setLength(0);
        lastPartial = "";
        // The note is saved — the crash draft has served its purpose.
        DraftStore.clear(app, draftKey());
        if (!finalText.isEmpty()) {
            // Save or append to the Bucket / project.
            long noteId;
            if (appendSectionId != -1) {
                noteId = NoteSaver.appendToSection(app, appendSectionId, finalText);
            } else if (appendNoteId != -1) {
                noteId = NoteSaver.appendToNote(app, appendNoteId, finalText);
            } else {
                noteId = NoteSaver.save(app, finalText);
            }
            DebugLog.log(app, "Kroko saved note id=" + noteId);
            final long savedId = noteId;
            // Clear the capture screen so it's ready for a new recording.
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    host.onTranscript("", "");
                }
            });
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    host.onNoteSaved(savedId);
                }
            });
        } else {
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    host.onTranscript("", "");
                    host.onNoteSaved(-1);
                }
            });
        }

        appendNoteId = -1; appendSeed = "";
        appendSectionId = -1; appendSectionSeed = "";
    }

    @Override public void pause() {
        DebugLog.log(app, "KrokoEngine.pause() called");
        if (!recording || paused) return;
        paused = true;
        // Stop audio but keep the transcript buffer intact.
        recording = false;
        if (workerThread != null) {
            try { workerThread.join(500); }
            catch (InterruptedException ignored) {}
            workerThread = null;
        }
        if (recorder != null) {
            try { recorder.stop(); recorder.release(); }
            catch (Exception ignored) {}
            recorder = null;
        }
        if (stream != null) {
            try { stream.release(); } catch (Exception ignored) {}
            stream = null;
        }
        // Commit any pending partial into the transcript buffer.
        if (!lastPartial.isEmpty()) {
            if (transcript.length() > 0) transcript.append(" ");
            transcript.append(lastPartial);
            lastPartial = "";
        }
        flushDraft();
        host.onRecordingChanged(false);
        host.onStatus("Paused. Tap to resume.");
    }

    @Override public boolean isPaused() { return paused; }

    @Override public void flushDraft() {
        String current = transcript.toString();
        if (!lastPartial.isEmpty()) {
            if (!current.isEmpty()) current += " ";
            current += lastPartial;
        }
        DraftStore.flushNow(app, draftKey(), current);
    }

    @Override public void onHostPause() {
        // Host pausing (e.g. navigating away): flush the draft so
        // words survive, but don't change recording state here —
        // pause()/stop() own that.
        if (recording || paused) flushDraft();
    }

    @Override public void destroy() {
        // Ensure any pending draft is on disk before teardown.
        DraftStore.forceFlush(app);
        stop();
    }
}
