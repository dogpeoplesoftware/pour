package com.pour.dictation;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The master checkbox row at the top of a selectable list: Bucket,
 * projects list, and project page all wear the same row. Tapping it
 * selects everything on the page, or clears the page when everything
 * is already selected; individual cards still toggle by hand and the
 * row re-syncs on every refresh, so its box never lies about the
 * state. Drawn with the card checkbox icons (ic_box) so it reads as
 * the same control, bigger.
 */
public final class SelectAllRow {

    private final LinearLayout row;
    private final ImageButton box;
    private final TextView label;

    public SelectAllRow(final Activity a, final Runnable onToggle) {
        row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        box = new ImageButton(a);
        box.setImageResource(R.drawable.ic_box);
        box.setBackground(Ui.selectable(a));
        box.setColorFilter(Ui.MUTED);
        box.setFocusable(false);
        box.setFocusableInTouchMode(false);
        int s = Ui.dp(a, 48);
        box.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        box.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = Ui.dp(a, 12);
        box.setPadding(pad, pad, pad, pad);
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onToggle.run();
            }
        });
        row.addView(box);

        label = new TextView(a);
        label.setText("Select all");
        label.setTextColor(Ui.MUTED);
        label.setTextSize(14);
        label.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onToggle.run();
            }
        });
        row.addView(label);
        row.setVisibility(View.GONE);
    }

    public View view() {
        return row;
    }

    /** Reflect the page's state: hidden when there is nothing to
     * select, checked only when every listed item is selected. */
    public void sync(Activity a, int total, int selectedCount) {
        if (total <= 0) {
            row.setVisibility(View.GONE);
            return;
        }
        row.setVisibility(View.VISIBLE);
        boolean all = selectedCount >= total;
        box.setImageResource(all ? R.drawable.ic_box_checked
                : R.drawable.ic_box);
        box.setColorFilter(all ? Ui.ACCENT : Ui.MUTED);
        box.setContentDescription(all ? "Deselect all"
                : "Select all");
        label.setText(all ? "Deselect all" : "Select all");
    }
}
