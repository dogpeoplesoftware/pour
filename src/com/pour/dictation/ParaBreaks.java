package com.pour.dictation;

/**
 * Spoken formatting for writers: saying "new paragraph", "period",
 * "comma", and friends while dictating produces the real punctuation at
 * save time (and in the live display).
 *
 * This is the de facto dictation standard (period, comma, question mark,
 * exclamation point, colon, semicolon, quotes, new line, new paragraph).
 * The known tradeoff: a literal word can be rewritten ("the Jurassic
 * period ended" becomes "the Jurassic . ended"). Every major dictation
 * system accepts it, and fixing a stray mark is a quicker keyboard pass
 * than inserting every mark by hand — paragraph structure is still the
 * thing you can't reconstruct afterwards, especially dialogue.
 */
public class ParaBreaks {

    public static String apply(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        // Fast path: the live display calls this on every partial update,
        // so skip the regexes entirely when no command was spoken.
        String lower = trimmed.toLowerCase(java.util.Locale.US);
        if (!lower.contains("new paragraph") && !lower.contains("new line")
                && !lower.contains("period") && !lower.contains("comma")
                && !lower.contains("question mark")
                && !lower.contains("exclamation")
                && !lower.contains("colon") && !lower.contains("semicolon")
                && !lower.contains("quote")) {
            return trimmed;
        }
        String out = trimmed;
        // Multi-word commands first, so "new paragraph" wins over any
        // shorter match inside it.
        out = out.replaceAll("(?i)[ \\t]*\\bnew paragraph\\b[ \\t]*", "\n\n");
        out = out.replaceAll("(?i)[ \\t]*\\bnew line\\b[ \\t]*", "\n");
        out = out.replaceAll("(?i)[ \\t]*\\bquestion mark\\b[ \\t]*", "? ");
        out = out.replaceAll("(?i)[ \\t]*\\bexclamation point\\b[ \\t]*",
                "! ");
        out = out.replaceAll("(?i)[ \\t]*\\bexclamation mark\\b[ \\t]*",
                "! ");
        out = out.replaceAll("(?i)[ \\t]*\\bsemicolon\\b[ \\t]*", "; ");
        out = out.replaceAll("(?i)[ \\t]*\\bcolon\\b[ \\t]*", ": ");
        out = out.replaceAll("(?i)[ \\t]*\\bcomma\\b[ \\t]*", ", ");
        out = out.replaceAll("(?i)[ \\t]*\\bperiod\\b[ \\t]*", ". ");
        out = out.replaceAll("(?i)[ \\t]*\\bquotes\\b[ \\t]*", "\"");
        out = out.replaceAll("(?i)[ \\t]*\\bquote\\b[ \\t]*", "\"");
        // Tidy the spacing the replacements leave behind.
        out = out.replaceAll("[ \\t]+\\n", "\n")
                 .replaceAll("\\n[ \\t]+", "\n");
        out = out.replaceAll("\\n{3,}", "\n\n");
        out = out.replaceAll(" +([,.!?;:])", "$1");
        out = out.replaceAll("([,.!?;:]) ([,.!?;:])", "$1$2");
        return out.trim();
    }
}
