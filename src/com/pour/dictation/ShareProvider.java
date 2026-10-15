package com.pour.dictation;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Serves exported project files from the app's cache dir to share
 * targets. Framework-only (no AndroidX FileProvider in this project):
 * read-only, restricted to getCacheDir()/exports, canonical-path checked
 * so no path outside the exports dir can ever be served. */
public class ShareProvider extends ContentProvider {

    public static final String AUTHORITY =
            "com.pour.dictation.share";
    private static final String EXPORTS = "exports";
    private static final int CODE_FILE = 1;

    private static final UriMatcher MATCHER = new UriMatcher(
            UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(AUTHORITY, EXPORTS + "/*", CODE_FILE);
    }

    /** Content URI for a file already written into cache/exports. */
    public static Uri uriFor(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(EXPORTS).appendPath(f.getName()).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        if (MATCHER.match(uri) != CODE_FILE) {
            throw new FileNotFoundException("Unknown URI: " + uri);
        }
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("..") || name.contains("/")) {
            throw new FileNotFoundException("Bad name: " + uri);
        }
        try {
            File dir = new File(getContext().getCacheDir(), EXPORTS)
                    .getCanonicalFile();
            File f = new File(dir, name).getCanonicalFile();
            if (!f.getPath().startsWith(dir.getPath() + File.separator)) {
                throw new FileNotFoundException("Outside exports: " + uri);
            }
            if (!f.isFile()) throw new FileNotFoundException(
                    "Not a file: " + uri);
            return f;
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException("Bad URI: " + uri);
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Read-only: " + uri);
        }
        return ParcelFileDescriptor.open(fileFor(uri),
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null) return "application/octet-stream";
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".md")) return "text/markdown";
        return "text/plain";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            File f = fileFor(uri);
            MatrixCursor c = new MatrixCursor(new String[]{
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only");
    }
}
