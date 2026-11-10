package com.knowledgeshare.foodtracker;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Tiny file provider so the camera app can write the full-size photo into our cache folder. */
public class ImageProvider extends ContentProvider {

    public static final String AUTHORITY = "com.knowledgeshare.foodtracker.images";
    public static final String FILE_NAME = "photo.jpg";

    public static File photoFile(android.content.Context ctx) {
        return new File(ctx.getCacheDir(), FILE_NAME);
    }

    public static Uri photoUri() {
        return Uri.parse("content://" + AUTHORITY + "/" + FILE_NAME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!FILE_NAME.equals(uri.getLastPathSegment())) throw new FileNotFoundException(uri.toString());
        File f = photoFile(getContext());
        int pfdMode = ParcelFileDescriptor.parseMode(mode);
        if (mode.contains("w")) pfdMode |= ParcelFileDescriptor.MODE_CREATE;
        return ParcelFileDescriptor.open(f, pfdMode);
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f = photoFile(getContext());
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{FILE_NAME, f.length()});
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
