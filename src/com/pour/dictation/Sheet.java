package com.pour.dictation;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A bottom sheet built on framework views only (no Material dependency):
 * a dialog pinned to the bottom edge with a rounded top, sliding up on
 * show. Primary actions live in the bottom third, in thumb reach, per
 * the one-handed ergonomics guidance.
 */
public class Sheet {

    /** Show a bottom sheet with a title and caller-built content. The
     * returned dialog can be dismissed by the caller. */
    public static Dialog show(Activity a, String title, View content) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.CARD);
        int r = Ui.dp(a, 20);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setStroke(2, Ui.STROKE);
        root.setBackground(bg);
        int p = Ui.dp(a, 20);
        root.setPadding(p, Ui.dp(a, 12), p, Ui.dp(a, 20));

        // The grabber: a bottom sheet should read as one at a glance.
        View grabber = new View(a);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Ui.STROKE);
        pill.setCornerRadius(Ui.dp(a, 3));
        grabber.setBackground(pill);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                Ui.dp(a, 40), Ui.dp(a, 5));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = Ui.dp(a, 12);
        root.addView(grabber, gp);

        if (title != null && !title.isEmpty()) {
            TextView t = new TextView(a);
            t.setText(title);
            t.setTextColor(Ui.TEXT);
            t.setTextSize(16);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            tp.bottomMargin = Ui.dp(a, 12);
            root.addView(t, tp);
        }

        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        d.setContentView(root);

        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.gravity = Gravity.BOTTOM;
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            w.setAttributes(lp);
        }
        d.setCanceledOnTouchOutside(true);
        d.show();

        // Slide up on show (skipped when the user asked the system to
        // reduce motion).
        if (!UndoBar.reduceMotion(a)) {
            root.setTranslationY(Ui.dp(a, 200));
            root.animate().translationY(0f).setDuration(220).start();
        }
        return d;
    }

    /** A single tappable row for a sheet list: full-width, 56dp tall,
     * title plus a muted subtitle. */
    public static View row(final Activity a, String title, String subtitle,
                           View.OnClickListener onTap) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int v = Ui.dp(a, 16);
        int h = Ui.dp(a, 4);
        row.setPadding(h, v, h, v);
        row.setMinimumHeight(Ui.dp(a, 56));
        row.setClickable(true);
        row.setFocusable(true);
        // A visible pressed state, since this isn't a framework button.
        row.setBackground(Ui.selectable(a));

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(16);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(a);
            s.setText(subtitle);
            // Use TEXT at 70% opacity for better contrast than MUTED on light themes.
            s.setTextColor((Ui.TEXT & 0x00FFFFFF) | 0xB3000000);
            s.setTextSize(14);
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(s);
        }
        row.setOnClickListener(onTap);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }
}
