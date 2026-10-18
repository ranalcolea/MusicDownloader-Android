package com.musicdownloader.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-initiated ongoing offline downloads, independent of MainActivity. */
public final class MobileDownloadForegroundService extends Service {
    private static final String CHANNEL = "mobile_offline_download_v2";
    private static final int NOTIFICATION_ID = 12048;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean active;

    public static void start(Context context) {
        Intent intent = new Intent(context, MobileDownloadForegroundService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                new NotificationChannel(CHANNEL, "Descargas offline", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Music Downloader V2")
                .setContentText("Descargas offline en segundo plano")
                .setOngoing(true).setContentIntent(pending).build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification());
        if (!active) {
            active = true;
            executor.execute(() -> runDownloads());
        }
        return START_STICKY;
    }

    private void runDownloads() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "MusicDownloaderV2:OfflineTransfer");
        try {
            lock.acquire();
            while (!Thread.currentThread().isInterrupted()) {
                boolean retry = MobileDownloadCoordinator.runOnce(getApplicationContext());
                if (!retry) break;
                // Pending server preparation, temporary connectivity loss, or interrupted transfer.
                Thread.sleep(5000L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.e("MobileDownloadV2", "Fallo en servicio foreground", e);
            MobileDownloadRecoveryService.schedule(getApplicationContext());
        } finally {
            if (lock.isHeld()) lock.release();
            active = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    @Override public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
