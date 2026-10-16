package com.pour.dictation;

import android.app.Activity;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The "How to use Pour" bottom sheet, shared by every main page's
 * header help icon. One copy of the content, opened from Capture,
 * Bucket, and Projects alike.
 */
public final class HelpSheet {

    private HelpSheet() { }

    public static void show(Activity a) {
        ScrollView scroll = new ScrollView(a);
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        String[][] sections = {
            {"Dictating",
             "Tap the big button and talk. Tap again to stop. Your words appear as you speak."},
            {"Punctuation",
             "Say punctuation out loud: \u201cperiod\u201d, \u201ccomma\u201d, \u201cquestion mark\u201d, \u201cnew paragraph\u201d."},
            {"Bucket",
             "Everything you dictate lands in the Bucket. Press and hold a card to lift it, then drag to reorder your cards, or drop it on the Trash bar to move it to trash (undo is in the bar). New pieces land on top. Use Sort to switch between Custom order, Newest first, and Oldest first. The three-dot menu on a card has Rename, Move to project, and Move to trash. Check cards to Move, Share, or trash several at once."},
            {"Projects",
             "File Bucket pieces into projects as pieces of writing. Check pieces to choose what Copy and Share include. On the Projects list, check projects to choose what Share includes, or to move several to trash at once. Press and hold a project to reorder it, or drop it on the Trash bar to move it to trash. The three-dot menu has Rename, Share, and Move to trash."},
            {"Pieces and folders",
             "A piece is a block of writing: tap it to dictate or type straight into it. A folder groups pieces and never holds text itself. Create either one with the + buttons at the top of a project, and group checked pieces into a folder any time. Press and hold a piece or folder to reorder it, or drop it on the Trash bar to move it to trash (undo is in the bar). The three-dot menu has the full options."},
            {"Share",
             "Tap Share, pick a format, then save it to Downloads or share it to another app. Formats: plain text, Markdown, Fountain, HTML, and PDF."},
            {"Backup",
             "Back up from Settings to keep a copy of everything."},
        };
        for (int i = 0; i < sections.length; i++) {
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) hp.topMargin = Ui.dp(a, 16);
            hp.bottomMargin = Ui.dp(a, 2);
            TextView h = new TextView(a);
            h.setText(sections[i][0]);
            h.setTextColor(Ui.CARD_TEXT);
            h.setTextSize(14);
            h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            content.addView(h, hp);

            TextView b = new TextView(a);
            b.setText(sections[i][1]);
            b.setTextColor(Ui.CARD_MUTED);
            b.setTextSize(14);
            content.addView(b);
        }
        scroll.addView(content);
        Sheet.show(a, "How to use Pour", scroll);
    }
}
