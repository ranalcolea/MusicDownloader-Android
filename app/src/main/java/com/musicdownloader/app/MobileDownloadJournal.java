package com.musicdownloader.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Durable registry for Raspberry Pi mobile-download jobs.
 * Prototype infrastructure: not yet wired into MainActivity or a worker.
 * All mutations are synchronized and committed synchronously to disk.
 */
public final class MobileDownloadJournal {
    private static final String PREFS = "mobile_download_journal_v2";
    private static final String KEY = "jobs";
    private final SharedPreferences preferences;

    public MobileDownloadJournal(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static final class Entry {
        public final String jobId;
        public final String state;
        public final String title;
        public final String format;
        public final long updatedAt;
        public final String artist, album, thumbnail, videoId;
        public final int duration;

        private Entry(String jobId, String state, String title,
                      String format, long updatedAt, String artist, String album,
                      String thumbnail, String videoId, int duration) {
            this.jobId = jobId;
            this.state = state;
            this.title = title;
            this.format = format;
            this.updatedAt = updatedAt;
            this.artist = artist; this.album = album;
            this.thumbnail = thumbnail; this.videoId = videoId; this.duration = duration;
        }
    }

    public synchronized void put(String jobId, String state,
                                 String title, String format) throws JSONException {
        put(jobId, state, title, format, "", "", "", "", 0);
    }

    public synchronized void put(String jobId, String state, String title, String format,
                                 String artist, String album, String thumbnail,
                                 String videoId, int duration) throws JSONException {
        validate(jobId);
        JSONArray jobs = read();
        JSONArray next = new JSONArray();
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject existing = jobs.optJSONObject(i);
            if (existing != null && !jobId.equals(existing.optString("jobId"))) {
                next.put(existing);
            }
        }
        JSONObject item = new JSONObject();
        item.put("jobId", jobId);
        item.put("state", state == null ? "pending" : state);
        item.put("title", title == null ? "" : title);
        item.put("format", format == null ? "" : format);
        item.put("artist", artist == null ? "" : artist);
        item.put("album", album == null ? "" : album);
        item.put("thumbnail", thumbnail == null ? "" : thumbnail);
        item.put("videoId", videoId == null ? "" : videoId);
        item.put("duration", Math.max(0, duration));
        item.put("updatedAt", System.currentTimeMillis());
        next.put(item);
        save(next);
    }

    public synchronized void updateState(String jobId, String state) throws JSONException {
        validate(jobId);
        JSONArray jobs = read();
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject item = jobs.optJSONObject(i);
            if (item != null && jobId.equals(item.optString("jobId"))) {
                item.put("state", state);
                item.put("updatedAt", System.currentTimeMillis());
                save(jobs);
                return;
            }
        }
        throw new IllegalArgumentException("Unknown job: " + jobId);
    }

    public synchronized List<Entry> list() throws JSONException {
        JSONArray jobs = read();
        List<Entry> result = new ArrayList<>();
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject item = jobs.optJSONObject(i);
            if (item != null) {
                result.add(new Entry(item.optString("jobId"),
                        item.optString("state"), item.optString("title"),
                        item.optString("format"), item.optLong("updatedAt"),
                        item.optString("artist"), item.optString("album"),
                        item.optString("thumbnail"), item.optString("videoId"),
                        item.optInt("duration")));
            }
        }
        return result;
    }

    public synchronized void remove(String jobId) throws JSONException {
        validate(jobId);
        JSONArray jobs = read();
        JSONArray next = new JSONArray();
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject item = jobs.optJSONObject(i);
            if (item != null && !jobId.equals(item.optString("jobId"))) {
                next.put(item);
            }
        }
        save(next);
    }

    private JSONArray read() throws JSONException {
        return new JSONArray(preferences.getString(KEY, "[]"));
    }

    private void save(JSONArray value) {
        if (!preferences.edit().putString(KEY, value.toString()).commit()) {
            throw new IllegalStateException("Could not persist download journal");
        }
    }

    private static void validate(String jobId) {
        if (jobId == null || !jobId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid job identifier");
        }
    }
}
