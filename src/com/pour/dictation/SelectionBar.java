package com.pour.dictation;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Compact floating selection strip. Appears above the tab row while
 * items are checked and shows every bulk action as a text button, so
 * delete never looks like the only option.
 *
 * Layout: [X] [N selected] [action] [action]...
 * Destructive actions sort last and render in a quiet red.
 */
public final class SelectionBar {

    /** Destructive red: distinct from the coral accent, readable on
     * dark and light cards. */
    private static final int DESTRUCTIVE = 0xFFEF5350;

    /** One bulk action: a text button in the strip. */
    public interface Action {
        String label();
        /** Evaluated on every update (e.g. Group only when >= 2). */
        boolean visible();
        /** Destructive actions sort last and render red. */
        boolean destructive();
        void run();
    }

    private final Activity a;
    private final LinearLayout bar;
    private final TextView countView;
    private final LinearLayout actionsRow;
    private final List<Action> actions;
    private final Runnable onClear;

    public SelectionBar(Activity a, List<Action> actions,
                        Runnable onClear) {
        this.a = a;
        this.actions = actions;
        this.onClear = onClear;

        bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Ui.card(a, 16));
        bar.setElevation(Ui.dp(a, 8));
        int pad = Ui.dp(a, 4);
        bar.setPadding(pad, pad, pad, pad);
        bar.setVisibility(View.GONE);

        final int s48 = Ui.dp(a, 48);
        final int iconPad = Ui.dp(a, 13);

        ImageButton clear = new ImageButton(a);
        clear.setImageResource(R.drawable.ic_close);
        clear.setBackground(Ui.selectable(a));
        clear.setColorFilter(Ui.MUTED);
        clear.setContentDescription("Clear selection");
        clear.setLayoutParams(
                new LinearLayout.LayoutParams(s48, s48));
        clear.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        clear.setPadding(iconPad, iconPad, iconPad, iconPad);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onClear.run();
            }
        });
        bar.addView(clear);

        countView = new TextView(a);
        countView.setTextColor(Ui.CARD_MUTED);
        countView.setTextSize(13);
        countView.setSingleLine(true);
        LinearLayout.LayoutParams countParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        countParams.leftMargin = Ui.dp(a, 2);
        countParams.rightMargin = Ui.dp(a, 4);
        bar.addView(countView, countParams);

        actionsRow = new LinearLayout(a);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(actionsRow);
    }

    public View view() {
        return bar;
    }

    /** Refresh for the current selection count; hides when zero. */
    public void update(int count) {
        if (count == 0) {
            bar.setVisibility(View.GONE);
            return;
        }
        bar.setVisibility(View.VISIBLE);
        countView.setText(count + " selected");
        actionsRow.removeAllViews();
        // Safe actions first, destructive last.
        List<Action> ordered = new ArrayList<Action>();
        for (Action act : actions) {
            if (act.visible() && !act.destructive()) ordered.add(act);
        }
        for (Action act : actions) {
            if (act.visible() && act.destructive()) ordered.add(act);
        }
        for (final Action act : ordered) {
            actionsRow.addView(actionButton(act));
        }
    }

    private View actionButton(final Action act) {
        TextView b = new TextView(a);
        b.setText(act.label());
        b.setTextSize(14);
        b.setSingleLine(true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.selectable(a));
        b.setContentDescription(act.label());
        if (act.destructive()) {
            b.setTextColor(DESTRUCTIVE);
        } else {
            b.setTextColor(Ui.CARD_TEXT);
        }
        int hp = Ui.dp(a, 14);
        int vp = Ui.dp(a, 12);
        b.setPadding(hp, vp, hp, vp);
        b.setMinHeight(Ui.dp(a, 48));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                act.run();
            }
        });
        return b;
    }
}
