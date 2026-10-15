package com.pour.dictation;

import android.content.Context;

/**
 * Picks the dictation engine: KrokoEngine (on-device, default) or
 * the framework SpeechRecognizer engine ("phone dictation").
 */
public class EngineFactory {

    /** Preference key: which engine to use. */
    private static final String PREF_ENGINE = "speech_engine";
    private static final String ENGINE_PHONE = "phone";

    /** Legacy: when true, use phone dictation. */
    private static final String PREF_PHONE_DICTATION = "phone_dictation";

    public static boolean usePhoneDictation(Context ctx) {
        if (ctx.getSharedPreferences("pour_pro",
                Context.MODE_PRIVATE).getBoolean(PREF_PHONE_DICTATION, false)) {
            return true;
        }
        return ENGINE_PHONE.equals(ctx.getSharedPreferences("pour_pro",
                Context.MODE_PRIVATE).getString(PREF_ENGINE, ""));
    }

    public static void setUsePhoneDictation(Context ctx, boolean usePhone) {
        ctx.getSharedPreferences("pour_pro", Context.MODE_PRIVATE)
                .edit().putString(PREF_ENGINE,
                        usePhone ? ENGINE_PHONE : "").apply();
    }

    public static Engine create(Context ctx, DictationEngine.Host host) {
        if (usePhoneDictation(ctx)) {
            return new DictationEngine(ctx, host);
        }
        // Default: Kroko (sherpa-onnx Zipformer).
        // Personal/free use only — CC-BY-SA, not commercially licensed.
        return new KrokoEngine(ctx, host);
    }
}
