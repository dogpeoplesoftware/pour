package com.pour.dictation;

import android.content.Context;

/**
 * Theme presets: one palette per theme, persisted by id.
 *
 * Free: Dark (the app's original look, preserved exactly), Light.
 * Pro: True Black, Ember, Forest, Dusk, Paper, plus a user-built
 * Custom theme (background + text + accent from curated swatches,
 * the rest derived).
 *
 * All palette names are original/generic. Ui.java mirrors the current
 * preset into its static color fields via Ui.refresh(); every activity
 * calls that in onCreate/onResume so no hardcoded colors survive on
 * any user-visible surface.
 */
public final class Theme {

    public static final String KEY_THEME = "theme";

    /** Id of the user-built custom theme (pro-only). */
    public static final String CUSTOM_ID = "custom";
    private static final String KEY_CUSTOM_BG = "theme_custom_bg";
    private static final String KEY_CUSTOM_TEXT = "theme_custom_text";
    private static final String KEY_CUSTOM_ACCENT = "theme_custom_accent";

    /** Sensible default custom combo: the Dark look. */
    public static final int DEFAULT_CUSTOM_BG = 0xFF0A0A0C;
    public static final int DEFAULT_CUSTOM_TEXT = 0xFFF5F5F4;
    public static final int DEFAULT_CUSTOM_ACCENT = 0xFFFF7A59;

    public static final class Preset {
        public final String id;
        public final String name;
        public final boolean pro;
        public final boolean lightChrome;
        public final int bg;
        public final int card;
        public final int stroke;
        public final int text;
        public final int muted;
        public final int cardText;
        public final int cardMuted;
        public final int accent;
        public final int onAccent;
        public final int pressed;
        public final int highlight;

        Preset(String id, String name, boolean pro, boolean lightChrome,
               int bg, int card, int stroke, int text, int muted,
               int cardText, int cardMuted,
               int accent, int onAccent, int pressed, int highlight) {
            this.id = id;
            this.name = name;
            this.pro = pro;
            this.lightChrome = lightChrome;
            this.bg = bg;
            this.card = card;
            this.stroke = stroke;
            this.text = text;
            this.muted = muted;
            this.cardText = cardText;
            this.cardMuted = cardMuted;
            this.accent = accent;
            this.onAccent = onAccent;
            this.pressed = pressed;
            this.highlight = highlight;
        }
    }

    public static final Preset[] ALL = {
        // Pour Light: the default — paper cream, ink text, pour blue accent.
        new Preset("pour", "Pour Light", false, true,
                0xFFF3EBDD, 0xFFFFF8F0, 0xFFD6DEE3,
                0xFF0F1113, 0xFF6F8FA3,
                0xFF0F1113, 0xFF6F8FA3,
                0xFF0E6A8E, 0xFFF3EBDD,
                0xFFC9D9E2, 0x662E8FB7),
        // Pour Dark: dark gray with the same blue accent family.
        new Preset("pourdark", "Pour Dark", false, false,
                0xFF1A1F23, 0xFF232A30, 0xFF3A4A55,
                0xFFF3EBDD, 0xFF9CB4C1,
                0xFFF3EBDD, 0xFF9CB4C1,
                0xFF2E8FB7, 0xFF0F1113,
                0xFF2A343B, 0x664FA8C5),
        // Ember: the OG grey and orange — original Pour look.
        new Preset("ember", "Ember", false, false,
                0xFF0A0A0C, 0xFF151518, 0xFF2A2A30,
                0xFFF5F5F4, 0xFF9E9EA8,
                0xFFF5F5F4, 0xFF9E9EA8,
                0xFFFF7A59, 0xFF1C1C1E,
                0xFF232328, 0x66333333),
        // Sepia: warm manuscript paper, Highland-inspired.
        new Preset("sepia", "Sepia", true, true,
                0xFFF5E6C8, 0xFFFFF3DC, 0xFFD9C49A,
                0xFF3D2E1A, 0xFF8A7355,
                0xFF3D2E1A, 0xFF8A7355,
                0xFFB07A2A, 0xFFFFF3DC,
                0xFFE8D5A8, 0x66C49A3A),
        // Night: deep navy for late-night writing.
        new Preset("night", "Night", true, false,
                0xFF0A0E1A, 0xFF121828, 0xFF2A3550,
                0xFFE8ECF5, 0xFF8A94B0,
                0xFFE8ECF5, 0xFF8A94B0,
                0xFF4FA8C5, 0xFF0A0E1A,
                0xFF1A2338, 0x664FA8C5),
        // Forest: quiet green retreat.
        new Preset("forest", "Forest", true, false,
                0xFF0F1A14, 0xFF16241B, 0xFF2A4030,
                0xFFE8F0E8, 0xFF8AA895,
                0xFFE8F0E8, 0xFF8AA895,
                0xFF6BAF7A, 0xFF0F1A14,
                0xFF1E3024, 0x666BAF7A),
        // Terminal: retro computing — dull plastic, green CRT screen.
        new Preset("terminal", "Terminal", false, true,
                0xFFC2BBAE, 0xFF0D140D, 0xFF9A9186,
                0xFF1A1A1A, 0xFF6B6B62,
                0xFF33DD66, 0xFF1A8A3A,
                0xFF4A4A45, 0xFFF0EDE4,
                0xFFA8A094, 0x6633DD66),
    };

    private Theme() { }

    public static Preset byId(String id) {
        for (Preset p : ALL) {
            if (p.id.equals(id)) return p;
        }
        return ALL[0];
    }

    /** The user's saved custom colors: bg, text, accent. */
    public static int[] customColors(Context ctx) {
        android.content.SharedPreferences p = Ui.prefs(ctx);
        return new int[] {
            p.getInt(KEY_CUSTOM_BG, DEFAULT_CUSTOM_BG),
            p.getInt(KEY_CUSTOM_TEXT, DEFAULT_CUSTOM_TEXT),
            p.getInt(KEY_CUSTOM_ACCENT, DEFAULT_CUSTOM_ACCENT),
        };
    }

    public static void saveCustom(Context ctx, int bg, int text,
                                 int accent) {
        Ui.prefs(ctx).edit()
                .putInt(KEY_CUSTOM_BG, bg)
                .putInt(KEY_CUSTOM_TEXT, text)
                .putInt(KEY_CUSTOM_ACCENT, accent)
                .apply();
    }

    /** Linear mix of two ARGB colors: t=0 gives a, t=1 gives b. */
    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg2 = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg2 - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Relative luminance, 0 (black) to 1 (white). */
    static double luminance(int c) {
        double r = ((c >> 16) & 0xFF) / 255.0;
        double g = ((c >> 8) & 0xFF) / 255.0;
        double b = (c & 0xFF) / 255.0;
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        double hi = Math.max(la, lb), lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    /**
     * Muted (secondary) text: the text color faded toward the
     * background, but never past WCAG AA against the background.
     * On light backgrounds the fade backs off until 4.5:1 holds.
     */
    static int derivedMuted(int bg, int text) {
        int muted = mix(text, bg, 0.38f);
        for (float t = 0.34f;
             contrast(muted, bg) < 4.5 && t > 0.001f;
             t -= 0.04f) {
            muted = mix(text, bg, t);
        }
        return muted;
    }

    /**
     * Build the Custom preset from the user's three colors, deriving
     * the rest: card/stroke/pressed shift the background slightly
     * toward the text, muted fades the text toward the background,
     * and the accent-button text picks whichever of near-black /
     * near-white contrasts better. The curated swatches guarantee
     * WCAG AA text pairs by construction (text swatches adapt to the
     * background's luminance), and the derivation keeps the rest.
     */
    public static Preset customPreset(Context ctx) {
        int[] c = customColors(ctx);
        int bg = c[0], text = c[1], accent = c[2];
        int card = mix(bg, text, 0.07f);
        int stroke = mix(bg, text, 0.18f);
        int muted = derivedMuted(bg, text);
        int onAccent = contrast(accent, 0xFF1C1C1E)
                >= contrast(accent, 0xFFF5F5F4)
                ? 0xFF1C1C1E : 0xFFF5F5F4;
        int pressed = mix(bg, text, 0.10f);
        int highlight = (accent & 0x00FFFFFF) | 0x66000000;
        return new Preset(CUSTOM_ID, "Custom", true,
                luminance(bg) > 0.5,
                bg, card, stroke, text, muted, text, muted, accent, onAccent,
                pressed, highlight);
    }

    /** Resolve any theme id, including the user-built custom one. */
    public static Preset presetFor(Context ctx, String id) {
        if (CUSTOM_ID.equals(id)) return customPreset(ctx);
        return byId(id);
    }

    /** Every preset the picker shows: the fixed set plus Custom. */
    public static Preset[] pickerPresets(Context ctx) {
        Preset[] out = new Preset[ALL.length + 1];
        System.arraycopy(ALL, 0, out, 0, ALL.length);
        out[ALL.length] = customPreset(ctx);
        return out;
    }

    /**
     * Derived colors for a candidate combo, for the editor's live
     * preview: card, stroke, muted, onAccent. Same math as
     * customPreset, without building the whole preset.
     */
    static int[] previewDerived(int bg, int text, int accent) {
        int onAccent = contrast(accent, 0xFF1C1C1E)
                >= contrast(accent, 0xFFF5F5F4)
                ? 0xFF1C1C1E : 0xFFF5F5F4;
        return new int[] {
            mix(bg, text, 0.07f),
            mix(bg, text, 0.18f),
            derivedMuted(bg, text),
            onAccent,
        };
    }

    /** The raw persisted pick (what the user chose). On fresh installs with
     * no saved preference, follows the system theme: dark system -> Pour
     * Dark, light system -> Pour Light. Never overrides an existing pick. */
    public static String selectedId(Context ctx) {
        android.content.SharedPreferences prefs = Ui.prefs(ctx);
        if (prefs.contains(KEY_THEME)) {
            return prefs.getString(KEY_THEME, "pour");
        }
        // No saved preference: follow the system.
        int nightFlags = ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return nightFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
                ? "pourdark" : "pour";
    }

    /**
     * The effective preset. A pro theme picked while pro never renders
     * for a free-tier user: free falls back to Dark without touching
     * the stored pick, so flipping back to pro restores it. Custom
     * counts as pro.
     */
    public static Preset current(Context ctx) {
        Preset p = presetFor(ctx, selectedId(ctx));
        if (p.pro && !Pro.isPro(ctx)) return ALL[0];
        return p;
    }

    public static void setCurrent(Context ctx, String id) {
        String safe = CUSTOM_ID.equals(id) ? CUSTOM_ID : byId(id).id;
        Ui.prefs(ctx).edit().putString(KEY_THEME, safe).apply();
    }
}
