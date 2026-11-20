package dev.herdr.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * Hands the camera app one private capture file (see {@link CaptureFiles}). Not exported: it is
 * reachable only through a URI this app grants for a single capture.
 */
public final class CaptureProvider extends ContentProvider {
    @Override public boolean onCreate() {
        return true;
    }

    private File file(Uri uri) throws FileNotFoundException {
        File cache = getContext() == null ? null : getContext().getCacheDir();
        File file = cache == null ? null : CaptureFiles.resolve(cache, uri.getLastPathSegment());
        if (file == null) throw new FileNotFoundException("no such capture");
        return file;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = file(uri);
        if (!file.isFile()) throw new FileNotFoundException("no such capture");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        try {
            File file = file(uri);
            MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            cursor.addRow(new Object[] {file.getName(), file.length()});
            return cursor;
        } catch (FileNotFoundException none) {
            return null;
        }
    }

    @Override public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read and write only");
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
