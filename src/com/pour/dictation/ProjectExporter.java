package com.pour.dictation;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Renders a project (checked sections as titled text blocks) into
 * portable document formats. Plain text keeps the owner's approved layout
 * verbatim; Markdown, Fountain, and HTML are generated from the same
 * section model; PDF is rendered binary via PdfDocument. All formats
 * skip empty sections and respect the checked-sections filter the
 * caller applies. Implemented from the formats' public specs; no
 * third-party code. */
public final class ProjectExporter {

    private ProjectExporter() { }

    public enum Format {
        TXT("txt", "text/plain", "Plain text",
                "Simple text, pieces as uppercase headings"),
        FOUNTAIN("fountain", "text/plain", "Fountain",
                "Screenplay format, for screenwriting apps"),
        MD("md", "text/markdown", "Markdown",
                "Headings and paragraphs, for pieces and writing apps"),
        HTML("html", "text/html", "HTML",
                "Formatted page, for browsers and email"),
        PDF("pdf", "application/pdf", "PDF",
                "Print-ready document");

        public final String ext;
        public final String mime;
        public final String label;
        public final String description;

        Format(String ext, String mime, String label, String description) {
            this.ext = ext;
            this.mime = mime;
            this.label = label;
            this.description = description;
        }

        /** PDF is binary; the text formats are UTF-8 strings. Binary
         * formats need renderBytes() instead of render(), and don't
         * belong in the copy-to-clipboard picker. */
        public boolean isBinary() {
            return this == PDF;
        }

        public static Format fromName(String name, Format fallback) {
            if (name == null) return fallback;
            for (Format f : values()) {
                if (f.name().equals(name)) return f;
            }
            return fallback;
        }
    }

    /** One checked section: its body is the text block. The folder
     * name is null when the section sits loose at the top level. */
    public static final class Entry {
        public final String folderName;
        public final String sectionName;
        public final String body;
        public Entry(String folderName, String sectionName, String body) {
            this.folderName = folderName;
            this.sectionName = sectionName == null ? "" : sectionName;
            this.body = body == null ? "" : body;
        }
    }

    /** Collect the export model: checked, non-empty sections in
     * top-level order (folders with their sections, then loose
     * sections as they interleave). */
    public static List<Entry> collect(NotesDb db, long projectId,
                                     java.util.Set<Long> selected) {
        List<Entry> out = new ArrayList<Entry>();
        for (NotesDb.TopItem t : db.topLevelOrder(projectId)) {
            if (t.isFolder) {
                Folder f = db.getFolder(t.id);
                if (f == null) continue;
                for (Section s : db.sectionsInFolder(t.id)) {
                    if (selected.contains(s.id)
                            && !s.body.trim().isEmpty()) {
                        out.add(new Entry(f.name, s.name, s.body));
                    }
                }
            } else {
                Section s = db.getSection(t.id);
                if (s == null) continue;
                if (selected.contains(s.id) && !s.body.trim().isEmpty()) {
                    out.add(new Entry(null, s.name, s.body));
                }
            }
        }
        return out;
    }

    /** True when any entry sits inside a folder — the renderers then
     * use the deeper heading levels everywhere. */
    private static boolean hasFolders(List<Entry> entries) {
        for (Entry e : entries) {
            if (e.folderName != null) return true;
        }
        return false;
    }

    public static String render(Format f, String projectName,
                               List<Entry> entries) {
        switch (f) {
            case MD: return markdown(projectName, entries);
            case FOUNTAIN: return fountain(projectName, entries);
            case HTML: return html(projectName, entries);
            case PDF:
                // Binary format — callers must use renderBytes().
                throw new IllegalArgumentException(
                        "PDF is binary; use renderBytes()");
            case TXT:
            default: return plainText(projectName, entries);
        }
    }

    /** Render to bytes. Text formats encode as UTF-8; PDF renders
     * binary via android.graphics.pdf.PdfDocument. */
    public static byte[] renderBytes(Format f, String projectName,
                                     List<Entry> entries) {
        if (f == Format.PDF) return pdf(projectName, entries);
        String s = render(f, projectName, entries);
        try {
            return s.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s.getBytes();
        }
    }

    // ---------------- plain text (owner's approved layout, verbatim) ---

    private static String plainText(String projectName, List<Entry> entries) {
        StringBuilder sb = new StringBuilder(
                projectName == null ? "" : projectName);
        String lastFolder = "";
        boolean firstFolderSeen = false;
        for (Entry e : entries) {
            String fk = e.folderName == null ? "" : e.folderName;
            if (!fk.isEmpty()
                    && (!firstFolderSeen || !fk.equals(lastFolder))) {
                firstFolderSeen = true;
                lastFolder = fk;
                sb.append("\n\n").append(fk.toUpperCase(Locale.US));
            }
            sb.append("\n\n");
            if (!e.sectionName.isEmpty()) {
                sb.append(e.sectionName.toUpperCase(Locale.US))
                        .append("\n\n");
            }
            sb.append(e.body.trim());
        }
        return sb.toString().trim();
    }

    // ---------------- markdown -------------------------------------------

    private static String markdown(String projectName, List<Entry> entries) {
        boolean folders = hasFolders(entries);
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(mdInline(projectName)).append("\n");
        String lastFolder = "";
        boolean firstFolderSeen = false;
        for (Entry e : entries) {
            if (folders && e.folderName != null
                    && (!firstFolderSeen
                            || !e.folderName.equals(lastFolder))) {
                firstFolderSeen = true;
                lastFolder = e.folderName;
                sb.append("\n## ").append(mdInline(e.folderName))
                        .append("\n");
            }
            if (!e.sectionName.isEmpty()) {
                sb.append("\n").append(folders ? "### " : "## ")
                        .append(mdInline(e.sectionName)).append("\n");
            }
            String mbody = e.body.trim();
            if (!mbody.isEmpty()) {
                sb.append("\n").append(mdBody(mbody)).append("\n");
            }
        }
        return sb.toString().trim() + "\n";
    }

    /** Escape a leading # so body text never becomes a heading. */
    private static String mdInline(String s) {
        if (s == null) return "";
        s = s.trim().replace("\n", " ");
        if (s.startsWith("#")) s = "\\" + s;
        return s;
    }

    /** Blank-line-separated paragraphs; lone newlines become paragraph
     * breaks so dictated line breaks survive as visible breaks. */
    private static String mdBody(String body) {
        String[] paras = body.split("\\n\\s*\\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paras.length; i++) {
            if (i > 0) sb.append("\n\n");
            String p = paras[i].trim().replaceAll("(?<!\\n)\\n(?!\\n)",
                    "\n\n");
            // Re-split in case lone newlines became paragraph breaks.
            String[] sub = p.split("\\n\\s*\\n");
            for (int j = 0; j < sub.length; j++) {
                if (j > 0) sb.append("\n\n");
                String line = sub[j].trim();
                if (line.startsWith("#")) line = "\\" + line;
                sb.append(line);
            }
        }
        return sb.toString();
    }

    // ---------------- fountain -------------------------------------------
    // Per the fountain.io spec: title-page `Key:` block first, `#`
    // sections, `=` synopses, everything else action. Dictated prose is
    // never guessed as character/dialogue: an all-caps line with no blank
    // line after it would parse as Character + Dialogue, so such lines
    // are forced to action with `!` (stripped in formatted output).

    private static String fountain(String projectName, List<Entry> entries) {
        boolean folders = hasFolders(entries);
        StringBuilder sb = new StringBuilder();
        sb.append("Title: ").append(fInline(projectName)).append("\n\n");
        sb.append("Draft date: ")
                .append(DateFormat.getDateInstance(DateFormat.MEDIUM,
                        Locale.getDefault()).format(new Date()))
                .append("\n\n");
        String lastFolder = "";
        boolean firstFolderSeen = false;
        for (Entry e : entries) {
            if (folders && e.folderName != null
                    && (!firstFolderSeen
                            || !e.folderName.equals(lastFolder))) {
                firstFolderSeen = true;
                lastFolder = e.folderName;
                sb.append("# ").append(fInline(e.folderName))
                        .append("\n\n");
            }
            if (!e.sectionName.isEmpty()) {
                sb.append(folders ? "## " : "# ")
                        .append(fInline(e.sectionName)).append("\n\n");
            }
            String fbody = e.body.trim();
            if (!fbody.isEmpty()) {
                sb.append(fBody(fbody)).append("\n\n");
            }
        }
        return sb.toString().trim() + "\n";
    }

    private static String fInline(String s) {
        if (s == null) return "";
        return s.trim().replace("\n", " ");
    }

    private static String fBody(String body) {
        String[] paras = body.split("\\n\\s*\\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paras.length; i++) {
            if (i > 0) sb.append("\n\n");
            String[] lines = paras[i].split("\\n");
            for (int j = 0; j < lines.length; j++) {
                if (j > 0) sb.append("\n");
                sb.append(fActionLine(lines[j]));
            }
        }
        return sb.toString();
    }

    /** Force a line to action when Fountain would otherwise read it as
     * something else: an all-caps line with text after it parses as
     * Character + Dialogue, `#`/`=`/`>`/`~`/`/*` open sections, synopses,
     * transitions, lyrics, boneyards, and `.` + alphanumerics forces a
     * scene heading. The `!` is stripped in formatted output. */
    private static String fActionLine(String line) {
        String t = line.trim();
        if (t.isEmpty()) return line;
        if (isAllCapsLine(t) || t.startsWith("#") || t.startsWith("=")
                || t.startsWith(">") || t.startsWith("~")
                || t.startsWith("/*")
                || (t.startsWith(".") && t.length() > 1
                    && Character.isLetterOrDigit(t.charAt(1)))) {
            return "!" + line;
        }
        return line;
    }

    private static boolean isAllCapsLine(String p) {
        // Only single-line paragraphs can be misread as Character.
        if (p.contains("\n")) return false;
        boolean hasLetter = false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
                if (Character.isLowerCase(c)) return false;
            }
        }
        return hasLetter;
    }

    // ---------------- html -------------------------------------------------

    private static String html(String projectName, List<Entry> entries) {
        boolean folders = hasFolders(entries);
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width,"
                + " initial-scale=1\">\n<title>");
        sb.append(hEsc(projectName)).append("</title>\n<style>\n"
                + "body{font-family:Georgia,'Times New Roman',serif;"
                + "max-width:38em;margin:2em auto;padding:0 1em;"
                + "line-height:1.6;color:#1a1a1a;background:#fff}\n"
                + "h1{font-size:1.8em;line-height:1.2}\n"
                + "h2{font-size:1.4em;margin-top:2em;"
                + "border-bottom:1px solid #ddd;padding-bottom:.2em}\n"
                + "h3{font-size:1.15em;margin-bottom:.2em}\n"
                + "p{margin:.8em 0}\n"
                + "</style>\n</head>\n<body>\n");
        sb.append("<h1>").append(hEsc(projectName)).append("</h1>\n");
        String lastFolder = "";
        boolean firstFolderSeen = false;
        for (Entry e : entries) {
            if (folders && e.folderName != null
                    && (!firstFolderSeen
                            || !e.folderName.equals(lastFolder))) {
                firstFolderSeen = true;
                lastFolder = e.folderName;
                sb.append("<h2>").append(hEsc(e.folderName))
                        .append("</h2>\n");
            }
            if (!e.sectionName.isEmpty()) {
                sb.append(folders ? "<h3>" : "<h2>")
                        .append(hEsc(e.sectionName))
                        .append(folders ? "</h3>\n" : "</h2>\n");
            }
            String hbody = e.body.trim();
            if (!hbody.isEmpty()) {
                String[] paras = hbody.split("\\n\\s*\\n");
                for (String par : paras) {
                    String pp = par.trim().replace("\n", "<br>\n");
                    if (!pp.isEmpty()) {
                        sb.append("<p>").append(hEscPara(pp))
                                .append("</p>\n");
                    }
                }
            }
        }
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    private static String hEsc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Escape text but keep the <br> markers inserted for line breaks. */
    private static String hEscPara(String s) {
        String[] parts = s.split("<br>", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append("<br>");
            sb.append(hEsc(parts[i]));
        }
        return sb.toString();
    }

    // ---------------- pdf --------------------------------------------------
    // Binary format via android.graphics.pdf.PdfDocument (API 19+).
    // A4 pages, black text on white: project title, then each piece
    // as a bold heading over wrapped body text. Multi-page aware.

    private static final int PDF_PAGE_W = 595; // A4 width, points
    private static final int PDF_PAGE_H = 842; // A4 height, points
    private static final int PDF_MARGIN = 56;

    private static byte[] pdf(String projectName, List<Entry> entries) {
        android.graphics.pdf.PdfDocument doc =
                new android.graphics.pdf.PdfDocument();
        PdfWriter w = new PdfWriter(doc);
        android.graphics.Paint titlePaint = pdfPaint(18, true);
        android.graphics.Paint headPaint = pdfPaint(14, true);
        android.graphics.Paint bodyPaint = pdfPaint(12, false);

        String title = projectName == null ? "" : projectName.trim();
        if (!title.isEmpty()) {
            w.para(titlePaint, title, 12);
        }
        boolean folders = hasFolders(entries);
        String lastFolder = "";
        boolean firstFolderSeen = false;
        for (Entry e : entries) {
            if (folders && e.folderName != null
                    && (!firstFolderSeen
                            || !e.folderName.equals(lastFolder))) {
                firstFolderSeen = true;
                lastFolder = e.folderName;
                w.para(headPaint, e.folderName.trim(), 6);
            }
            if (!e.sectionName.isEmpty()) {
                w.para(headPaint, e.sectionName.trim(), 4);
            }
            String body = e.body.trim();
            if (!body.isEmpty()) {
                String[] paras = body.split("\\n\\s*\\n");
                for (String par : paras) {
                    // Lone newlines become spaces in print flow;
                    // blank lines mark paragraph breaks.
                    String flow =
                            par.trim().replaceAll("\\s*\\n\\s*", " ");
                    if (!flow.isEmpty()) {
                        w.para(bodyPaint, flow, 6);
                    }
                }
                w.gap(6);
            }
        }
        w.finish();
        java.io.ByteArrayOutputStream out =
                new java.io.ByteArrayOutputStream();
        try {
            doc.writeTo(out);
        } catch (Exception ex) {
            return new byte[0];
        } finally {
            doc.close();
        }
        return out.toByteArray();
    }

    private static android.graphics.Paint pdfPaint(float textSize,
                                                   boolean bold) {
        android.graphics.Paint p = new android.graphics.Paint();
        p.setColor(0xFF000000);
        p.setAntiAlias(true);
        p.setTextSize(textSize);
        p.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                bold ? android.graphics.Typeface.BOLD
                     : android.graphics.Typeface.NORMAL));
        return p;
    }

    /** Draws word-wrapped paragraphs onto PdfDocument pages, opening
     * a new page whenever the current one fills. */
    private static final class PdfWriter {
        private final android.graphics.pdf.PdfDocument doc;
        private android.graphics.pdf.PdfDocument.Page page;
        private android.graphics.Canvas canvas;
        private int pageNum = 0;
        private float y;

        PdfWriter(android.graphics.pdf.PdfDocument doc) {
            this.doc = doc;
            newPage();
            this.y = PDF_MARGIN;
        }

        private void newPage() {
            if (page != null) doc.finishPage(page);
            android.graphics.pdf.PdfDocument.PageInfo info =
                    new android.graphics.pdf.PdfDocument.PageInfo.Builder(
                            PDF_PAGE_W, PDF_PAGE_H, ++pageNum).create();
            page = doc.startPage(info);
            canvas = page.getCanvas();
        }

        void finish() {
            if (page != null) {
                doc.finishPage(page);
                page = null;
            }
        }

        /** Extra blank space after this paragraph. */
        void gap(float extra) {
            y += extra;
        }

        /** Draw one paragraph, word-wrapped to the margins. */
        void para(android.graphics.Paint paint, String text,
                  float afterSpace) {
            if (text == null) return;
            text = text.trim();
            if (text.isEmpty()) return;
            android.graphics.Paint.FontMetrics fm =
                    paint.getFontMetrics();
            float lineH = fm.descent - fm.ascent + fm.leading;
            float maxW = PDF_PAGE_W - 2 * PDF_MARGIN;
            float bottom = PDF_PAGE_H - PDF_MARGIN;
            for (String line : wrap(paint, text, maxW)) {
                if (y + lineH > bottom) {
                    newPage();
                    y = PDF_MARGIN;
                }
                canvas.drawText(line, PDF_MARGIN, y - fm.ascent, paint);
                y += lineH;
            }
            y += afterSpace;
        }

        /** Greedy word wrap; overlong words are hard-broken. */
        private static java.util.List<String> wrap(
                android.graphics.Paint paint, String text,
                float maxWidth) {
            java.util.List<String> lines =
                    new java.util.ArrayList<String>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split("\\s+")) {
                if (word.isEmpty()) continue;
                if (line.length() == 0) {
                    if (paint.measureText(word) <= maxWidth) {
                        line.append(word);
                    } else {
                        // Hard-break one overlong word.
                        int start = 0;
                        while (start < word.length()) {
                            int n = paint.breakText(word, start,
                                    word.length(), true, maxWidth,
                                    null);
                            if (n <= 0) n = 1;
                            lines.add(word.substring(start,
                                    start + n));
                            start += n;
                        }
                    }
                } else if (paint.measureText(line + " " + word)
                        <= maxWidth) {
                    line.append(' ').append(word);
                } else {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                }
            }
            if (line.length() > 0) lines.add(line.toString());
            if (lines.isEmpty()) lines.add("");
            return lines;
        }
    }

    // ---------------- filenames --------------------------------------------

    /** Make a project name safe as a filename. */
    public static String sanitizeFileName(String name) {
        if (name == null) name = "";
        String s = name.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]",
                "_");
        s = s.replaceAll("_+", "_").trim();
        if (s.length() > 60) s = s.substring(0, 60).trim();
        s = s.replaceAll("[_.]+$", "");
        if (s.isEmpty()) s = "project";
        return s;
    }
}
