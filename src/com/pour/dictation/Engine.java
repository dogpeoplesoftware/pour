package com.pour.dictation;

/**
 * A dictation session driver. Implementations: DictationEngine (framework
 * SpeechRecognizer, works everywhere, no downloads) and KrokoEngine
 * (on-device sherpa-onnx Zipformer ASR, bundled model). Hosts talk only
 * to this interface.
 */
public interface Engine {
    /** False when this engine can't run (no recognizer / no model). */
    boolean isAvailable();
    boolean hasMicPermission();
    boolean isRecording();
    /** Begin a session. False when it couldn't start. */
    boolean start();
    /** Stop the session and save the note if there is one. */
    void stop();
    /**
     * Pause the session without saving: the recognizer stops, the words
     * stay in the transcript buffer and are flushed to the crash draft.
     * start() resumes from the paused point.
     */
    void pause();
    /** True after pause() until start() resumes. */
    boolean isPaused();
    /** Push the in-progress words to the crash draft immediately. */
    void flushDraft();
    /**
     * Append the next session to an existing note instead of creating a
     * new one. Call before start(). noteId -1 with non-empty existingText
     * seeds the session text but still saves as a new note (used to
     * recover an unsent draft).
     */
    void setAppendTarget(long noteId, String existingText);
    /**
     * Append the next session to a section's body instead of a note.
     * The section keeps its name; only the body and word count are
     * updated. Mutually exclusive with setAppendTarget: the last call
     * wins. Call before start().
     */
    void setAppendSectionTarget(long sectionId, String existingText);
    /** Host's onPause: release transient audio state. */
    void onHostPause();
    void destroy();
}
