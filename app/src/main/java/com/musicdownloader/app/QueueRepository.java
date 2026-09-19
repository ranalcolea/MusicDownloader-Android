package com.musicdownloader.app;

import androidx.media3.common.MediaItem;

import java.util.ArrayList;
import java.util.List;

public final class QueueRepository {

    private static final List<MediaItem> queue =
            new ArrayList<>();

    private QueueRepository() {
    }

    public static synchronized void setQueue(
            List<MediaItem> items) {

        queue.clear();

        if (items != null) {
            queue.addAll(items);
        }
    }

    public static synchronized void addItems(
            List<MediaItem> items) {

        if (items != null) {
            queue.addAll(items);
        }
    }

    public static synchronized List<MediaItem> getQueue() {

        return new ArrayList<>(queue);
    }

    public static synchronized void clear() {

        queue.clear();
    }
}
