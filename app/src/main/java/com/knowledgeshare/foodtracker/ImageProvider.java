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

    /** Small JPEG copy of the current photo, shared to ChatGPT / Claude / Gemini apps. */
    public static final String SHARE_NAME = "food.jpg";

    public static File shareFile(android.content.Context ctx) {
        return new File(ctx.getCacheDir(), SHARE_NAME);
    }

    public static Uri shareUri() {
        return Uri.parse("content://" + AUTHORITY + "/" + SHARE_NAME);
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String seg = uri.getLastPathSegment();
        if (FILE_NAME.equals(seg)) return photoFile(getContext());
        if (SHARE_NAME.equals(seg)) return shareFile(getContext());
        throw new FileNotFoundException(uri.toString());
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
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
        File f;
        try { f = fileFor(uri); } catch (FileNotFoundException e) { return null; }
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
