package com.musicdownloader.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Recovery fallback: foreground service owns normal transfers. */
public final class MobileDownloadRecoveryService extends JobService {
    private static final int JOB_ID = 12047;
    private ExecutorService worker;
    public static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        JobInfo info = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, MobileDownloadRecoveryService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setBackoffCriteria(30000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build();
        scheduler.schedule(info);
    }
    @Override public boolean onStartJob(JobParameters params) {
        worker = Executors.newSingleThreadExecutor();
        worker.execute(() -> {
            boolean retry = MobileDownloadCoordinator.runOnce(getApplicationContext());
            Log.i("MobileDownloadV2", "Recuperación JobScheduler terminada. Reintento=" + retry);
            jobFinished(params, retry);
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        if (worker != null) worker.shutdownNow();
        return true;
    }
}
