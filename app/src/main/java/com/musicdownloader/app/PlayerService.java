package com.musicdownloader.app;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;

public class PlayerService extends MediaSessionService {

    public static final String COMMAND_PLAY_QUEUE =
            "com.musicdownloader.app.PLAY_QUEUE";

    public static final String COMMAND_ADD_QUEUE =
            "com.musicdownloader.app.ADD_QUEUE";

    public static final String COMMAND_INSERT_QUEUE =
            "com.musicdownloader.app.INSERT_QUEUE";

    private ExoPlayer player;
    private MediaSession mediaSession;

    // RECUPERACION_COBERTURA_V1
    private final android.os.Handler networkRecoveryHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private android.net.ConnectivityManager networkManager;
    private android.net.ConnectivityManager.NetworkCallback networkCallback;

    private boolean networkRecoveryPending = false;
    private int networkRecoveryAttempts = 0;
    private long networkRecoveryPosition = 0L;
    private String networkRecoveryUri = "";

    private final Runnable networkRecoveryTask = () -> {
        if (!networkRecoveryPending || player == null) return;

        if (!player.getPlayWhenReady()) {
            cancelNetworkRecovery();
            return;
        }

        MediaItem current = player.getCurrentMediaItem();
        if (current == null || current.localConfiguration == null) {
            cancelNetworkRecovery();
            return;
        }

        String uri = current.localConfiguration.uri.toString();

        if (!uri.equals(networkRecoveryUri)) {
            cancelNetworkRecovery();
            return;
        }

        if (!hasInternetConnection()) return;

        if (networkRecoveryAttempts >= 5) {
            android.util.Log.w(
                    "MusicDownloaderPlayer",
                    "Esperando nueva conexión tras 5 intentos"
            );
            return;
        }

        networkRecoveryAttempts++;

        android.util.Log.i(
                "MusicDownloaderPlayer",
                "RECUPERACION intento=" + networkRecoveryAttempts
                        + " posicion=" + networkRecoveryPosition
        );

        // Se conserva la misma cola y el mismo MediaItem.
        player.seekTo(networkRecoveryPosition);
        player.prepare();
        player.play();
    };

    private boolean hasInternetConnection() {
        if (networkManager == null) return false;

        android.net.Network network =
                networkManager.getActiveNetwork();

        if (network == null) return false;

        android.net.NetworkCapabilities capabilities =
                networkManager.getNetworkCapabilities(network);

        return capabilities != null &&
                capabilities.hasCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
                ) &&
                capabilities.hasCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
                );
    }

    private void scheduleNetworkRecovery(long delayMs) {
        networkRecoveryHandler.removeCallbacks(networkRecoveryTask);

        if (networkRecoveryPending) {
            networkRecoveryHandler.postDelayed(
                    networkRecoveryTask,
                    delayMs
            );
        }
    }

    private void cancelNetworkRecovery() {
        networkRecoveryPending = false;
        networkRecoveryAttempts = 0;
        networkRecoveryUri = "";
        networkRecoveryHandler.removeCallbacks(networkRecoveryTask);
    }

    private void startNetworkRecovery(
            androidx.media3.common.PlaybackException error) {

        if (player == null || !player.getPlayWhenReady()) return;

        // Media3: familia de errores de entrada/salida.
        if (error.errorCode < 2000 || error.errorCode >= 3000) return;

        MediaItem item = player.getCurrentMediaItem();

        if (item == null || item.localConfiguration == null) return;

        String uri = item.localConfiguration.uri.toString();

        // Los archivos locales no requieren recuperación de red.
        if (!(uri.startsWith("http://") || uri.startsWith("https://"))) {
            return;
        }

        if (!uri.equals(networkRecoveryUri)) {
            networkRecoveryAttempts = 0;
        }

        networkRecoveryUri = uri;
        networkRecoveryPosition = Math.max(
                0L,
                player.getCurrentPosition()
        );
        networkRecoveryPending = true;

        scheduleNetworkRecovery(
                hasInternetConnection() ? 2000L : 5000L
        );
    }

    private void initializeNetworkRecovery() {
        networkManager =
                (android.net.ConnectivityManager)
                        getSystemService(CONNECTIVITY_SERVICE);

        if (networkManager == null) return;

        networkCallback =
                new android.net.ConnectivityManager.NetworkCallback() {

                    @Override
                    public void onAvailable(android.net.Network network) {
                        networkRecoveryHandler.post(
                            () -> scheduleNetworkRecovery(1200L)
                        );
                    }

                    @Override
                    public void onCapabilitiesChanged(
                            android.net.Network network,
                            android.net.NetworkCapabilities capabilities) {

                        if (capabilities.hasCapability(
                                android.net.NetworkCapabilities
                                        .NET_CAPABILITY_VALIDATED)) {
                            networkRecoveryHandler.post(
                                () -> scheduleNetworkRecovery(1200L)
                            );
                        }
                    }
                };

        try {
            networkManager.registerDefaultNetworkCallback(
                    networkCallback
            );
        } catch (Exception error) {
            android.util.Log.w(
                    "MusicDownloaderPlayer",
                    "No se pudo registrar la red",
                    error
            );
            networkCallback = null;
        }
    }


    // SPOTIFY_AUTO_WARM_V1
    private final android.os.Handler spotifyWarmHandler =
            new android.os.Handler(
                    android.os.Looper.getMainLooper()
            );

    private final java.util.concurrent.ExecutorService spotifyWarmExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private final java.util.concurrent.ConcurrentHashMap<String, Long>
            spotifyWarmTimes =
                    new java.util.concurrent.ConcurrentHashMap<>();

    private final java.util.Set<String> spotifyWarmRunning =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private boolean spotifyWarmStopped = false;

    private final Runnable spotifyWarmCheck = new Runnable() {
        @Override
        public void run() {
            if (spotifyWarmStopped) return;

            warmNextSpotifySong();

            spotifyWarmHandler.postDelayed(this, 10000L);
        }
    };

    private void warmNextSpotifySong() {
        if (player == null || !player.hasNextMediaItem()) {
            return;
        }

        int index = player.getNextMediaItemIndex();

        if (index < 0 ||
                index >= player.getMediaItemCount()) {
            return;
        }

        MediaItem next = player.getMediaItemAt(index);

        if (next.localConfiguration == null) return;

        android.net.Uri uri = next.localConfiguration.uri;

        if (uri == null ||
                !"/api/audio".equals(uri.getPath())) {
            return;
        }

        String videoId = uri.getQueryParameter("id");

        if (videoId == null ||
                !videoId.matches("[A-Za-z0-9_-]{11}")) {
            return;
        }

        long now = android.os.SystemClock.elapsedRealtime();
        Long previous = spotifyWarmTimes.get(videoId);

        long duration = player.getDuration();
        long position = player.getCurrentPosition();

        boolean nearEnd =
                duration > 0 &&
                duration != androidx.media3.common.C.TIME_UNSET &&
                duration - position <= 65000L;

        // Preparar al aparecer la siguiente canción.
        // Refrescar cerca del final si la caché envejeció.
        if (previous != null) {
            if (!nearEnd) return;
            if (now - previous < 55000L) return;
        }

        if (!spotifyWarmRunning.add(videoId)) {
            return;
        }

        final String warmUrl = uri.buildUpon()
                .path("/api/audio-warm")
                .build()
                .toString();

        spotifyWarmExecutor.execute(() -> {
            java.net.HttpURLConnection connection = null;

            try {
                connection = (java.net.HttpURLConnection)
                        new java.net.URL(warmUrl).openConnection();

                connection.setConnectTimeout(10000);
                connection.setReadTimeout(65000);
                connection.setRequestMethod("GET");

                int status = connection.getResponseCode();

                if (status == 200) {
                    spotifyWarmTimes.put(
                            videoId,
                            android.os.SystemClock.elapsedRealtime()
                    );

                    android.util.Log.d(
                            "SpotifyWarm",
                            "PREPARADA " + videoId
                    );
                } else {
                    android.util.Log.w(
                            "SpotifyWarm",
                            "HTTP " + status + " " + videoId
                    );
                }

            } catch (Exception error) {
                android.util.Log.w(
                        "SpotifyWarm",
                        "ERROR " + videoId,
                        error
                );
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }

                spotifyWarmRunning.remove(videoId);
            }
        });
    }


    // BLUETOOTH_MEDIA_RESUME_V1
    // Android elige qué app recibe PLAY; esto permite reanudar la nuestra
    // cuando una orden del coche llegue a Music Downloader y el servicio
    // haya sido cerrado. No interrumpe ni controla otras aplicaciones.
    private static final String CAR_MEDIA_PREFS = "music_car_media_resume_v1";
    private final android.os.Handler carMediaSaveHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable carMediaSaveTick = new Runnable() {
        @Override public void run() {
            saveCarMediaSnapshot();
            carMediaSaveHandler.postDelayed(this, 15000L);
        }
    };

    private void saveCarMediaSnapshot() {
        if (player == null || player.getMediaItemCount() == 0) return;
        int active = player.getCurrentMediaItemIndex();
        if (active < 0) return;
        try {
            org.json.JSONArray tracks = new org.json.JSONArray();
            int first = Math.max(0, active - 10);
            int last = Math.min(player.getMediaItemCount(), first + 120);
            int selectedSerialIndex = 0;
            for (int i = first; i < last; i++) {
                MediaItem item = player.getMediaItemAt(i);
                if (item.localConfiguration == null || item.localConfiguration.uri == null)
                    continue;
                if (i == active) selectedSerialIndex = tracks.length();
                org.json.JSONObject t = new org.json.JSONObject();
                t.put("uri", item.localConfiguration.uri.toString());
                t.put("id", item.mediaId);
                t.put("title", item.mediaMetadata.title == null ? "" : item.mediaMetadata.title.toString());
                t.put("artist", item.mediaMetadata.artist == null ? "" : item.mediaMetadata.artist.toString());
                t.put("album", item.mediaMetadata.albumTitle == null ? "" : item.mediaMetadata.albumTitle.toString());
                t.put("art", item.mediaMetadata.artworkUri == null ? "" : item.mediaMetadata.artworkUri.toString());
                tracks.put(t);
            }
            if (tracks.length() == 0) return;
            int newIndex = Math.min(selectedSerialIndex, tracks.length() - 1);
            org.json.JSONObject snapshot = new org.json.JSONObject();
            snapshot.put("tracks", tracks);
            snapshot.put("index", Math.max(0, newIndex));
            snapshot.put("position", Math.max(0L, player.getCurrentPosition()));
            getSharedPreferences(CAR_MEDIA_PREFS, MODE_PRIVATE)
                    .edit().putString("snapshot", snapshot.toString()).apply();
        } catch (Exception error) {
            android.util.Log.w("MusicDownloaderPlayer", "BT resumption cache", error);
        }
    }

    private MediaSession.MediaItemsWithStartPosition restoreCarMediaSnapshot()
            throws Exception {
        String saved = getSharedPreferences(CAR_MEDIA_PREFS, MODE_PRIVATE)
                .getString("snapshot", "");
        if (saved.isEmpty()) throw new UnsupportedOperationException("Sin música anterior");
        org.json.JSONObject snapshot = new org.json.JSONObject(saved);
        org.json.JSONArray tracks = snapshot.optJSONArray("tracks");
        if (tracks == null || tracks.length() == 0)
            throw new UnsupportedOperationException("Sin elementos para reanudar");
        List<MediaItem> items = new ArrayList<>();
        for (int i = 0; i < tracks.length(); i++) {
            org.json.JSONObject t = tracks.optJSONObject(i);
            if (t == null) continue;
            String uri = t.optString("uri", "");
            if (uri.isEmpty()) continue;
            androidx.media3.common.MediaMetadata.Builder meta =
                    new androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(t.optString("title", ""))
                            .setArtist(t.optString("artist", ""))
                            .setAlbumTitle(t.optString("album", ""));
            String artwork = t.optString("art", "");
            if (!artwork.isEmpty()) {
                try { meta.setArtworkUri(android.net.Uri.parse(artwork)); }
                catch (Exception ignored) { }
            }
            MediaItem.Builder builder = new MediaItem.Builder()
                    .setUri(android.net.Uri.parse(uri)).setMediaMetadata(meta.build());
            String id = t.optString("id", "");
            if (!id.isEmpty()) builder.setMediaId(id);
            items.add(builder.build());
        }
        if (items.isEmpty()) throw new UnsupportedOperationException("Cola anterior no válida");
        int index = Math.max(0, Math.min(items.size() - 1, snapshot.optInt("index", 0)));
        long pos = Math.max(0L, snapshot.optLong("position", 0L));
        return new MediaSession.MediaItemsWithStartPosition(items, index, pos);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        AudioAttributes audioAttributes =
                new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build();

        player =
                new ExoPlayer.Builder(this)
                        .build();

        player.setAudioAttributes(
                audioAttributes,
                true
        );

        player.setHandleAudioBecomingNoisy(true);
        spotifyWarmHandler.postDelayed(
                spotifyWarmCheck,
                10000L
        );


        player.addListener(new androidx.media3.common.Player.Listener() {

            @Override
            public void onMediaItemTransition(
                    MediaItem item,
                    int reason) {
                cancelNetworkRecovery();
                warmNextSpotifySong();
                saveCarMediaSnapshot();
            }

            @Override
            public void onPlayWhenReadyChanged(
                    boolean playWhenReady,
                    int reason) {
                if (!playWhenReady) {
                    cancelNetworkRecovery();
                }
                saveCarMediaSnapshot();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == androidx.media3.common.Player.STATE_READY) {
                    cancelNetworkRecovery();
                }
            }

            @Override
            public void onTimelineChanged(
                    androidx.media3.common.Timeline timeline,
                    int reason) {
                warmNextSpotifySong();
                saveCarMediaSnapshot();
            }


            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                MediaItem item = player.getCurrentMediaItem();

                String uri = "null";
                if (item != null &&
                        item.localConfiguration != null &&
                        item.localConfiguration.uri != null) {
                    uri = item.localConfiguration.uri.toString();
                }

                startNetworkRecovery(error);

                android.util.Log.e(
                        "MusicDownloaderPlayer",
                        "PLAYBACK ERROR"
                                + " code=" + error.errorCode
                                + " message=" + error.getMessage()
                                + " uri=" + uri,
                        error
                );
            }
        });

        initializeNetworkRecovery();
        carMediaSaveHandler.postDelayed(carMediaSaveTick, 15000L);

        Intent intent =
                new Intent(
                        this,
                        MainActivity.class
                );

        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE
                );

        mediaSession =
                new MediaSession.Builder(
                        this,
                        player
                )
                        .setSessionActivity(pendingIntent)
                        .setCallback(new MediaSession.Callback() {

                            @Override
                            public ListenableFuture<MediaSession.MediaItemsWithStartPosition>
                            onPlaybackResumption(MediaSession session,
                                                 MediaSession.ControllerInfo controller) {
                                try {
                                    MediaSession.MediaItemsWithStartPosition restored =
                                            restoreCarMediaSnapshot();
                                    QueueRepository.setQueue(restored.mediaItems);
                                    return Futures.immediateFuture(restored);
                                } catch (Exception failure) {
                                    return Futures.immediateFailedFuture(failure);
                                }
                            }

                            @Override
                            public MediaSession.ConnectionResult onConnect(
                                    MediaSession session,
                                    MediaSession.ControllerInfo controller) {

                                SessionCommands commands =
                                        MediaSession.ConnectionResult
                                                .DEFAULT_SESSION_COMMANDS
                                                .buildUpon()
                                                .add(
                                                        new SessionCommand(
                                                                COMMAND_PLAY_QUEUE,
                                                                Bundle.EMPTY
                                                        )
                                                )
                                                .add(
                                                        new SessionCommand(
                                                                COMMAND_ADD_QUEUE,
                                                                Bundle.EMPTY
                                                        )
                                                )
                                                .add(
                                                        new SessionCommand(
                                                                COMMAND_INSERT_QUEUE,
                                                                Bundle.EMPTY
                                                        )
                                                )
                                                .build();

                                return new MediaSession.ConnectionResult
                                        .AcceptedResultBuilder(session)
                                        .setAvailableSessionCommands(commands)
                                        .build();
                            }

                            @Override
                            public ListenableFuture<SessionResult>
                            onCustomCommand(
                                    MediaSession session,
                                    MediaSession.ControllerInfo controller,
                                    SessionCommand customCommand,
                                    Bundle args) {

                                if (COMMAND_PLAY_QUEUE.equals(
                                        customCommand.customAction)) {

                                    List<MediaItem> items =
                                            QueueRepository.getQueue();

                                    if (items.isEmpty()) {

                                        return Futures.immediateFuture(
                                                new SessionResult(
                                                        SessionResult.RESULT_ERROR_UNKNOWN
                                                )
                                        );
                                    }

                                    player.stop();

                                    player.clearMediaItems();

                                    player.setMediaItems(
                                            items,
                                            0,
                                            0
                                    );

                                    player.prepare();

                                    player.play();

                                    return Futures.immediateFuture(
                                            new SessionResult(
                                                    SessionResult.RESULT_SUCCESS
                                            )
                                    );
                                }

                                if (COMMAND_INSERT_QUEUE.equals(
                                        customCommand.customAction)) {

                                    String uri =
                                            args.getString(
                                                    "queue_insert_uri"
                                            );

                                    if (uri == null ||
                                            uri.trim().isEmpty()) {

                                        return Futures.immediateFuture(
                                                new SessionResult(
                                                        SessionResult.RESULT_ERROR_UNKNOWN
                                                )
                                        );
                                    }

                                    androidx.media3.common.MediaMetadata.Builder
                                            metadataBuilder =
                                            new androidx.media3.common.MediaMetadata.Builder();

                                    String title =
                                            args.getString(
                                                    "queue_insert_title"
                                            );

                                    String artist =
                                            args.getString(
                                                    "queue_insert_artist"
                                            );

                                    String album =
                                            args.getString(
                                                    "queue_insert_album"
                                            );

                                    String artwork =
                                            args.getString(
                                                    "queue_insert_artwork"
                                            );

                                    if (title != null) {
                                        metadataBuilder.setTitle(title);
                                    }

                                    if (artist != null) {
                                        metadataBuilder.setArtist(artist);
                                    }

                                    if (album != null) {
                                        metadataBuilder.setAlbumTitle(album);
                                    }

                                    if (artwork != null &&
                                            !artwork.trim().isEmpty()) {

                                        metadataBuilder.setArtworkUri(
                                                android.net.Uri.parse(
                                                        artwork
                                                )
                                        );
                                    }

                                    // COLA_DURACION_TRANSPORTE_V1
                                    long durationMs = args.getLong(
                                            "queue_insert_durationMs", -1L);
                                    if (durationMs > 0) {
                                        Bundle durationExtras = new Bundle();
                                        durationExtras.putLong("durationMs", durationMs);
                                        metadataBuilder.setExtras(durationExtras);
                                    }

                                    MediaItem item =
                                            new MediaItem.Builder()
                                                    .setUri(uri)
                                                    .setMediaMetadata(
                                                            metadataBuilder.build()
                                                    )
                                                    .build();

                                    int insertIndex =
                                            args.getInt(
                                                    "queue_insert_index",
                                                    player.getMediaItemCount()
                                            );

                                    insertIndex =
                                            Math.max(
                                                    0,
                                                    Math.min(
                                                            insertIndex,
                                                            player.getMediaItemCount()
                                                    )
                                            );

                                    player.addMediaItem(
                                            insertIndex,
                                            item
                                    );

                                    ArrayList<MediaItem> currentItems =
                                            new ArrayList<>();

                                    for (
                                            int i = 0;
                                            i < player.getMediaItemCount();
                                            i++
                                    ) {
                                        currentItems.add(
                                                player.getMediaItemAt(i)
                                        );
                                    }

                                    QueueRepository.setQueue(
                                            currentItems
                                    );

                                    return Futures.immediateFuture(
                                            new SessionResult(
                                                    SessionResult.RESULT_SUCCESS
                                            )
                                    );
                                }

                                if (COMMAND_ADD_QUEUE.equals(
                                        customCommand.customAction)) {

                                    ArrayList<MediaItem> newItems =
                                            new ArrayList<>();

                                    /*
                                     * URI que ya existe realmente en
                                     * el reproductor Media3.
                                     */
                                    java.util.HashSet<String> existingUris =
                                            new java.util.HashSet<>();

                                    for (int i = 0;
                                            i < player.getMediaItemCount();
                                            i++) {

                                        MediaItem existing =
                                                player.getMediaItemAt(i);

                                        if (existing == null ||
                                                existing.localConfiguration == null ||
                                                existing.localConfiguration.uri == null) {
                                            continue;
                                        }

                                        String existingUri =
                                                existing.localConfiguration.uri.toString();

                                        if (existingUri != null &&
                                                !existingUri.trim().isEmpty()) {

                                            existingUris.add(existingUri);
                                        }
                                    }

                                    int count =
                                            args.getInt(
                                                    "queue_item_count",
                                                    0
                                            );

                                    for (int i = 0; i < count; i++) {

                                        String prefix =
                                                "queue_item_" + i + "_";

                                        String uri =
                                                args.getString(
                                                        prefix + "uri"
                                                );

                                        if (uri == null ||
                                                uri.trim().isEmpty()) {
                                            continue;
                                        }

                                        /*
                                         * SEGUNDA BARRERA:
                                         *
                                         * Si Media3 ya contiene esta URI,
                                         * no se vuelve a introducir.
                                         */
                                        if (existingUris.contains(uri)) {
                                            continue;
                                        }

                                        androidx.media3.common.MediaMetadata.Builder
                                                metadataBuilder =
                                                new androidx.media3.common.MediaMetadata.Builder();

                                        String title =
                                                args.getString(
                                                        prefix + "title"
                                                );

                                        String artist =
                                                args.getString(
                                                        prefix + "artist"
                                                );

                                        String album =
                                                args.getString(
                                                        prefix + "album"
                                                );

                                        String artwork =
                                                args.getString(
                                                        prefix + "artwork"
                                                );

                                        if (title != null) {
                                            metadataBuilder.setTitle(title);
                                        }

                                        if (artist != null) {
                                            metadataBuilder.setArtist(artist);
                                        }

                                        if (album != null) {
                                            metadataBuilder.setAlbumTitle(album);
                                        }

                                        if (artwork != null &&
                                                !artwork.trim().isEmpty()) {

                                            metadataBuilder.setArtworkUri(
                                                    android.net.Uri.parse(
                                                            artwork
                                                    )
                                            );
                                        }

                                        // COLA_DURACION_TRANSPORTE_V1
                                        long durationMs = args.getLong(
                                                prefix + "durationMs", -1L);
                                        if (durationMs > 0) {
                                            Bundle durationExtras = new Bundle();
                                            durationExtras.putLong("durationMs", durationMs);
                                            metadataBuilder.setExtras(durationExtras);
                                        }

                                        MediaItem item =
                                                new MediaItem.Builder()
                                                        .setUri(uri)
                                                        .setMediaMetadata(
                                                                metadataBuilder.build()
                                                        )
                                                        .build();

                                        newItems.add(item);

                                        /*
                                         * Evita también duplicados dentro
                                         * del propio comando.
                                         */
                                        existingUris.add(uri);
                                    }

                                    if (!newItems.isEmpty()) {

                                        player.addMediaItems(
                                                newItems
                                        );
                                    }

                                    /*
                                     * QueueRepository debe reflejar
                                     * exactamente la cola REAL de Media3.
                                     */
                                    ArrayList<MediaItem> currentItems =
                                            new ArrayList<>();

                                    for (int i = 0;
                                            i < player.getMediaItemCount();
                                            i++) {

                                        currentItems.add(
                                                player.getMediaItemAt(i)
                                        );
                                    }

                                    QueueRepository.setQueue(
                                            currentItems
                                    );

                                    return Futures.immediateFuture(
                                            new SessionResult(
                                                    SessionResult.RESULT_SUCCESS
                                            )
                                    );
                                }

                                return Futures.immediateFuture(
                                        new SessionResult(
                                                SessionResult.RESULT_SUCCESS
                                        )
                                );
                            }
                        })
                        .build();
    }

    public int getQueueSize() {

        if (player == null) {
            return 0;
        }

        return player.getMediaItemCount();
    }

    public void stopPlayback() {

        if (player == null) {
            return;
        }

        player.stop();
    }

    @Override
    public void onTaskRemoved(
            Intent rootIntent) {

        if (player != null &&
                !player.getPlayWhenReady()) {
            stopSelf();
        }

        super.onTaskRemoved(rootIntent);
    }

    @Override
    public MediaSession onGetSession(
            MediaSession.ControllerInfo controllerInfo) {

        return mediaSession;
    }

    @Override
    public void onDestroy() {
        saveCarMediaSnapshot();
        carMediaSaveHandler.removeCallbacks(carMediaSaveTick);
        cancelNetworkRecovery();

        if (networkManager != null && networkCallback != null) {
            try {
                networkManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
        }

        spotifyWarmStopped = true;
        spotifyWarmHandler.removeCallbacks(spotifyWarmCheck);
        spotifyWarmExecutor.shutdownNow();


        QueueRepository.clear();

        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }

        if (player != null) {
            player.release();
            player = null;
        }

        super.onDestroy();
    }
}
