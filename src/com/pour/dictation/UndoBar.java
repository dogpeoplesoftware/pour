package com.pour.dictation;

import android.util.Log;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * A one-action undo bar: the app's Snackbar, built on framework views
 * only (no Material dependency). Slides up above the bottom tab row,
 * holds for a few seconds, then slides away. Only one is ever visible;
 * showing a new one dismisses the old. The action runs on the UI thread.
 *
 * Single-action scope by design: each organizing action shows its own
 * bar, replacing any pending one.
 */
public class UndoBar {

    /** 5 seconds: long enough to catch a mis-tap (the bar appears
     * right where the user's eyes already are), short enough not to
     * loiter. Destructive actions already pass a confirm dialog, so
     * this undo is the second safety net, not the first. */
    public static final long DURATION_MS = 5000;

    public interface Action {
        void run();
    }

    private static WeakReference<View> currentBar = new WeakReference<>(null);
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Runnable pendingHide = null;

    public static void show(final Activity a, String message,
                            String buttonText, final Action onAction) {
        dismiss();
        if (a == null || a.isFinishing()) return;
        final ViewGroup decor =
                (ViewGroup) a.getWindow().getDecorView();

        final LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Ui.card(a, 16));
        int p = Ui.dp(a, 16);
        bar.setPadding(p, Ui.dp(a, 12), Ui.dp(a, 6), Ui.dp(a, 12));
        bar.setElevation(Ui.dp(a, 8));

        TextView msg = new TextView(a);
        msg.setText(message);
        msg.setTextColor(Ui.TEXT);
        msg.setTextSize(14);
        msg.setSingleLine(true);
        msg.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(msg, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button btn = new Button(a);
        btn.setText(buttonText);
        btn.setTextColor(Ui.ACCENT);
        btn.setTextSize(14);
        btn.setBackgroundColor(0x00000000);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setMinHeight(Ui.dp(a, 48));
        btn.setMinimumHeight(Ui.dp(a, 48));
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismiss();
                try {
                    onAction.run();
                } catch (Exception e) {
                    Log.w("UndoBar", "Suppressed exception", e);
                }
            }
        });
        bar.addView(btn);

        int navInset = 0;
        try {
            WindowInsets insets = decor.getRootWindowInsets();
            if (insets != null) {
                navInset = insets.getSystemWindowInsetBottom();
            }
        } catch (Exception e) {
            Log.w("UndoBar", "Suppressed exception", e);
        }
        // Float above the navigation bar and the app's own tab row.
        // Gravity.BOTTOM: the decor view is a FrameLayout whose default
        // gravity is TOP, which previously parked the bar in the status
        // bar. Pin it to the bottom explicitly.
        int bottomMargin = navInset + Ui.dp(a, 84);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        int side = Ui.dp(a, 16);
        lp.setMargins(side, 0, side, bottomMargin);
        decor.addView(bar, lp);
        currentBar = new WeakReference<View>(bar);

        // Live region: TalkBack announces the action and its undo.
        bar.setContentDescription(message + ". " + buttonText
                + " available.");
        bar.announceForAccessibility(message);

        final boolean reduceMotion = reduceMotion(a);
        if (!reduceMotion) {
            bar.setTranslationY(Ui.dp(a, 80));
            bar.setAlpha(0f);
            bar.animate().translationY(0f).alpha(1f)
                    .setDuration(200).start();
        }
        pendingHide = new Runnable() {
            @Override public void run() {
                hideAnimated(a, bar, reduceMotion);
            }
        };
        handler.postDelayed(pendingHide, DURATION_MS);
    }

    /** Shorthand for the standard undo bar. */
    public static void undo(Activity a, String message, Action onUndo) {
        show(a, message, "Undo", onUndo);
    }

    public static void dismiss() {
        if (pendingHide != null) {
            handler.removeCallbacks(pendingHide);
            pendingHide = null;
        }
        View bar = currentBar.get();
        if (bar != null) {
            try {
                ViewGroup parent = (ViewGroup) bar.getParent();
                if (parent != null) parent.removeView(bar);
            } catch (Exception e) {
                Log.w("UndoBar", "Suppressed exception", e);
            }
            currentBar.clear();
        }
    }

    private static void hideAnimated(final Activity a, final View bar,
                                     boolean reduceMotion) {
        pendingHide = null;
        if (currentBar.get() != bar) return;
        if (reduceMotion || a == null || a.isFinishing()) {
            dismiss();
            return;
        }
        bar.animate().translationY(Ui.dp(a, 80)).alpha(0f)
                .setDuration(180)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        dismiss();
                    }
                }).start();
    }

    /** True when the user asked the system to reduce motion. */
    public static boolean reduceMotion(Activity a) {
        try {
            float scale = android.provider.Settings.Global.getFloat(
                    a.getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f);
            return scale == 0f;
        } catch (Exception e) {
            return false;
        }
    }
}
