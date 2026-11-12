/*
 * حساباتي - مزوّد ملفات بسيط لتسليم ملف التحديث لبرنامج التثبيت في أندرويد 7 وأحدث.
 * © 2026 أحمد حسن عبدالله - جميع الحقوق محفوظة.
 */
package com.ahmedhassan.hesabaty;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public class ApkProvider extends ContentProvider {

    static String authority(Context c) { return c.getPackageName() + ".apkprovider"; }

    static Uri uriFor(Context c, String fileName) {
        return new Uri.Builder().scheme("content").authority(authority(c)).appendPath(fileName).build();
    }

    /** يسمح فقط بملفات التحديث داخل مجلد تنزيلات التطبيق */
    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("hesabaty-[0-9A-Za-z.]+\\.apk")) throw new FileNotFoundException("not allowed");
        File dir = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File f = new File(dir, name);
        if (!f.exists()) throw new FileNotFoundException(name);
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File f = fileFor(uri);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
