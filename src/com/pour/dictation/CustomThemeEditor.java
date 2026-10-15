package com.pour.dictation;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Pro-only custom theme builder. The user picks three colors —
 * background, text, accent — from curated swatch sets; the app
 * derives the rest (card, stroke, muted, pressed, highlight,
 * status-bar style) with the same math as the fixed presets.
 *
 * The swatch sets adapt to the chosen background's luminance: text
 * swatches are near-white on dark backgrounds and near-black on
 * light ones, and accent swatches shift brighter/deeper to match.
 * Every text/background pair the UI allows stays at WCAG AA by
 * construction; there is no free hex picker.
 */
public final class CustomThemeEditor {

    /** Background swatches: darks first, then lights. */
    private static final int[] BG_SWATCHES = {
        0xFF0A0A0C, 0xFF000000, 0xFF1A120B, 0xFF0B1512, 0xFF141020,
        0xFF101A2E, 0xFF1E0F16, 0xFFFAFAF7, 0xFFF4ECDB, 0xFFFFFFFF,
    };

    /** Text swatches for dark backgrounds. */
    private static final int[] TEXT_ON_DARK = {
        0xFFF5F5F4, 0xFFFFFFFF, 0xFFF8F0E2,
        0xFFEFF7F0, 0xFFF4F0FB, 0xFFE8E4DA,
    };

    /** Text swatches for light backgrounds. */
    private static final int[] TEXT_ON_LIGHT = {
        0xFF191918, 0xFF000000, 0xFF3A2E21,
        0xFF1A2433, 0xFF2E1A33, 0xFF33302A,
    };

    /** Accent swatches for dark backgrounds (bright, vivid). */
    private static final int[] ACCENT_ON_DARK = {
        0xFFFF7A59, 0xFFFF9E4A, 0xFF7BC98A, 0xFFB79CFF,
        0xFF6EC1FF, 0xFFFF7AB8, 0xFFFFD166, 0xFF4FD1C5,
    };

    /** Accent swatches for light backgrounds (deeper, vivid). */
    private static final int[] ACCENT_ON_LIGHT = {
        0xFFBC421F, 0xFFB0532A, 0xFF2E7D4F, 0xFF6A4FC7,
        0xFF1B62D6, 0xFFB23A6F, 0xFF8A6D00, 0xFF0E7C7B,
    };

    private CustomThemeEditor() { }

    private static boolean bgIsLight(int bg) {
        return Theme.luminance(bg) > 0.5;
    }

    private static int[] textSwatches(int bg) {
        return bgIsLight(bg) ? TEXT_ON_LIGHT : TEXT_ON_DARK;
    }

    private static int[] accentSwatches(int bg) {
        return bgIsLight(bg) ? ACCENT_ON_LIGHT : ACCENT_ON_DARK;
    }

    private static boolean contains(int[] arr, int v) {
        for (int x : arr) if (x == v) return true;
        return false;
    }

    /**
     * Open the editor sheet. onDone runs after a successful Done tap
     * (combo persisted); the caller applies it (setCurrent + recreate).
     */
    public static void show(final Activity a, final Runnable onDone) {
        int[] saved = Theme.customColors(a);
        final int[] state = { saved[0], saved[1], saved[2] };

        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(a, 20);
        body.setPadding(pad, Ui.dp(a, 8), pad, pad);

        final LinearLayout previewHolder = new LinearLayout(a);
        previewHolder.setOrientation(LinearLayout.VERTICAL);
        body.addView(previewHolder);

        final LinearLayout[] bgGridHolder = new LinearLayout[1];
        final LinearLayout[] textGridHolder = new LinearLayout[1];
        final LinearLayout[] accentGridHolder = new LinearLayout[1];

        final Runnable[] refreshAll = new Runnable[1];
        refreshAll[0] = new Runnable() {
            @Override public void run() {
                buildPreview(a, previewHolder,
                        state[0], state[1], state[2]);
                body.removeView(bgGridHolder[0]);
                bgGridHolder[0] = swatchSection(a, "Background",
                        BG_SWATCHES, state, 0, refreshAll[0]);
                body.addView(bgGridHolder[0], 1);
                body.removeView(textGridHolder[0]);
                textGridHolder[0] = swatchSection(a, "Text",
                        textSwatches(state[0]), state, 1, refreshAll[0]);
                body.addView(textGridHolder[0], 2);
                body.removeView(accentGridHolder[0]);
                accentGridHolder[0] = swatchSection(a, "Accent",
                        accentSwatches(state[0]), state, 2, refreshAll[0]);
                body.addView(accentGridHolder[0], 3);
            }
        };

        bgGridHolder[0] = swatchSection(a, "Background", BG_SWATCHES,
                state, 0, refreshAll[0]);
        body.addView(bgGridHolder[0]);
        textGridHolder[0] = swatchSection(a, "Text",
                textSwatches(state[0]), state, 1, refreshAll[0]);
        body.addView(textGridHolder[0]);
        accentGridHolder[0] = swatchSection(a, "Accent",
                accentSwatches(state[0]), state, 2, refreshAll[0]);
        body.addView(accentGridHolder[0]);
        buildPreview(a, previewHolder, state[0], state[1], state[2]);

        // Bottom row: Reset left, Done right.
        LinearLayout buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bp =
                new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        TextView reset = new TextView(a);
        reset.setText("Reset");
        reset.setTextColor(Ui.MUTED);
        reset.setTextSize(14);
        reset.setPadding(Ui.dp(a, 4), Ui.dp(a, 16),
                Ui.dp(a, 4), Ui.dp(a, 16));
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                state[0] = Theme.DEFAULT_CUSTOM_BG;
                state[1] = Theme.DEFAULT_CUSTOM_TEXT;
                state[2] = Theme.DEFAULT_CUSTOM_ACCENT;
                refreshAll[0].run();
            }
        });
        buttons.addView(reset, bp);
        TextView done = new TextView(a);
        done.setText("Done");
        done.setTextSize(14);
        done.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        done.setGravity(Gravity.CENTER);
        GradientDrawable doneBg = new GradientDrawable();
        doneBg.setShape(GradientDrawable.RECTANGLE);
        doneBg.setCornerRadius(Ui.dp(a, 12));
        doneBg.setColor(Ui.ACCENT);
        done.setBackground(doneBg);
        done.setTextColor(Ui.ON_ACCENT);
        int dh = Ui.dp(a, 12), dw = Ui.dp(a, 28);
        done.setPadding(dw, dh, dw, dh);
        final Dialog[] sheet = new Dialog[1];
        done.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Theme.saveCustom(a, state[0], state[1], state[2]);
                if (sheet[0] != null) sheet[0].dismiss();
                onDone.run();
            }
        });
        buttons.addView(done);
        LinearLayout.LayoutParams blp =
                (LinearLayout.LayoutParams) buttons.getLayoutParams();
        if (blp == null) {
            blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        blp.topMargin = Ui.dp(a, 20);
        body.addView(buttons, blp);

        ScrollView scroll = new ScrollView(a);
        scroll.addView(body);
        sheet[0] = Sheet.show(a, "Custom theme", scroll);
    }

    /** One labeled swatch grid. slot: 0 = bg, 1 = text, 2 = accent. */
    private static LinearLayout swatchSection(final Activity a,
            String label, final int[] swatches, final int[] state,
            final int slot, final Runnable onPick) {
        LinearLayout section = new LinearLayout(a);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(a, 16);
        section.setLayoutParams(slp);
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextColor(Ui.MUTED);
        t.setTextSize(14);
        t.setPadding(0, 0, 0, Ui.dp(a, 8));
        section.addView(t);
        GridLayout grid = new GridLayout(a);
        grid.setColumnCount(5);
        for (final int color : swatches) {
            grid.addView(swatch(a, color,
                    state[slot] == color, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    state[slot] = color;
                    if (slot == 0) {
                        // Background changed: keep text/accent readable
                        // by snapping them into the matching sets.
                        if (!contains(textSwatches(color), state[1])) {
                            state[1] = textSwatches(color)[0];
                        }
                        if (!contains(accentSwatches(color), state[2])) {
                            state[2] = accentSwatches(color)[0];
                        }
                    }
                    onPick.run();
                }
            }));
        }
        section.addView(grid);
        return section;
    }

    /** One circular swatch; selected gets a high-contrast ring. */
    private static View swatch(Activity a, int color, boolean selected,
                               View.OnClickListener l) {
        int outer = Ui.dp(a, 48);
        int inner = Ui.dp(a, 38);
        android.widget.FrameLayout holder =
                new android.widget.FrameLayout(a);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        int m = Ui.dp(a, 5);
        lp.setMargins(m, m, m, m);
        holder.setLayoutParams(lp);
        holder.setMinimumWidth(outer);
        holder.setMinimumHeight(outer);
        View dot = new View(a);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(Ui.dp(a, selected ? 3 : 1),
                selected ? Ui.TEXT : Ui.STROKE);
        dot.setBackground(d);
        android.widget.FrameLayout.LayoutParams ip =
                new android.widget.FrameLayout.LayoutParams(
                        inner, inner);
        ip.gravity = Gravity.CENTER;
        dot.setLayoutParams(ip);
        holder.addView(dot);
        holder.setOnClickListener(l);
        return holder;
    }

    /** Live preview: a sample card in the candidate colors. */
    private static void buildPreview(Activity a, LinearLayout holder,
            int bg, int text, int accent) {
        holder.removeAllViews();
        int[] derived = Theme.previewDerived(bg, text, accent);
        int card = derived[0], stroke = derived[1],
            muted = derived[2], onAccent = derived[3];
        LinearLayout cardView = new LinearLayout(a);
        cardView.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setCornerRadius(Ui.dp(a, 16));
        cardBg.setColor(card);
        cardBg.setStroke(Ui.dp(a, 1), stroke);
        cardView.setBackground(cardBg);
        int p = Ui.dp(a, 16);
        cardView.setPadding(p, p, p, p);
        TextView title = new TextView(a);
        title.setText("Preview");
        title.setTextColor(text);
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        cardView.addView(title);
        TextView bodyT = new TextView(a);
        bodyT.setText("Your thoughts, turned to text.");
        bodyT.setTextColor(muted);
        bodyT.setTextSize(14);
        bodyT.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 12));
        cardView.addView(bodyT);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(a);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(accent);
        dot.setBackground(dotBg);
        int ds = Ui.dp(a, 16);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ds, ds);
        dlp.rightMargin = Ui.dp(a, 8);
        row.addView(dot, dlp);
        TextView pill = new TextView(a);
        pill.setText("Sample");
        pill.setTextSize(14);
        pill.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        pill.setTextColor(onAccent);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setShape(GradientDrawable.RECTANGLE);
        pillBg.setCornerRadius(Ui.dp(a, 16));
        pillBg.setColor(accent);
        pill.setBackground(pillBg);
        int ph = Ui.dp(a, 8), pw = Ui.dp(a, 16);
        pill.setPadding(pw, ph, pw, ph);
        row.addView(pill);
        cardView.addView(row);
        holder.addView(cardView);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = Ui.dp(a, 4);
        holder.setLayoutParams(hlp);
    }
}
