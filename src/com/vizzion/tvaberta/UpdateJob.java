package com.vizzion.tvaberta;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

// Checks for a new version in the background (every 6 hours, only with internet, even with the app
// closed) and, once per version, puts "Nova versão disponível" in the notification bar. Tapping it
// opens the app on the update window.
public class UpdateJob extends JobService {
    private static final int JOB = 1601;
    private static final String CHANNEL = "atualizacoes";
    static final String EXTRA_SHOW_UPDATE = "show_update";

    // Called each time the app opens: (re)schedules the check if it isn't already.
    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB) return;
            js.schedule(new JobInfo.Builder(JOB, new ComponentName(c, UpdateJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(6 * 3600_000L)
                    .setPersisted(true) // survives restarts (RECEIVE_BOOT_COMPLETED)
                    .build());
        } catch (Exception ignored) {}
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            Updater.Release r = Updater.check(this, true);
            if (r != null && !Updater.notified(this, r.version)) {
                notify(this, r.version);
                Updater.markNotified(this, r.version);
            }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // try again later
    }

    static void notify(Context c, String version) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
                new NotificationChannel(CHANNEL, "Atualizações do TV Aberta", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(c, MainActivity.class).putExtra(EXTRA_SHOW_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(c.getResources().getIdentifier("ic_notify", "drawable", c.getPackageName()))
                .setContentTitle("Nova versão do TV Aberta: " + version)
                .setContentText("Toque para ver as novidades e atualizar.")
                .setContentIntent(tap)
                .setAutoCancel(true);
        try { nm.notify(JOB, b.build()); } catch (Exception ignored) {} // no permission to notify: the app still shows it
    }
}
