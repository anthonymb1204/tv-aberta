package com.vizzion.tvaberta;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

// Tells the home screen which channel was watched last, for its "Continuar" button:
// content://com.vizzion.tvaberta.last/channel -> one row with "name". Read-only, name only.
public class LastChannelProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[] {"name"});
        Channels.Channel last = Channels.last(getContext());
        if (last != null) c.addRow(new Object[] {last.name});
        return c;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
