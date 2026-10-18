package com.musicdownloader.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure decision layer for restoring interrupted mobile-download jobs.
 * This class never starts another server job: it only classifies persisted IDs.
 * The future background coordinator must reconcile each ID with the server
 * before attempting to transfer or publish a file.
 */
public final class MobileDownloadRecoveryPlan {
    public enum Action {
        QUERY_SERVER, VERIFY_LOCAL_FILE, KEEP_COMPLETED, NEEDS_USER_REVIEW
    }

    public static final class Item {
        public final String jobId;
        public final Action action;
        public final String reason;

        Item(String jobId, Action action, String reason) {
            this.jobId = jobId;
            this.action = action;
            this.reason = reason;
        }
    }

    private MobileDownloadRecoveryPlan() { }

    public static List<Item> fromJournal(List<MobileDownloadJournal.Entry> entries) {
        if (entries == null || entries.isEmpty()) return Collections.emptyList();
        List<Item> plan = new ArrayList<>();
        for (MobileDownloadJournal.Entry entry : entries) {
            if (entry == null || entry.jobId == null ||
                    !entry.jobId.matches("[A-Za-z0-9_-]{1,128}")) continue;
            String state = entry.state == null ? "" : entry.state.trim().toLowerCase(java.util.Locale.ROOT);
            Action action;
            String reason;
            switch (state) {
                case "completed":
                case "complete":
                    action = Action.KEEP_COMPLETED;
                    reason = "Already marked completed";
                    break;
                case "transferring":
                case "transferred":
                case "saving":
                    action = Action.QUERY_SERVER;
                    reason = "Reconcile interrupted transfer with original server job";
                    break;
                case "pending":
                case "preparing":
                case "downloading":
                case "ready":
                case "retry":
                    action = Action.QUERY_SERVER;
                    reason = "Reconcile existing job ID with Raspberry Pi";
                    break;
                case "failed":
                    action = Action.NEEDS_USER_REVIEW;
                    reason = "Failed job requires explicit retry";
                    break;
                default:
                    action = Action.NEEDS_USER_REVIEW;
                    reason = "Unknown persisted state; do not start a duplicate job";
            }
            plan.add(new Item(entry.jobId, action, reason));
        }
        return Collections.unmodifiableList(plan);
    }
}
