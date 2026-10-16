package com.pour.dictation;

import android.util.Log;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Pour — capture screen, styled like the Pour web app.
 * All recording work lives in DictationEngine;
 * this Activity is the phone UI on top of it.
 */
public class MainActivity extends Activity implements DictationEngine.Host {

    private static final int REQ_RECORD_AUDIO = 1001;
    private static final int REQ_POST_NOTIF = 1002;

    private Engine engine;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean startAfterGrant = false;
    private boolean pendingAutostart = false;

    private TextView statusView;
    private TextView transcriptView;
    private TextView liveView;
    private WaveformView waveformView;
    private TextView countView;
    private RecordButton circleBtn;
    private TextView recordLabel;
    private ScrollView scrollView;
    private LinearLayout permCard;
    private TextView permMsg;
    private LinearLayout draftCard;
    private TextView draftMsg;
    /** Append mode: continue an existing note instead of a new one. */
    private long appendNoteId = -1;
    /** Section-append mode: continue a section's body. */
    private long appendSectionId = -1;
    private String appendSeed = null;
    private String appendTitle = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.initTheme(this);
        CrashLog.install(getApplicationContext());
        // Auto-empty old trash per the retention setting.
        autoDeleteOldTrash();
        // Purge stale export cache files.
        purgeExportCache();
        // Demo data for screenshots (no-op unless DemoData.DEMO_MODE).
        DemoData.populateIfNeeded(this);
        // Kroko model ships in assets/kroko/ and is warmed up below;
        // no separate model download needed.
        final Context appCtx = getApplicationContext();
        engine = EngineFactory.create(this, this);
        buildUi();
        DebugLog.log(this, "MainActivity.onCreate engine="
                + engine.getClass().getSimpleName());

        // Warm up Kroko in background so first tap is instant.
        if (engine instanceof KrokoEngine) {
            KrokoEngine.warmup(this);
        }

        if (!engine.isAvailable()) {
            setStatus("Speech recognition isn't available on this device.");
            circleBtn.setEnabled(false);
            return;
        }

        // Ask for the mic up front so the system dialog appears on first
        // launch instead of hiding behind the record button.
        if (!engine.hasMicPermission()) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    REQ_RECORD_AUDIO);
        }

        // Continue an existing note ("Dictate more" from the note screen)
        // or a section's body ("Dictate more" from the section editor).
        appendNoteId = getIntent().getLongExtra("append_note_id", -1);
        appendSectionId = getIntent().getLongExtra("append_section_id", -1);
        appendSeed = getIntent().getStringExtra("append_seed");
        appendTitle = getIntent().getStringExtra("append_title");
        if (appendTitle != null && !appendTitle.trim().isEmpty()) {
            setStatus("Continuing \u201c" + appendTitle.trim()
                    + "\u201d. Tap to talk.");
        }

        // From the recording notification's actions (Stop / Pause).
        handleServiceAction(getIntent());

        // Offer back a crashed session's words before anything records
        // over them.
        checkDraft();
        boolean draftPending =
                draftCard.getVisibility() == View.VISIBLE;

        // From the splash screen's "Start recording": begin right away —
        // unless unsent words are waiting for a decision.
        pendingAutostart = getIntent().getBooleanExtra("autostart", false);
        if (pendingAutostart && engine.hasMicPermission() && !draftPending) {
            pendingAutostart = false;
            handler.postDelayed(new Runnable() {
                @Override public void run() { beginSession(); }
            }, 400);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleServiceAction(intent);
        if (intent.getBooleanExtra("autostart", false) && !engine.isRecording()
                && engine.hasMicPermission() && engine.isAvailable()
                && draftCard.getVisibility() != View.VISIBLE) {
            handler.postDelayed(new Runnable() {
                @Override public void run() { beginSession(); }
            }, 400);
        }
    }

    /** The recording notification's Stop / Pause actions land here (the
     * activity is single-top, so this reuses the live session). */
    private void handleServiceAction(Intent intent) {
        if (intent == null || engine == null) return;
        String action = intent.getStringExtra("svc_action");
        if (action == null) return;
        intent.removeExtra("svc_action");
        if ("stop".equals(action)) {
            if (engine.isRecording() || engine.isPaused()) engine.stop();
        } else if ("pause_toggle".equals(action)) {
            if (engine.isRecording()) {
                engine.pause();
            } else if (engine.isPaused()) {
                beginSession();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (engine != null) engine.onHostPause();
    }

    // ---- DictationEngine.Host ----

    @Override
    public void onStatus(String s) {
        setStatus(s);
    }

    @Override
    public void onTranscript(String full, String live) {
        // Spoken "new paragraph" / "new line" render as real breaks live,
        // matching what the save will store.
        transcriptView.setText(ParaBreaks.apply(full));
        liveView.setText(ParaBreaks.apply(live));
        scrollView.post(new Runnable() {
            @Override public void run() { scrollView.fullScroll(View.FOCUS_DOWN); }
        });
        updateCount();
    }

    @Override
    public void onRecordingChanged(boolean recording) {
        DebugLog.log(this, "onRecordingChanged recording=" + recording
                + " isPaused=" + (engine == null ? "null"
                : String.valueOf(engine.isPaused())));
        updateRecordUi(recording);
        if (recording) {
            // Hold a foreground claim so the session survives the screen
            // turning off (phone in a pocket while pacing).
            RecordService.start(this);
            if (Ui.keepAwake(this)) {
                getWindow().addFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        } else if (engine != null && engine.isPaused()) {
            // Paused, not stopped: keep the foreground claim so the
            // session's words stay alive, but show the paused state.
            RecordService.setPaused(this, true);
            getWindow().clearFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            RecordService.stop(this);
            getWindow().clearFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public void onPermissionDenied() {
        showPermNotice(true);
    }

    @Override
    public void onNoteSaved(long noteId) {
        if ((appendNoteId != -1 || appendSectionId != -1) && noteId != -1) {
            // An append session just landed: refresh the seed from the
            // database so tapping record again continues from the fresh
            // text instead of the stale pre-session copy (which would
            // silently drop the words just dictated).
            NotesDb db = new NotesDb(this);
            try {
                boolean ok = false;
                if (appendSectionId != -1) {
                    Section s = db.getSection(appendSectionId);
                    if (s != null) {
                        appendSeed = s.body;
                        ok = true;
                    }
                } else {
                    Note n = db.get(appendNoteId);
                    if (n != null) {
                        appendSeed = n.transcript;
                        ok = true;
                    }
                }
                if (!ok) {
                    appendNoteId = -1;
                    appendSectionId = -1;
                    appendSeed = null;
                    appendTitle = null;
                    setStatus("");
                }
            } catch (Exception e) {
                Log.w("MainActivity", "Suppressed exception", e);
            }
            try { db.close(); } catch (Exception e) {
                Log.w("MainActivity", "Suppressed exception", e);
            }
        } else {
            // A fresh session (or a recovered fresh draft): consume any
            // seed so the next tap starts clean instead of duplicating it.
            appendNoteId = -1;
            appendSectionId = -1;
            appendSeed = null;
            appendTitle = null;
        }
    }

    /** Auto-delete trash older than the retention setting. Runs on
     * every app start; cheap (two indexed deletes). */
    private void autoDeleteOldTrash() {
        int days = SettingsActivity.trashRetentionDays(this);
        if (days < 0) return; // "Never" — skip.
        final long cutoff = System.currentTimeMillis()
                - (long) days * 24L * 60L * 60L * 1000L;
        new Thread(new Runnable() {
            @Override public void run() {
                NotesDb db = null;
                try {
                    db = new NotesDb(getApplicationContext());
                    db.autoDeleteOldTrash(cutoff);
                } catch (Exception ignored) {
                } finally {
                    if (db != null) {
                        try { db.close(); } catch (Exception ignored) { }
                    }
                }
            }
        }, "TrashCleanup").start();
    }

    /** Delete stale files from cache/exports on launch. These are
     * temporary share files; they should not accumulate. */
    private void purgeExportCache() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.io.File dir = new java.io.File(
                            getCacheDir(), "exports");
                    if (!dir.isDirectory()) return;
                    java.io.File[] files = dir.listFiles();
                    if (files == null) return;
                    // Keep files from the last 24h (in case a share
                    // is still in flight); delete anything older.
                    long cutoff = System.currentTimeMillis()
                            - 24L * 60L * 60L * 1000L;
                    for (java.io.File f : files) {
                        if (f.lastModified() < cutoff) {
                            f.delete();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }, "ExportCachePurge").start();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.Drawable texBg = Ui.texturedBackground(this);
        if (texBg != null) root.setBackground(texBg);
        else root.setBackgroundColor(Ui.BG);
        Ui.applySafeInsets(this, root, 20);

        root.addView(Ui.header(this, "Capture",
                null, null, null, true, true, false));

        TextView purposeView = new TextView(this);
        purposeView.setText("Talk. Everything you say pours into the Bucket.");
        purposeView.setTextColor(Ui.MUTED);
        purposeView.setTextSize(14);
        LinearLayout.LayoutParams pvParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pvParams.topMargin = Ui.dp(this, 4);
        root.addView(purposeView, pvParams);

        statusView = new TextView(this);
        // The status line starts empty and only ever carries live state
        // (listening, paused, save results). It is not a second subtitle.
        statusView.setText("");
        statusView.setTextColor(Ui.MUTED);
        statusView.setGravity(Gravity.START);
        root.addView(statusView);

        permCard = buildPermCard();
        permCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams permParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        permParams.topMargin = Ui.dp(this, 12);
        root.addView(permCard, permParams);

        draftCard = buildDraftCard();
        draftCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams draftParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        draftParams.topMargin = Ui.dp(this, 12);
        root.addView(draftCard, draftParams);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.card(this, 16));
        int cp = Ui.dp(this, 16);
        card.setPadding(cp, cp, cp, cp);
        scrollView = new ScrollView(this);
        transcriptView = new TextView(this);
        transcriptView.setTextSize(16 * Ui.textScale(this));
        transcriptView.setTextColor(Ui.CARD_TEXT);
        transcriptView.setLineSpacing(0, 1.3f);
        transcriptView.setMinLines(6);
        scrollView.addView(transcriptView);
        card.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        liveView = new TextView(this);
        liveView.setTextSize(14);
        liveView.setTextColor(Ui.CARD_MUTED);
        card.addView(liveView);

        // Real-time audio waveform (tiny, subtle — width of record button).
        waveformView = new WaveformView(this);
        waveformView.setAlpha(0.6f);
        LinearLayout.LayoutParams waveParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 56), Ui.dp(this, 12));
        waveParams.topMargin = Ui.dp(this, 4);
        waveParams.bottomMargin = Ui.dp(this, 4);
        waveParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        card.addView(waveformView, waveParams);

        countView = new TextView(this);
        countView.setGravity(Gravity.END);
        countView.setTextColor(Ui.CARD_MUTED);
        countView.setTextSize(14);
        card.addView(countView);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        cardParams.topMargin = Ui.dp(this, 16);
        root.addView(card, cardParams);

        // Big coral record circle, like the web app.
        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams centerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        centerParams.topMargin = Ui.dp(this, 20);
        root.addView(center, centerParams);

        circleBtn = new RecordButton(this);
        circleBtn.setContentDescription("Start recording");
        int cs = Ui.dp(this, 168);
        center.addView(circleBtn, new LinearLayout.LayoutParams(cs, cs));
        circleBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onToggle(); }
        });

        recordLabel = new TextView(this);
        recordLabel.setText("Tap to talk");
        recordLabel.setTextColor(Ui.MUTED);
        recordLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = Ui.dp(this, 12);
        center.addView(recordLabel, labelParams);

        root.addView(Ui.tabRow(this, Ui.TAB_CAPTURE));

        setContentView(root);
        updateCount();
    }

    private void onToggle() {
        if (engine.isRecording()) {
            engine.stop();
            return;
        }
        if (!engine.hasMicPermission()) {
            // Resume automatically if the user grants it from the dialog.
            startAfterGrant = true;
            showPermNotice(false);
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    REQ_RECORD_AUDIO);
            return;
        }
        permCard.setVisibility(View.GONE);
        beginSession();
    }

    /** Loud, persistent notice when the mic permission is missing. */
    /** Crash recovery: offer back an unsent draft instead of silently
     * recording over it. Suppresses autostart while the offer stands. */
    private LinearLayout buildDraftCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.card(this, 16));
        int p = Ui.dp(this, 16);
        card.setPadding(p, p, p, p);

        draftMsg = new TextView(this);
        draftMsg.setTextColor(Ui.CARD_TEXT);
        draftMsg.setTextSize(14);
        card.addView(draftMsg);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.topMargin = Ui.dp(this, 12);
        card.addView(row, rp);

        Button recoverBtn = new Button(this);
        recoverBtn.setText("Recover");
        Ui.styleBtn(this, recoverBtn);
        recoverBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onDraftRecover(); }
        });
        row.addView(recoverBtn);

        Button discardBtn = new Button(this);
        discardBtn.setText("Discard");
        Ui.styleBtn(this, discardBtn);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = Ui.dp(this, 8);
        discardBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onDraftDiscard(); }
        });
        row.addView(discardBtn, dlp);
        return card;
    }

    private void checkDraft() {
        if (engine != null && engine.isRecording()) return;
        boolean inAppendMode = appendNoteId != -1
                || appendSectionId != -1
                || (appendSeed != null && !appendSeed.trim().isEmpty());
        if (inAppendMode) return;
        final DraftStore.Draft d = DraftStore.load(this);
        if (d == null) return;
        String when = android.text.format.DateFormat.getTimeFormat(this)
                .format(new java.util.Date(d.time));
        int words = NotesDb.countWords(d.text);
        draftMsg.setText("Found unsent dictation from " + when + " ("
                + words + (words == 1 ? " word" : " words")
                + "). Recover it before recording something new.");
        draftCard.setTag(d);
        draftCard.setVisibility(View.VISIBLE);
    }

    private void onDraftRecover() {
        DraftStore.Draft d = (DraftStore.Draft) draftCard.getTag();
        if (d == null) {
            draftCard.setVisibility(View.GONE);
            return;
        }
        String title = null;
        long recoverNoteId = -1;
        long recoverSectionId = -1;
        if (d.noteId <= -2) {
            // A section draft: the key is -(sectionId + 1).
            recoverSectionId = -d.noteId - 1;
            NotesDb db = new NotesDb(this);
            try {
                Section s = db.getSection(recoverSectionId);
                if (s != null) title = s.name;
            } catch (Exception e) {
                Log.w("MainActivity", "Suppressed exception", e);
            }
            try { db.close(); } catch (Exception e) {
                Log.w("MainActivity", "Suppressed exception", e);
            }
        } else if (d.noteId != -1) {
            recoverNoteId = d.noteId;
            NotesDb db = new NotesDb(this);
            try {
                Note n = db.get(d.noteId);
                if (n != null) title = n.title;
            } catch (Exception e) {
                Log.w("MainActivity", "Suppressed exception", e);
            }
            try { db.close(); } catch (Exception e) {
                Log.w("MainActivity", "Suppressed exception", e);
            }
        }
        appendNoteId = recoverNoteId;
        appendSectionId = recoverSectionId;
        appendSeed = d.text;
        appendTitle = title;
        draftCard.setVisibility(View.GONE);
        if (title != null && !title.trim().isEmpty()) {
            setStatus("Continuing \u201c" + title.trim()
                    + "\u201d. Tap to talk.");
        } else {
            setStatus("Recovered unsent dictation. Tap to talk.");
        }
        android.widget.Toast.makeText(this, "Recovered.",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void onDraftDiscard() {
        DraftStore.Draft d = (DraftStore.Draft) draftCard.getTag();
        DraftStore.clear(this, d != null ? d.noteId : -1);
        draftCard.setTag(null);
        draftCard.setVisibility(View.GONE);
    }

    /** Start a session: applies any append/recovery seed, hides the draft
     * offer (a new session's autosaves replace it). */
    private void beginSession() {
        askNotificationPermissionOnce();
        draftCard.setVisibility(View.GONE);
        if (appendSectionId != -1) {
            engine.setAppendSectionTarget(appendSectionId, appendSeed);
        } else if (appendNoteId != -1
                || (appendSeed != null && !appendSeed.trim().isEmpty())) {
            engine.setAppendTarget(appendNoteId, appendSeed);
        }
        // Pass waveform to the engine for real-time visualization.
        attachWaveform();
        engine.start();
    }

    /** Give the engine the current WaveformView. Must be called before
     * every engine.start() — including the auto-start after a permission
     * grant — or the waveform silently stays dead. */
    private void attachWaveform() {
        if (waveformView != null) {
            if (engine instanceof KrokoEngine) {
                ((KrokoEngine) engine).setWaveformView(waveformView);
            }
            waveformView.clear();
        }
    }

    /**
     * Android 13+ needs POST_NOTIFICATIONS before the recording
     * notification may appear. Ask once, at the first recording — never
     * at launch. (The foreground-service notification itself is exempt,
     * but the permission keeps every notification path working.)
     */
    private void askNotificationPermissionOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        // Already granted — nothing to do, and don't burn the one-time flag.
        if (checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        android.content.SharedPreferences prefs =
                getSharedPreferences("pour_pro", MODE_PRIVATE);
        // Only ever ask once per install. The flag is set BEFORE the
        // system dialog appears, so even if the activity is killed while
        // the dialog is up, we never prompt again. A permanent denial
        // ("Don't ask again") is covered too: the flag stays set, so we
        // never re-request.
        if (prefs.getBoolean("notif_asked", false)) return;
        prefs.edit().putBoolean("notif_asked", true).apply();
        requestPermissions(
                new String[]{
                        android.Manifest.permission.POST_NOTIFICATIONS},
                REQ_POST_NOTIF);
    }

    private void showPermNotice(boolean permanent) {
        if (permanent) {
            permMsg.setText("Microphone access is turned off for Pour, so I can't "
                    + "hear you. Tap Open Settings below, then switch the "
                    + "Microphone permission on.");
        } else {
            permMsg.setText("Pour needs microphone access to turn your speech "
                    + "into text. Allow it when the system asks, or open "
                    + "Settings to switch it on.");
        }
        permCard.setVisibility(View.VISIBLE);
        setStatus("Microphone permission is needed to dictate.");
    }

    private LinearLayout buildPermCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.card(this, 16));
        int p = Ui.dp(this, 16);
        card.setPadding(p, p, p, p);

        permMsg = new TextView(this);
        permMsg.setTextColor(Ui.CARD_TEXT);
        permMsg.setTextSize(14);
        card.addView(permMsg);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.topMargin = Ui.dp(this, 12);
        card.addView(row, rp);

        Button settingsBtn = new Button(this);
        settingsBtn.setText("Open Settings");
        Ui.styleBtn(this, settingsBtn);
        settingsBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openAppSettings(); }
        });
        row.addView(settingsBtn);

        Button retryBtn = new Button(this);
        retryBtn.setText("Ask again");
        Ui.styleBtn(this, retryBtn);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = Ui.dp(this, 8);
        retryBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                requestPermissions(
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        REQ_RECORD_AUDIO);
            }
        });
        row.addView(retryBtn, rlp);
        return card;
    }

    private void openAppSettings() {
        Intent i = new Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(android.net.Uri.fromParts("package", getPackageName(), null));
        try { startActivity(i); } catch (Exception e) {
            Log.w("MainActivity", "Suppressed exception", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.syncTheme(this)) return;
        // Re-apply dictation text size in case the setting changed.
        if (transcriptView != null) {
            transcriptView.setTextSize(16 * Ui.textScale(this));
        }
        // A session may have crashed while this screen sat in the
        // background — surface its draft if one appeared.
        checkDraft();
        // The user may have flipped the permission in Settings and come back.
        if (engine != null && engine.hasMicPermission()) {
            permCard.setVisibility(View.GONE);
        } else if (permCard.getVisibility() == View.VISIBLE) {
            showPermNotice(!shouldShowRequestPermissionRationale(
                    Manifest.permission.RECORD_AUDIO));
        }
    }

    private void updateRecordUi(boolean recording) {
        circleBtn.setRecording(recording);
        if (recording) {
            circleBtn.setContentDescription("Stop recording");
            recordLabel.setText("Recording\u2026 tap to stop");
        } else if (engine != null && engine.isPaused()) {
            // The session is alive with words in the buffer: the button
            // resumes, it doesn't start over.
            circleBtn.setContentDescription("Resume recording");
            recordLabel.setText("Paused. Tap to resume");
        } else {
            circleBtn.setContentDescription("Start recording");
            recordLabel.setText("Tap to talk");
        }
    }

    private void setStatus(String s) {
        statusView.setText(s);
    }

    private void updateCount() {
        int words = NotesDb.countWords(transcriptView.getText().toString());
        countView.setText(words + (words == 1 ? " word" : " words"));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                permCard.setVisibility(View.GONE);
                if (startAfterGrant || pendingAutostart) {
                    startAfterGrant = false;
                    pendingAutostart = false;
                    attachWaveform();
                    engine.start();
                } else {
                    updateRecordUi(engine.isRecording());
                }
            } else {
                pendingAutostart = false;
                startAfterGrant = false;
                updateRecordUi(false);
                // If the system won't show the dialog anymore, the user must
                // flip the toggle in Settings — say so plainly.
                showPermNotice(!shouldShowRequestPermissionRationale(
                        Manifest.permission.RECORD_AUDIO));
            }
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        RecordService.stop(this);
        if (engine != null) {
            if (engine instanceof KrokoEngine) {
                ((KrokoEngine) engine).clearWaveformView();
            }
            engine.destroy();
            engine = null;
        }
        super.onDestroy();
    }
}
