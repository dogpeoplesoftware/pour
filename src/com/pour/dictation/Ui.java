package com.pour.dictation;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared styling. Colors are theme-driven: Ui.refresh() mirrors the
 * current Theme preset into these static fields. They start as Dark
 * (the original Pour look); every activity refreshes in onCreate and
 * onResume, so no user-visible surface keeps a stale or hardcoded
 * color. */
public class Ui {
    public static int BG = 0xFFF3EBDD;
    public static int CARD = 0xFFFFF8F0;
    public static int STROKE = 0xFFD6DEE3;
    public static int ACCENT = 0xFF0E6A8E;
    public static int ON_ACCENT = 0xFFF3EBDD;
    public static int TEXT = 0xFF0F1113;
    public static int MUTED = 0xFF6F8FA3;
    /** Text color for content on cards (may differ from TEXT when cards
     * contrast with the background, e.g. Terminal theme). */
    public static int CARD_TEXT = 0xFF0F1113;
    /** Muted text color for content on cards. */
    public static int CARD_MUTED = 0xFF6F8FA3;
    /** Pressed-state fill for selectable rows. */
    public static int PRESSED = 0xFFC9D9E2;
    /** Translucent highlight for search and read-along spans. */
    public static int HIGHLIGHT = 0x662E8FB7;

    /** Mirror the current theme preset into the static color fields. */
    public static void refresh(android.content.Context ctx) {
        Theme.Preset p = Theme.current(ctx);
        BG = p.bg;
        CARD = p.card;
        STROKE = p.stroke;
        TEXT = p.text;
        MUTED = p.muted;
        CARD_TEXT = p.cardText;
        CARD_MUTED = p.cardMuted;
        ACCENT = p.accent;
        ON_ACCENT = p.onAccent;
        PRESSED = p.pressed;
        HIGHLIGHT = p.highlight;
    }

    /** Subtle paper texture background for light themes. Returns null for dark themes. */
    public static android.graphics.drawable.Drawable texturedBackground(
            android.content.Context ctx) {
        Theme.Preset p = Theme.current(ctx);
        if (!p.lightChrome) return null; // Texture only makes sense on light themes
        android.graphics.drawable.Drawable[] layers = new android.graphics.drawable.Drawable[2];
        layers[0] = new android.graphics.drawable.ColorDrawable(BG);
        android.graphics.drawable.Drawable tex = null;
        try {
            tex = ctx.getResources().getDrawable(
                ctx.getResources().getIdentifier("paper_texture", "drawable",
                    ctx.getPackageName()));
        } catch (Exception ignored) { }
        if (tex == null) return layers[0];
        tex.setAlpha(20); // Very subtle (~8%)
        layers[1] = tex;
        return new android.graphics.drawable.LayerDrawable(layers);
    }

    private static final java.util.WeakHashMap<android.app.Activity, String>
            builtThemes =
            new java.util.WeakHashMap<android.app.Activity, String>();

    /** First lines of Activity.onCreate: load the theme and remember
     * which preset this instance was built with. */
    public static void initTheme(android.app.Activity a) {
        refresh(a);
        builtThemes.put(a, Theme.current(a).id);
    }

    /**
     * First line of Activity.onResume (and after any pro-override flip):
     * refresh colors, and recreate the activity if the effective theme
     * changed while it was alive or in the back stack. Returns true
     * when a recreate was triggered; the caller should return at once.
     */
    public static boolean syncTheme(android.app.Activity a) {
        refresh(a);
        String built = builtThemes.get(a);
        String now = Theme.current(a).id;
        if (built != null && !built.equals(now)) {
            a.recreate();
            return true;
        }
        return false;
    }

    /** App preferences: one store for the user's choices. */
    public static android.content.SharedPreferences prefs(
            android.content.Context c) {
        return c.getSharedPreferences("pour_pro",
                android.content.Context.MODE_PRIVATE);
    }

    /** Note-text scale from the Settings "Text size" choice. */
    public static float textScale(android.content.Context c) {
        String v = prefs(c).getString("text_size", "medium");
        if ("small".equals(v)) return 0.88f;
        if ("large".equals(v)) return 1.25f;
        return 1f;
    }

    /** Bucket sort order: "custom" (manual, default), "newest", or
     * "oldest". Migrates the old boolean sort_order pref. */
    public static String bucketSortMode(android.content.Context c) {
        String v = prefs(c).getString("sort_order", "custom");
        if ("newest".equals(v) || "oldest".equals(v) || "custom".equals(v)) {
            return v;
        }
        // Old boolean-style value.
        return "oldest".equals(v) ? "oldest" : "newest";
    }

    public static void setBucketSortMode(android.content.Context c,
                                         String mode) {
        prefs(c).edit().putString("sort_order", mode).apply();
    }

    /** Bucket sort order from Settings: newest first by default. */
    public static boolean newestFirst(android.content.Context c) {
        return !"oldest".equals(
                prefs(c).getString("sort_order", "newest"));
    }

    /** Keep the screen awake while dictating (Settings, default on). */
    public static boolean keepAwake(android.content.Context c) {
        return prefs(c).getBoolean("keep_awake", true);
    }

    public static int dp(Activity a, int dp) {
        return Math.round(dp * a.getResources().getDisplayMetrics().density);
    }

    /**
     * Universal safe margin: keeps content clear of the status bar, navigation
     * bar, and any display cutout (hole punch / notch) on every phone.
     * Newer Android versions draw apps edge-to-edge under the status bar, so we
     * pad the root view by the real system insets and forbid the window from
     * ever overlapping the cutout area.
     */
    public static void applySafeInsets(final Activity a, final View root,
                                       final int baseDp) {
        refresh(a);
        // Window chrome follows the theme: the background visible behind
        // the status bar, the status and navigation bar colors, and
        // dark/light status-bar icons for light themes.
        a.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(BG));
        a.getWindow().setStatusBarColor(BG);
        a.getWindow().setNavigationBarColor(BG);
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            android.view.View decor = a.getWindow().getDecorView();
            int vis = decor.getSystemUiVisibility();
            if (Theme.current(a).lightChrome) {
                decor.setSystemUiVisibility(vis
                        | android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            } else {
                decor.setSystemUiVisibility(vis
                        & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams lp =
                    a.getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams
                    .LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            a.getWindow().setAttributes(lp);
        }
        final int base = dp(a, baseDp);
        root.setOnApplyWindowInsetsListener(
                new View.OnApplyWindowInsetsListener() {
                    @Override public android.view.WindowInsets onApplyWindowInsets(
                            View v, android.view.WindowInsets insets) {
                        v.setPadding(base + insets.getSystemWindowInsetLeft(),
                                base + insets.getSystemWindowInsetTop(),
                                base + insets.getSystemWindowInsetRight(),
                                base + insets.getSystemWindowInsetBottom());
                        return insets;
                    }
                });
    }

    public static GradientDrawable card(Activity a, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(CARD);
        d.setCornerRadius(dp(a, radiusDp));
        d.setStroke(2, STROKE);
        return d;
    }

    /** Filled rounded rectangle in any color (e.g. the coral primary button). */
    public static GradientDrawable filled(Activity a, int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(a, radiusDp));
        return d;
    }

    /** Small dark card button. */
    public static void styleBtn(Activity a, Button b) {
        b.setBackground(card(a, 12));
        b.setTextColor(CARD_TEXT);
        b.setLetterSpacing(0.08f);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 12));
        // Kill the default elevation shadow: it's rectangular and peeks
        // out from behind the rounded corners.
        b.setElevation(0);
        b.setStateListAnimator(null);
    }

    /** Loud primary button: filled accent, for the upgrade CTA and
     * other primary actions. */
    public static void stylePrimaryBtn(Activity a, Button b) {
        b.setBackground(filled(a, ACCENT, 12));
        b.setTextColor(ON_ACCENT);
        // All-caps CTAs need air: 0.08em tracking (first-year typography).
        b.setLetterSpacing(0.08f);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 12));
        // Kill the default elevation shadow: it's rectangular and peeks
        // out from behind the rounded corners.
        b.setElevation(0);
        b.setStateListAnimator(null);
    }

    /** Pressed-state background for custom tappable rows (bottom sheets,
     * lists): transparent at rest, a subtle fill while pressed. */
    public static android.graphics.drawable.StateListDrawable selectable(
            Activity a) {
        android.graphics.drawable.StateListDrawable d =
                new android.graphics.drawable.StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(PRESSED);
        pressed.setCornerRadius(dp(a, 12));
        d.addState(new int[]{android.R.attr.state_pressed}, pressed);
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0x00000000);
        normal.setCornerRadius(dp(a, 12));
        d.addState(new int[]{}, normal);
        return d;
    }

    /** Unified piece-editor top bar: back | editable title | share |
     * overflow. Used by the Bucket note editor and the project section
     * editor so every piece looks and behaves the same. */
    public static LinearLayout editorTopBar(Activity a, EditText titleBox,
            View.OnClickListener onBack, View.OnClickListener onShare,
            View.OnClickListener onMore) {
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int s = dp(a, 48);
        ImageButton back = new ImageButton(a);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(TEXT);
        back.setBackground(selectable(a));
        back.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        back.setContentDescription("Back");
        back.setOnClickListener(onBack);
        bar.addView(back, new LinearLayout.LayoutParams(s, s));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(a, 4);
        tp.rightMargin = dp(a, 4);
        bar.addView(titleBox, tp);
        ImageButton share = new ImageButton(a);
        share.setImageResource(R.drawable.ic_export);
        share.setColorFilter(MUTED);
        share.setBackground(selectable(a));
        share.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        share.setContentDescription("Share");
        share.setOnClickListener(onShare);
        bar.addView(share, new LinearLayout.LayoutParams(s, s));
        ImageButton more = new ImageButton(a);
        more.setImageResource(R.drawable.ic_more);
        more.setColorFilter(MUTED);
        more.setBackground(selectable(a));
        more.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        more.setContentDescription("More actions");
        more.setOnClickListener(onMore);
        bar.addView(more, new LinearLayout.LayoutParams(s, s));
        return bar;
    }

    public static final int TAB_CAPTURE = 0;
    public static final int TAB_BIN = 1;
    public static final int TAB_PROJECTS = 2;

    /** Bottom Capture / Bin / Projects tab row. */
    public static LinearLayout tabRow(final Activity a, final int tab) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(a, 16);
        row.setLayoutParams(p);

        Button cap = new Button(a);
        cap.setText("Capture");
        Button nb = new Button(a);
        nb.setText("Bucket");
        Button ms = new Button(a);
        ms.setText("Projects");
        for (Button b : new Button[]{cap, nb, ms}) {
            b.setBackgroundColor(0x00000000);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            // Sentence case (the theme's default all-caps is off-brand
            // here) and real 48dp touch targets on primary nav.
            b.setAllCaps(false);
            b.setPadding(dp(a, 8), dp(a, 12), dp(a, 8), dp(a, 12));
        }
        cap.setTextColor(tab == TAB_CAPTURE ? ACCENT : MUTED);
        nb.setTextColor(tab == TAB_BIN ? ACCENT : MUTED);
        ms.setTextColor(tab == TAB_PROJECTS ? ACCENT : MUTED);
        cap.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (tab != TAB_CAPTURE) {
                    // Return to the capture screen wherever it lives in the
                    // stack, or start it fresh (e.g. from the splash screen).
                    Intent i = new Intent(a, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    a.startActivity(i);
                }
            }
        });
        nb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (tab != TAB_BIN) {
                    // Tabs are modes, not history: clear any stacked
                    // instances so Back exits instead of time-traveling.
                    Intent i = new Intent(a, LibraryActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    a.startActivity(i);
                }
            }
        });
        ms.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (tab == TAB_PROJECTS) return;
                Intent i = new Intent(a, ProjectsActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                a.startActivity(i);
            }
        });
        row.addView(cap);
        row.addView(nb);
        row.addView(ms);
        return row;
    }

    /** The one shared page header for every page: 24sp title left,
     * actions right — export icon, then 48dp muted icons with 8dp gaps.
     * Pass null for actions a page doesn't have (null title = icons only,
     * right-aligned, as on the splash). Gear opens Settings, ? opens the
     * help sheet. mock=true renders the locked-preview look: every action
     * is shown but dead — no click listeners, no content descriptions,
     * hidden from accessibility (the locked screen handles taps itself). */
    public static LinearLayout header(Activity a, String title,
            View.OnClickListener onExport,
            String addDesc, View.OnClickListener onAdd,
            boolean showSettings, boolean showHelp, boolean mock) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (title != null && !title.isEmpty()) {
            TextView titleView = new TextView(a);
            titleView.setText(title);
            titleView.setTextSize(24);
            titleView.setTextColor(TEXT);
            row.addView(titleView, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        } else {
            row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        }
        if (onExport != null || mock) {
            row.addView(headerIcon(a, R.drawable.ic_export,
                    mock ? null : "Share", mock ? null : onExport, mock));
        }
        if (addDesc != null || mock) {
            row.addView(headerIcon(a, R.drawable.ic_add,
                    mock ? null : addDesc, mock ? null : onAdd, mock));
        }
        if (showSettings) {
            final Activity act = a;
            row.addView(headerIcon(a, R.drawable.ic_gear,
                    mock ? null : "Settings",
                    mock ? null : new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            act.startActivity(new Intent(act,
                                    SettingsActivity.class));
                        }
                    }, mock));
        }
        if (showHelp) {
            final Activity act = a;
            row.addView(headerIcon(a, R.drawable.ic_help,
                    mock ? null : "How to use Pour",
                    mock ? null : new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            HelpSheet.show(act);
                        }
                    }, mock));
        }
        if (mock) {
            row.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        return row;
    }

    /** A standalone 48dp muted header icon button (gear/help style)
     * for custom headers that don't use Ui.header(). */
    public static ImageView headerIconButton(Activity a, int resId,
            String desc, View.OnClickListener onClick) {
        return headerIcon(a, resId, desc, onClick, false);
    }

    /** 48dp muted header icon with the 8dp gap, CENTER_INSIDE
     * (24dp glyphs render at intrinsic size). */
    /** Apply theme colors to a framework AlertDialog: rounded card
     * background, theme title/message text, accent buttons. Call after
     * show() — buttons only exist once the dialog is shown. Without
     * this, the dialog uses the system theme, which can clash with
     * the app theme (e.g. dark system dialog with light app text
     * colors, or vice versa). */
    public static void styleDialog(Activity a,
                                   android.app.AlertDialog d) {
        if (d == null || d.getWindow() == null) return;
        // Rounded background in the theme card color, so the dialog
        // always matches the current theme.
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(a, 16));
        d.getWindow().setBackgroundDrawable(bg);
        // Title and message use the theme text color. Note: AlertDialog's
        // internal title view id ("alertTitle") is not a public SDK
        // constant, so look it up by name. Fall back to android.R.id.title.
        int alertTitleId = a.getResources().getIdentifier(
                "alertTitle", "id", "android");
        TextView title = alertTitleId != 0
                ? d.findViewById(alertTitleId) : null;
        if (title == null) title = d.findViewById(android.R.id.title);
        if (title != null) title.setTextColor(CARD_TEXT);
        TextView msg = d.findViewById(android.R.id.message);
        if (msg != null) msg.setTextColor(CARD_TEXT);
        // Buttons use the theme accent color.
        Button pos = d.getButton(
                android.app.AlertDialog.BUTTON_POSITIVE);
        if (pos != null) pos.setTextColor(ACCENT);
        Button neg = d.getButton(
                android.app.AlertDialog.BUTTON_NEGATIVE);
        if (neg != null) neg.setTextColor(ACCENT);
        Button neu = d.getButton(
                android.app.AlertDialog.BUTTON_NEUTRAL);
        if (neu != null) neu.setTextColor(ACCENT);
    }

    private static ImageView headerIcon(Activity a, int resId, String desc,
            View.OnClickListener onClick, boolean mock) {
        ImageView b = new ImageView(a);
        b.setImageResource(resId);
        b.setColorFilter(MUTED);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int s = dp(a, 48);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(s, s);
        p.leftMargin = dp(a, 8);
        if (desc != null) b.setContentDescription(desc);
        if (onClick != null) b.setOnClickListener(onClick);
        if (mock) {
            b.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        b.setLayoutParams(p);
        return b;
    }
}
