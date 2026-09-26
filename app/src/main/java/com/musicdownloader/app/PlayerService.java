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

    private ExoPlayer player;
    private MediaSession mediaSession;

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

                                if (COMMAND_ADD_QUEUE.equals(
                                        customCommand.customAction)) {

                                    ArrayList<MediaItem> newItems =
                                            new ArrayList<>();

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

                                        MediaItem item =
                                                new MediaItem.Builder()
                                                        .setUri(uri)
                                                        .setMediaMetadata(
                                                                metadataBuilder.build()
                                                        )
                                                        .build();

                                        newItems.add(item);
                                    }

                                    if (!newItems.isEmpty()) {

                                        player.addMediaItems(
                                                newItems
                                        );
                                    }

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
