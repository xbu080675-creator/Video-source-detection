package com.xbu.videosourcedetection;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;

public final class ForegroundAppDetector {
    private final Context context;
    private final UsageStatsManager usageStatsManager;

    public ForegroundAppDetector(Context context) {
        this.context = context.getApplicationContext();
        this.usageStatsManager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
    }

    public boolean hasPermission() {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    public String getForegroundLabel() {
        if (!hasPermission() || usageStatsManager == null) return "未授权读取前台 App";
        long end = System.currentTimeMillis();
        UsageEvents events = usageStatsManager.queryEvents(end - 8000, end);
        UsageEvents.Event event = new UsageEvents.Event();
        String pkg = null;
        long latest = 0;
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            int type = event.getEventType();
            if ((type == UsageEvents.Event.MOVE_TO_FOREGROUND || type == UsageEvents.Event.ACTIVITY_RESUMED)
                    && event.getTimeStamp() >= latest) {
                latest = event.getTimeStamp();
                pkg = event.getPackageName();
            }
        }
        if (pkg == null) return "未知 App";
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg, 0);
            CharSequence label = context.getPackageManager().getApplicationLabel(info);
            return label + " · " + pkg;
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
