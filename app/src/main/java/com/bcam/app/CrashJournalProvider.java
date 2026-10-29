package com.bcam.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Local-only, bounded, privacy-safe diagnostic journal. No network transmission. */
public final class CrashJournalProvider extends ContentProvider {
    private static final Object LOCK = new Object();
    private static final int LIMIT_BYTES = 32768;
    private static volatile File journal;

    @Override public boolean onCreate() {
        if (getContext() == null) return false;
        journal = new File(getContext().getFilesDir(), "crash-journal.log");
        AppLog.INSTANCE.init(getContext());
        final Thread.UncaughtExceptionHandler prior = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            record("CRASH", error);
            if (prior != null) prior.uncaughtException(thread, error);
            else Process.killProcess(Process.myPid());
        });
        return true;
    }

    /** Only exception class and code location are persisted: never messages, call data or tokens. */
    public static void record(String level, Throwable error) {
        if (error == null || journal == null) return;
        String kind = error.getClass().getName();
        StackTraceElement[] stack = error.getStackTrace();
        String location = stack.length == 0 ? "unknown" :
                stack[0].getClassName() + "." + stack[0].getMethodName();
        append(level + " " + kind + " at " + location);
    }

    /** Record a pre-approved event identifier, not raw user data or exception messages. */
    public static void event(String level, String eventCode) {
        if (journal == null || eventCode == null) return;
        if (!eventCode.matches("[A-Za-z0-9_.-]{1,80}")) return;
        append(level + " " + eventCode);
    }

    private static void append(String line) {
        synchronized (LOCK) {
            try {
                File f = journal;
                if (f == null) return;
                String stamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date());
                String next = stamp + " " + line + "\n";
                if (f.length() > LIMIT_BYTES) {
                    byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
                    int from = Math.max(0, bytes.length - LIMIT_BYTES / 2);
                    while (from < bytes.length && bytes[from] != '\n') from++;
                    if (from < bytes.length) from++;
                    try (FileOutputStream truncate = new FileOutputStream(f, false)) {
                        truncate.write(bytes, from, bytes.length - from);
                    }
                }
                try (FileOutputStream stream = new FileOutputStream(f, true)) {
                    stream.write(next.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) { /* Diagnostics must never crash app. */ }
        }
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String order) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
