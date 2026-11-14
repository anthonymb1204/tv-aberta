package com.vizzion.tvaberta;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.pm.PackageManager;

// One APK for both: on a TV the remote-control screens, on a phone a portrait, touch-first layout.
final class Device {
    private Device() {}

    static boolean isTv(Context c) {
        UiModeManager m = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
        if (m != null && m.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return true;
        PackageManager pm = c.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }

    // Layout units: the TV screens are drawn on a 1920-wide grid; on a phone the same numbers become
    // density-independent sizes, a little over half as big (a 28-unit row text is about 15 dp).
    static float scale(Context c, boolean tv) {
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        return tv ? dm.widthPixels / 1920f : dm.density * 0.56f;
    }
}
