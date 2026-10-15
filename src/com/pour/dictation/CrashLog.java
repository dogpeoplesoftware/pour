package com.pour.dictation;

import android.content.Context;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CrashLog {
    private static final String PREFS = "pour_pro";
    private static final String PREF_ENABLED = "crash_log_enabled";
    private static final String LOG_FILE = "crash_log.txt";
    private static boolean installed = false;

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, false);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_ENABLED, enabled).apply();
        if (enabled && !installed) {
            install(ctx.getApplicationContext());
        }
    }

    public static synchronized void install(final Context appCtx) {
        if (installed) return;
        if (!isEnabled(appCtx)) return;
        installed = true;
        final Thread.UncaughtExceptionHandler defaultHandler =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(
                new Thread.UncaughtExceptionHandler() {
                    @Override
                    public void uncaughtException(Thread t, Throwable e) {
                        try { writeCrash(appCtx, t, e); } catch (Exception ignored) { }
                        if (defaultHandler != null) {
                            defaultHandler.uncaughtException(t, e);
                        }
                    }
                });
    }

    private static void writeCrash(Context ctx, Thread t, Throwable e) {
        try {
            File f = new File(ctx.getFilesDir(), LOG_FILE);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            StringBuilder sb = new StringBuilder();
            sb.append("=== Crash at ").append(sdf.format(new Date())).append(" ===\n");
            sb.append("Thread: ").append(t.getName()).append("\n");
            sb.append("App: ").append(ctx.getPackageName()).append("\n\n");
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            pw.flush();
            sb.append(sw.toString()).append("\n\n");
            boolean append = f.exists() && f.length() < 500000;
            FileWriter fw = new FileWriter(f, append);
            fw.write(sb.toString());
            fw.close();
        } catch (Exception ignored) { }
    }

    public static String getLog(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), LOG_FILE);
            if (!f.exists()) return "";
            java.util.Scanner s = new java.util.Scanner(f).useDelimiter("\\A");
            String content = s.hasNext() ? s.next() : "";
            s.close();
            return content;
        } catch (Exception e) { return ""; }
    }

    public static boolean hasLog(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), LOG_FILE);
            return f.exists() && f.length() > 0;
        } catch (Exception e) { return false; }
    }
}
