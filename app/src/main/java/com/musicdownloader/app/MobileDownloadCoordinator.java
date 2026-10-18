package com.musicdownloader.app;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.util.List;
import org.json.JSONObject;

/** Single reconciler shared by the foreground service and JobScheduler fallback. */
public final class MobileDownloadCoordinator {
    private static final String TAG = "MobileDownloadV2";
    private static final Object LOCK = new Object();
    private MobileDownloadCoordinator() {}

    /** Returns true while pending jobs require a later retry. */
    public static boolean runOnce(Context suppliedContext) {
        synchronized (LOCK) {
            Context context = suppliedContext.getApplicationContext();
            boolean retry = false;
            try {
                // Application context supplied by the caller.
                String url = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                        .getString("server_url", "").trim();
                if (url.isEmpty()) {
                    retry = true;
                } else {
                    ApiClient api = new ApiClient(url);
                    MobileDownloadJournal journal = new MobileDownloadJournal(context);
                    List<MobileDownloadRecoveryPlan.Item> plan =
                            MobileDownloadRecoveryPlan.fromJournal(journal.list());
                    for (MobileDownloadRecoveryPlan.Item item : plan) {
                        if (item.action != MobileDownloadRecoveryPlan.Action.QUERY_SERVER) continue;
                        MobileDownloadJournal.Entry entry = null;
                        for (MobileDownloadJournal.Entry candidate : journal.list()) {
                            if (item.jobId.equals(candidate.jobId)) { entry = candidate; break; }
                        }
                        if (entry == null) continue;
                        File staging = new File(context.getFilesDir(), "mobile-download-staging");
                        File stagedFile = new File(staging, item.jobId + ".part");
                        if ("transferred".equals(entry.state) || "saving".equals(entry.state)) {
                            // A process restart or storage cleanup may have removed the
                            // staged file. Reconcile with the SAME server job rather than
                            // retrying publication of a nonexistent file forever.
                            // A nonempty .part is not proof of a completed transfer.
                            // Reconcile with the server and verify its total size before
                            // publishing; never promote an interrupted partial file.
                            journal.updateState(item.jobId, "ready");
                        }
                        try {
                            JSONObject response = api.getDownloadStatus(item.jobId);
                            String status = response.optString("status", "").trim();
                            Log.i(TAG, "Job " + item.jobId + " servidor=" + status);
                            if ("done".equals(status) || "completed".equals(status)) {
                                // Server file is ready, NOT yet transferred to the phone.
                                // Stage the completed file in private app storage. Publishing
                                // to the user's SAF folder is a separate, idempotent step.
                                journal.updateState(item.jobId, "ready");
                                try {
                                    Log.i(TAG, "Iniciando transferencia " + item.jobId);
                                    File downloaded = ResumableFileTransfer.fetch(
                                            api.getMobileDownloadUrl(item.jobId), staging, item.jobId);
                                    if (downloaded.isFile() && downloaded.length() > 0L) {
                                        Log.i(TAG, "Transferencia completa " + item.jobId);
                                        journal.updateState(item.jobId, "transferred");
                                        MobileDownloadJournal.Entry refreshed = null;
                                        for (MobileDownloadJournal.Entry candidate : journal.list()) {
                                            if (item.jobId.equals(candidate.jobId)) { refreshed = candidate; break; }
                                        }
                                        if (refreshed != null) {
                                            journal.updateState(item.jobId, "saving");
                                            MobileOfflinePublisher.publish(context, refreshed, downloaded);
                                            Log.i(TAG, "Guardado offline completo " + item.jobId);
                                            journal.updateState(item.jobId, "completed");
                                            downloaded.delete();
                                        }
                                    } else {
                                        retry = true;
                                    }
                                } catch (Exception transferFailure) {
                                    Log.e(TAG, "Error transfiriendo o guardando " + item.jobId, transferFailure);
                                    // Keep the partial file and the original job ID.
                                    boolean isStaged = false;
                                    for (MobileDownloadJournal.Entry candidate : journal.list()) {
                                        if (item.jobId.equals(candidate.jobId) &&
                                            ("transferred".equals(candidate.state) || "saving".equals(candidate.state))) {
                                            isStaged = true; break;
                                        }
                                    }
                                    if (!isStaged) journal.updateState(item.jobId, "ready");
                                    retry = true;
                                }
                            } else if ("error".equals(status) || "failed".equals(status)) {
                                journal.updateState(item.jobId, "failed");
                            } else if ("queued".equals(status) || "pending".equals(status)
                                    || "downloading".equals(status) || "processing".equals(status)
                                    || "running".equals(status)) {
                                journal.updateState(item.jobId, "preparing");
                                retry = true; // Revisit the same job; never start a duplicate.
                            } else if (status.isEmpty()) {
                                retry = true;
                            }
                        } catch (Exception failure) {
                            Log.e(TAG, "Error consultando " + item.jobId, failure);
                            retry = true;
                        }
                    }
                }
            } catch (Exception failure) {
                Log.e(TAG, "Error general de recuperación", failure);
                retry = true;
            }
            return retry;
        }
    }
}
