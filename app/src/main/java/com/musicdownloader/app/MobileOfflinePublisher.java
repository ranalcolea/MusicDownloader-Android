package com.musicdownloader.app;

import android.content.Context;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.IOException;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/** Publish a verified staged download to the user's SAF folder, keyed by job ID. */
public final class MobileOfflinePublisher {
    private MobileOfflinePublisher() { }

    public static void publish(Context context, MobileDownloadJournal.Entry entry, File staged)
            throws IOException {
        if (!staged.isFile() || staged.length() <= 0) throw new IOException("Missing staged audio");
        String uri = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("offline_folder_uri", "");
        if (uri.isEmpty()) throw new IOException("Offline folder not configured");
        DocumentFile folder = DocumentFile.fromTreeUri(context, Uri.parse(uri));
        if (folder == null || !folder.isDirectory() || !folder.canWrite())
            throw new IOException("Offline folder is unavailable");
        String format = "opus".equalsIgnoreCase(entry.format) ? "opus" : "mp3";
        String extension = "." + format;
        String title = entry.title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (title.isEmpty()) title = "Cancion";
        if (title.length() > 90) title = title.substring(0, 90);
        String filename = title + " [" + entry.jobId + "]" + extension;
        DocumentFile existing = folder.findFile(filename);
        if (existing != null && existing.isFile() && existing.length() == staged.length()) {
            registerOffline(context, existing, entry);
            return;
        }
        if (existing != null && !existing.delete()) throw new IOException("Cannot replace incomplete offline file");
        String mime = "opus".equals(format) ? "audio/ogg" : "audio/mpeg";
        DocumentFile target = folder.createFile(mime, filename);
        if (target == null) throw new IOException("Cannot create offline file");
        boolean success = false;
        try (FileInputStream input = new FileInputStream(staged);
             OutputStream output = context.getContentResolver().openOutputStream(target.getUri(), "w")) {
            if (output == null) throw new IOException("Cannot open offline output");
            byte[] buffer = new byte[65536];
            int n;
            while ((n = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");
                output.write(buffer, 0, n);
            }
            output.flush();
            success = true;
        } finally {
            if (!success) target.delete();
        }
        if (target.length() != staged.length()) {
            target.delete();
            throw new IOException("Offline file size mismatch");
        }
        registerOffline(context, target, entry);
    }
    /** Keep the existing Offline screen's library in sync with background downloads. */
    private static void registerOffline(Context context, DocumentFile file,
                                        MobileDownloadJournal.Entry entry) throws IOException {
        try {
            SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
            JSONArray library = new JSONArray(prefs.getString("offline_library", "[]"));
            String fileUri = file.getUri().toString();
            for (int i = 0; i < library.length(); i++) {
                JSONObject existing = library.optJSONObject(i);
                if (existing != null && fileUri.equals(existing.optString("uri"))) {
                    return;
                }
            }
            JSONObject track = new JSONObject();
            track.put("uri", fileUri);
            track.put("filename", file.getName() == null ? "" : file.getName());
            track.put("size", file.length());
            track.put("videoId", entry.videoId);
            track.put("title", entry.title == null ? "" : entry.title);
            track.put("artist", entry.artist);
            track.put("album", entry.album);
            track.put("duration", entry.duration);
            track.put("thumbnail", entry.thumbnail);
            library.put(track);
            if (!prefs.edit().putString("offline_library", library.toString()).commit()) {
                throw new IOException("Could not persist Offline library");
            }
        } catch (org.json.JSONException e) {
            throw new IOException("Could not register Offline track", e);
        }
    }

}
