package com.sam.samba.debug;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

public class SambaInitProvider extends ContentProvider {

    private static final String TAG = "SambaDebug";

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) return false;

        // 只在 debug 包中启动（双重保险，防止误集成到 release）
        if (!isDebuggable(context)) {
            Log.w(TAG, "Not a debuggable build, Samba service skipped.");
            return false;
        }

        Log.i(TAG, "Debug build detected, starting Samba service...");
        startSambaService(context);
        return false; // 不需要返回 true，这个 Provider 不提供数据
    }

    private void startSambaService(Context context) {
        Intent intent = new Intent(context, SambaForegroundService.class);
        context.startForegroundService(intent);
    }

    private boolean isDebuggable(Context context) {
        return (context.getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    // ---- 以下方法全部空实现，这个 Provider 不提供任何数据 ----

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}