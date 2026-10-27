package com.pour.dictation;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Export a single piece as a file, using the exact process the
 * project page uses: a "Share as" format sheet (ProjectExporter
 * formats in their fixed order), then a destination sheet (save to
 * Downloads or share to another app as a real file). Shared by both
 * piece editors so a Bucket piece and a project piece export in
 * precisely the same way. The editors' simple Copy button is a
 * separate action and is not touched by this flow. */
public final class PieceExport {

    private PieceExport() { }

    public static void exportPiece(final Activity a, String title,
                                   String body) {
        final String text = body == null ? "" : body;
        if (text.trim().isEmpty()) {
            Toast.makeText(a, "Nothing to export.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String t = title == null ? "" : title.trim();
        final String docTitle = t.isEmpty() ? "Untitled piece" : t;

        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        final android.app.Dialog[] sheet = new android.app.Dialog[1];
        for (final ProjectExporter.Format f
                : ProjectExporter.Format.values()) {
            content.addView(Sheet.row(a,
                    f.label + " (." + f.ext + ")",
                    f.description,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            a.getSharedPreferences("pour_pro",
                                            Activity.MODE_PRIVATE)
                                    .edit()
                                    .putString("export_format", f.name())
                                    .apply();
                            if (sheet[0] != null) sheet[0].dismiss();
                            deliver(a, docTitle, text, f);
                        }
                    }));
        }
        sheet[0] = Sheet.show(a, "Share as", content);
    }

    /** One format picked: render once, then pick the destination. */
    private static void deliver(final Activity a, final String docTitle,
                                String body,
                                final ProjectExporter.Format f) {
        List<ProjectExporter.Entry> entries =
                new ArrayList<ProjectExporter.Entry>();
        entries.add(new ProjectExporter.Entry(null, "", body));
        final boolean isPdf = f == ProjectExporter.Format.PDF;
        final byte[] pdfBytes;
        final String text;
        if (isPdf) {
            pdfBytes = ProjectExporter.renderBytes(f, docTitle, entries);
            text = null;
            if (pdfBytes == null || pdfBytes.length == 0) {
                Toast.makeText(a, "Nothing to export.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            pdfBytes = null;
            text = ProjectExporter.render(f, docTitle, entries);
            if (text.isEmpty()) {
                Toast.makeText(a, "Nothing to export.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }
        final String base = ProjectExporter.sanitizeFileName(docTitle);
        ExportDelivery.askDestination(a,
                new Runnable() {
                    @Override public void run() {
                        if (isPdf) {
                            ExportDelivery.saveBytesToDownloads(
                                    a, f, pdfBytes, base);
                        } else {
                            ExportDelivery.saveToDownloads(a, f, text, base);
                        }
                    }
                },
                new Runnable() {
                    @Override public void run() {
                        if (isPdf) {
                            ExportDelivery.shareBytes(a, f, pdfBytes, base,
                                    "Share piece");
                        } else {
                            ExportDelivery.shareFile(a, f, text, base,
                                    "Share piece");
                        }
                    }
                });
    }
}
