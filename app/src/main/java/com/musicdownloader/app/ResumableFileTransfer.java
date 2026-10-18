package com.musicdownloader.app;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import android.util.Log;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V2 prototype: durable staging of a server-prepared audio file.
 * Does not modify the current Activity/download flow until integrated with a
 * persistent Android worker and the SAF final-file publishing step.
 */
public final class ResumableFileTransfer {
    private static final String TAG = "MobileDownloadV2";
    private static final Pattern UNSATISFIABLE_RANGE = Pattern.compile("bytes \\*/(\\d+)");
    private static final Pattern CONTENT_RANGE =
            Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");

    private ResumableFileTransfer() { }

    public static File fetch(String url, File stagingDirectory, String jobId)
            throws IOException {
        if (jobId == null || !jobId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IOException("Invalid job identifier");
        }
        if (!stagingDirectory.isDirectory() && !stagingDirectory.mkdirs()) {
            throw new IOException("Cannot create staging directory");
        }
        File partial = new File(stagingDirectory, jobId + ".part");
        return fetchAttempt(url, partial, true);
    }

    private static File fetchAttempt(String url, File partial, boolean allowRestart)
            throws IOException {
        long offset = partial.exists() ? partial.length() : 0L;
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(120000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
        try {
            int status = connection.getResponseCode();
            if (status == 416 && offset > 0) {
                // The local partial may already contain the entire file.
                // Verify against the server's advertised total before accepting it.
                String contentRange = connection.getHeaderField("Content-Range");
                if (contentRange != null && UNSATISFIABLE_RANGE.matcher(contentRange).matches()) {
                    long total = Long.parseLong(contentRange.substring(contentRange.indexOf('/') + 1));
                    if (total == offset) return partial;
                }
                if (allowRestart) {
                    connection.disconnect();
                    if (!partial.delete()) throw new IOException("Cannot discard obsolete partial file");
                    return fetchAttempt(url, partial, false);
                }
                throw new IOException("Partial file no longer matches server file (HTTP 416)");
            }
            if (status != 200 && status != 206) {
                throw new IOException("Download HTTP " + status);
            }
            long expectedTotal;
            if (status == 206) {
                Matcher m = CONTENT_RANGE.matcher(connection.getHeaderField("Content-Range") == null
                        ? "" : connection.getHeaderField("Content-Range"));
                if (!m.matches() || Long.parseLong(m.group(1)) != offset) {
                    // Do not append bytes belonging to a different or changed file.
                    throw new IOException("Unexpected Content-Range");
                }
                expectedTotal = Long.parseLong(m.group(3));
                long end = Long.parseLong(m.group(2));
                if (expectedTotal <= end || end < offset || expectedTotal <= 0) {
                    throw new IOException("Invalid response range");
                }
                long length = connection.getContentLengthLong();
                if (length >= 0 && length != end - offset + 1) {
                    throw new IOException("Mismatched response length");
                }
            } else {
                // A server that ignores Range must restart from byte zero.
                offset = 0;
                expectedTotal = connection.getContentLengthLong();
                if (expectedTotal < 0) throw new IOException("Missing Content-Length");
            }
            try (InputStream input = connection.getInputStream();
                 RandomAccessFile output = new RandomAccessFile(partial, "rw")) {
                if (offset == 0) output.setLength(0);
                else if (output.length() != offset) {
                    throw new IOException("Partial file changed during transfer");
                }
                output.seek(offset);
                byte[] buffer = new byte[64 * 1024];
                int lastLoggedPercent = -5;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Transfer interrupted");
                    }
                    output.write(buffer, 0, read);
                    if (expectedTotal > 0) {
                        int percent = (int) Math.min(100L, (output.getFilePointer() * 100L) / expectedTotal);
                        if (percent >= lastLoggedPercent + 5 || percent == 100) {
                            lastLoggedPercent = percent;
                            Log.i(TAG, "Fragmentos recibidos: " + percent + "% (" + output.getFilePointer() + "/" + expectedTotal + " bytes)");
                        }
                    }
                }
                output.getFD().sync();
                if (output.length() != expectedTotal) {
                    throw new IOException("Incomplete transfer: " + output.length()
                            + "/" + expectedTotal);
                }
            }
            return partial;
        } finally {
            connection.disconnect();
        }
    }
}
