package com.pour.dictation;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Optional debug logging. Writes app activity to a file when enabled
 * in Settings. The user chooses to share the log; nothing is automatic. */
public class DebugLog {
    private static final String PREF_DEBUG = "debug_logging";
    private static File logFile;
    private static final Object LOCK = new Object();

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences("pour_pro", Context.MODE_PRIVATE)
                .getBoolean(PREF_DEBUG, false);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences("pour_pro", Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_DEBUG, enabled).apply();
        if (enabled) {
            init(ctx);
            log(ctx, "Debug logging enabled");
        }
    }

    private static void init(Context ctx) {
        synchronized (LOCK) {
            if (logFile == null) {
                File dir = new File(ctx.getFilesDir(), "logs");
                dir.mkdirs();
                logFile = new File(dir, "debug.log");
                // Fresh log on each enable
                if (logFile.exists()) logFile.delete();
            }
        }
    }

    public static void log(Context ctx, String msg) {
        if (!isEnabled(ctx)) return;
        synchronized (LOCK) {
            if (logFile == null) init(ctx);
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(
                        "HH:mm:ss.SSS", Locale.US);
                String ts = sdf.format(new Date());
                // Include calling method for context
                StackTraceElement[] st =
                        Thread.currentThread().getStackTrace();
                String caller = st.length > 4
                        ? st[4].getClassName() + "." + st[4].getMethodName()
                        : "unknown";
                FileWriter w = new FileWriter(logFile, true);
                w.write(ts + " [" + caller + "] " + msg + "\n");
                w.close();
            } catch (IOException e) {
                // Logging must never crash the app
            }
        }
    }

    public static File getLogFile(Context ctx) {
        synchronized (LOCK) {
            if (logFile == null) init(ctx);
            return logFile;
        }
    }
}
