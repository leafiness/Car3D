package com.ljone.car3d;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 无依赖的文件提供者（不走 AndroidX/support）：只把缓存里的 update.apk 暴露给系统安装器。
 * 配合 android:grantUriPermissions=true 使用，ACTION_VIEW 安装时给系统读权限。
 */
public class UpdateFileProvider extends ContentProvider {
    static final String AUTH = "com.ljone.car3d.fileprovider";

    static Uri uri(android.content.Context c) { return Uri.parse("content://" + AUTH + "/update.apk"); }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = new File(getContext().getCacheDir(), "update.apk");
        if (!f.exists()) throw new FileNotFoundException("update.apk 不存在");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
}
