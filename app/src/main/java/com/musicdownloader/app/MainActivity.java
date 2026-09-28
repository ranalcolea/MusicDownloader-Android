package com.musicdownloader.app;
import android.view.View;
import android.animation.ValueAnimator;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;

import android.content.Intent;
import android.net.Uri;
import android.media.MediaMetadataRetriever;


import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Looper;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.Gravity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.util.Log;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionToken;
import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;

public class MainActivity extends android.app.Activity {

    /*
     * Generación de reproducción individual.
     *
     * Cada nueva canción/lista individual invalida los
     * resolvers pendientes de la reproducción anterior.
     */
    private volatile long individualPlaybackGeneration = 0;

    private long beginIndividualPlayback() {
        return ++individualPlaybackGeneration;
    }

    private boolean isCurrentIndividualPlayback(
            long generation) {

        return generation ==
                individualPlaybackGeneration;
    }


    private static final int PICK_PLAYLIST_FILE = 9001;
    private static final int PICK_OFFLINE_FOLDER = 9002;

    private Uri offlineFolderUri;

    private EditText serverInput;
    private TextView serverStatus;
    private LinearLayout serverCard;
    private EditText searchInput;
    private LinearLayout contentLayout;

    // Contenedores visuales de la nueva navegación
    private LinearLayout rootLayout;
    private android.widget.FrameLayout contentFrame;
    private LinearLayout bottomNavigation;

    /*
     * CONTENEDORES PERSISTENTES DE LAS PESTAÑAS
     */
    private ScrollView mainTabScroll;
    private ScrollView spotifyTabScroll;
    private ScrollView offlineTabScroll;
    private ScrollView downloadsTabScroll;

    private LinearLayout mainTabLayout;
    private LinearLayout spotifyTabLayout;
    private LinearLayout offlineTabLayout;
    private LinearLayout downloadsTabLayout;

    private TextView playerTitle;
    private TextView playerArtist;
    private TextView playerQueueProgress;
    private ImageView playerArtwork;
    private TextView playerTime;

    /*
     * Últimos resultados de búsqueda de álbumes.
     * Se conservan para poder volver desde la ficha del álbum.
     */
    private List<ApiClient.Album> lastAlbumSearchResults =
            new ArrayList<>();

    /*
     * Estado visual de preparación de cada pista.
      *
     * Cada posición de la lista corresponde exactamente
     * con la tarjeta mostrada en pantalla.
     */
    private final List<TextView> albumPreparationStatusViews =
            new ArrayList<>();

    private final List<TextView> spotifyPreparationStatusViews =
            new ArrayList<>();

    /*
     * IDs de las canciones de búsqueda normal que ya han sido
     * preparadas para reproducirse.
     *
     * Se guarda el ID y no la posición porque la búsqueda está
     * paginada y las tarjetas se reconstruyen al cambiar de página.
     */
    private final java.util.Set<String> searchObtainedVideoIds =
            new java.util.HashSet<>();

    private final List<TextView> searchPreparationStatusViews =
            new ArrayList<>();

    private volatile long queuePreparationGeneration = 0;

    private String playerArtworkLoaded = "";
    private SeekBar playerSeekBar;

    private LinearLayout player;

    // El usuario ha cerrado manualmente el mini-player.
    private boolean playerManuallyClosed = false;

    /*
     * El usuario puede minimizar el mini-player sin detener
     * la reproducción. El botón flotante permite restaurarlo.
     */
    private boolean playerMinimized = false;

    private Button restorePlayerButton;

    private Button playPauseButton;
    private Button previousButton;
    private Button nextButton;

    private MediaController mediaController;

    private final ExecutorService executor =
            Executors.newFixedThreadPool(4);

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private final Handler playerHandler =
            new Handler(Looper.getMainLooper());

    private final Runnable progressUpdater =
            new Runnable() {

        @Override
        public void run() {

            updatePlayerUi();

            playerHandler.postDelayed(
                    this,
                    500
            );
        }
    };

    @Override
    protected void onCreate(
            Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        buildUi();

        String savedOfflineFolder =
                getSharedPreferences(
                        "settings",
                        MODE_PRIVATE
                ).getString(
                        "offline_folder_uri",
                        ""
                );

        if (savedOfflineFolder != null
                && !savedOfflineFolder.isEmpty()) {

            try {
                offlineFolderUri =
                        Uri.parse(savedOfflineFolder);
            } catch (Exception ignored) {
                offlineFolderUri = null;
            }
        }

        playerHandler.post(
                progressUpdater
        );

        handler.postDelayed(
                () -> connectMediaController(),
                500
        );
    }

    private void buildUi() {

        rootLayout =
                new LinearLayout(this);

        LinearLayout root =
                rootLayout;

        root.setOrientation(
                LinearLayout.VERTICAL
        );

        root.setPadding(
                20,
                20,
                20,
                20
        );

        TextView title =
                new TextView(this);

        title.setText(
                "🎵 Music Downloader"
        );

        title.setTextSize(26);

        title.setGravity(
                Gravity.CENTER
        );

        root.addView(
                title,
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                )
        );

        serverInput =
                new EditText(this);

        serverInput.setHint(
                "Servidor (http://IP:puerto)"
        );

        serverInput.setSingleLine(true);

        String savedUrl =
                getSharedPreferences(
                        "settings",
                        MODE_PRIVATE
                ).getString(
                        "server_url",
                        ""
                );

        serverInput.setText(savedUrl);

        /*
         * NAVEGACIÓN PRINCIPAL
         */

        bottomNavigation =
                new LinearLayout(this);

        bottomNavigation.setOrientation(
                LinearLayout.HORIZONTAL
        );

        bottomNavigation.setGravity(
                Gravity.CENTER
        );

        bottomNavigation.setPadding(
                dp(8),
                dp(6),
                dp(8),
                dp(6)
        );

        bottomNavigation.setBackground(
                roundedBackground(
                        Color.rgb(24, 24, 30),
                        Color.rgb(65, 65, 78),
                        dp(18)
                )
        );

        bottomNavigation.setElevation(
                dp(8)
        );

        Button musicTab =
                visualButton("🎵 Música");

        Button spotifyTab =
                visualButton("🟢 Spotify");

        Button offlineTab =
                visualButton("📱 Offline");

        Button downloadsTab =
                visualButton("🔗 Conexiones");

        LinearLayout.LayoutParams navButtonParams =
                new LinearLayout.LayoutParams(
                        0,
                        dp(48),
                        1f
                );

        navButtonParams.setMargins(
                dp(3),
                0,
                dp(3),
                0
        );

        bottomNavigation.addView(
                musicTab,
                new LinearLayout.LayoutParams(
                        navButtonParams
                )
        );

        bottomNavigation.addView(
                spotifyTab,
                new LinearLayout.LayoutParams(
                        navButtonParams
                )
        );

        bottomNavigation.addView(
                offlineTab,
                new LinearLayout.LayoutParams(
                        navButtonParams
                )
        );

        bottomNavigation.addView(
                downloadsTab,
                new LinearLayout.LayoutParams(
                        navButtonParams
                )
        );

        musicTab.setOnClickListener(
                v -> showMainHome()
        );

        spotifyTab.setOnClickListener(
                v -> showSpotifyHome()
        );

        offlineTab.setOnClickListener(
                v -> showOfflineLibrary()
        );

        downloadsTab.setOnClickListener(
                v -> showDownloads()
        );


        root.addView(
                bottomNavigation,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(60)
                )
        );

        /*
         * ============================================================
         * SERVIDOR
         * ============================================================
         */

        serverCard =
                new LinearLayout(this);

        serverCard.setOrientation(
                LinearLayout.VERTICAL
        );

        serverCard.setPadding(
                dp(11),
                dp(6),
                dp(11),
                dp(5)
        );

        serverCard.setBackground(
                roundedBackground(
                        Color.rgb(25, 25, 32),
                        Color.rgb(70, 70, 85),
                        20
                )
        );

        serverCard.setElevation(
                dp(6)
        );

        LinearLayout.LayoutParams serverCardParams =
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                );

        serverCardParams.setMargins(
                dp(8),
                dp(2),
                dp(8),
                dp(4)
        );

        TextView serverTitle =
                new TextView(this);

        serverTitle.setText(
                "🖥  SERVIDOR"
        );

        serverTitle.setTextSize(13);

        serverTitle.setTypeface(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
        );

        serverTitle.setTextColor(
                Color.rgb(145, 145, 255)
        );

        serverCard.addView(
                serverTitle
        );

        TextView serverDescription =
                new TextView(this);

        serverDescription.setText(
                "Conecta la aplicación con tu servidor Music Downloader"
        );

        serverDescription.setTextSize(13);

        serverDescription.setTextColor(
                Color.rgb(135, 135, 145)
        );

        LinearLayout.LayoutParams descriptionParams =
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                );

        descriptionParams.setMargins(
                0,
                0,
                0,
                dp(3)
        );

        serverCard.addView(
                serverDescription,
                descriptionParams
        );

        serverInput.setTextColor(
                Color.WHITE
        );

        serverInput.setHintTextColor(
                Color.rgb(120, 120, 130)
        );

        serverInput.setTextSize(15);

        serverInput.setPadding(
                dp(14),
                0,
                dp(14),
                0
        );

        serverInput.setBackground(
                roundedBackground(
                        Color.rgb(18, 18, 24),
                        Color.rgb(75, 75, 92),
                        14
                )
        );

        serverCard.addView(
                serverInput,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(38)
                )
        );

        LinearLayout statusRow =
                new LinearLayout(this);

        statusRow.setOrientation(
                LinearLayout.HORIZONTAL
        );

        statusRow.setGravity(
                Gravity.CENTER_VERTICAL
        );

        serverStatus =
                new TextView(this);

        android.content.SharedPreferences serverPrefs =
                getSharedPreferences(
                        "settings",
                        MODE_PRIVATE
                );

        String checkedServer =
                serverPrefs.getString(
                        "server_checked_url",
                        ""
                );

        boolean serverWasChecked =
                serverPrefs.getBoolean(
                        "server_checked",
                        false
                );

        boolean serverWasConnected =
                serverPrefs.getBoolean(
                        "server_connected",
                        false
                );

        String currentServer =
                serverInput
                        .getText()
                        .toString()
                        .trim();

        if (
                serverWasChecked &&
                serverWasConnected &&
                currentServer.equals(checkedServer)
        ) {

            serverStatus.setText(
                    "🟢 Conectado"
            );

            serverStatus.setTextColor(
                    Color.rgb(55, 215, 125)
            );

        } else if (
                serverWasChecked &&
                !serverWasConnected &&
                currentServer.equals(checkedServer)
        ) {

            serverStatus.setText(
                    "🔴 Sin conexión"
            );

            serverStatus.setTextColor(
                    Color.rgb(255, 90, 100)
            );

        } else {

            serverStatus.setText(
                    "⚪ Sin comprobar"
            );

            serverStatus.setTextColor(
                    Color.rgb(145, 145, 155)
            );
        }

        serverStatus.setTextSize(13);

        LinearLayout.LayoutParams statusParams =
                new LinearLayout.LayoutParams(
                        0,
                        -2,
                        1
                );

        statusParams.setMargins(
                0,
                dp(2),
                0,
                0
        );

        statusRow.addView(
                serverStatus,
                statusParams
        );

        Button checkServer =
                roundedButton();

        checkServer.setText(
                "↻ Comprobar"
        );

        checkServer.setTextSize(12);

        LinearLayout.LayoutParams checkParams =
                new LinearLayout.LayoutParams(
                        -2,
                        dp(34)
                );

        checkParams.setMargins(
                dp(4),
                dp(1),
                0,
                0
        );

        statusRow.addView(
                checkServer,
                checkParams
        );

        Button saveServer =
                roundedButton();

        saveServer.setText(
                "✓ Guardar"
        );

        saveServer.setTextSize(12);

        LinearLayout.LayoutParams saveParams =
                new LinearLayout.LayoutParams(
                        -2,
                        dp(34)
                );

        saveParams.setMargins(
                dp(4),
                dp(1),
                0,
                0
        );

        statusRow.addView(
                saveServer,
                saveParams
        );

        serverCard.addView(
                statusRow
        );

        checkServer.setOnClickListener(
                v -> checkServerConnection()
        );

        saveServer.setOnClickListener(
                v -> saveServerUrl()
        );

        /*
         * ============================================================
         * BUSCADOR
         * ============================================================
         */

        LinearLayout searchCard =
                new LinearLayout(this);

        searchCard.setOrientation(
                LinearLayout.VERTICAL
        );

        searchCard.setPadding(
                dp(11),
                dp(6),
                dp(11),
                dp(5)
        );

        searchCard.setBackground(
                roundedBackground(
                        Color.rgb(25, 25, 32),
                        Color.rgb(70, 70, 85),
                        dp(20)
                )
        );

        searchCard.setElevation(
                dp(7)
        );

        LinearLayout.LayoutParams searchCardParams =
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                );

        searchCardParams.setMargins(
                dp(8),
                dp(2),
                dp(8),
                dp(4)
        );

        TextView searchTitle =
                new TextView(this);

        searchTitle.setText(
                "BUSCAR MÚSICA"
        );

        searchTitle.setTextSize(13);

        searchTitle.setTypeface(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
        );

        searchTitle.setTextColor(
                Color.rgb(145, 105, 255)
        );

        searchCard.addView(
                searchTitle
        );

        TextView searchDescription =
                new TextView(this);

        searchDescription.setText(
                "Encuentra canciones, artistas y álbumes"
        );

        searchDescription.setTextSize(12);

        searchDescription.setTextColor(
                Color.rgb(135, 135, 145)
        );

        LinearLayout.LayoutParams searchDescriptionParams =
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                );

        searchDescriptionParams.setMargins(
                0,
                0,
                0,
                dp(3)
        );

        searchCard.addView(
                searchDescription,
                searchDescriptionParams
        );

        searchInput =
                new EditText(this);

        searchInput.setHint(
                "Buscar canción o artista"
        );

        searchInput.setSingleLine(true);

        searchInput.setTextColor(
                Color.WHITE
        );

        searchInput.setHintTextColor(
                Color.rgb(120, 120, 130)
        );

        searchInput.setTextSize(16);

        searchInput.setPadding(
                dp(16),
                0,
                dp(16),
                0
        );

        searchInput.setCompoundDrawablesWithIntrinsicBounds(
                android.R.drawable.ic_menu_search,
                0,
                0,
                0
        );

        searchInput.setCompoundDrawablePadding(
                dp(10)
        );

        searchInput.setBackground(
                roundedBackground(
                        Color.rgb(17, 17, 23),
                        Color.rgb(80, 80, 98),
                        dp(16)
                )
        );

        searchInput.setElevation(
                dp(3)
        );

        searchCard.addView(
                searchInput,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(56)
                )
        );

        LinearLayout modeRow =
                new LinearLayout(this);

        modeRow.setOrientation(
                LinearLayout.HORIZONTAL
        );

        modeRow.setGravity(
                Gravity.CENTER
        );

        LinearLayout.LayoutParams modeRowParams =
                new LinearLayout.LayoutParams(
                        -1,
                        dp(50)
                );

        modeRowParams.setMargins(
                0,
                dp(4),
                0,
                0
        );

        Button songsButton =
                new Button(this);

        songsButton.setText(
                "🎵  Canciones"
        );

        songsButton.setTextSize(14);

        songsButton.setTextColor(
                Color.WHITE
        );

        songsButton.setAllCaps(false);

        songsButton.setTypeface(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
        );

        songsButton.setGravity(
                Gravity.CENTER
        );

        songsButton.setBackground(
                roundedBackground(
                        Color.rgb(38, 32, 55),
                        Color.rgb(105, 82, 165),
                        dp(14)
                )
        );

        songsButton.setElevation(
                dp(4)
        );

        songsButton.setOnClickListener(
                v -> searchSongs()
        );

        LinearLayout.LayoutParams songsParams =
                new LinearLayout.LayoutParams(
                        0,
                        dp(39),
                        1
                );

        songsParams.setMargins(
                0,
                0,
                dp(5),
                0
        );

        modeRow.addView(
                songsButton,
                songsParams
        );

        Button albumsButton =
                new Button(this);

        albumsButton.setText(
                "💿  Álbumes"
        );

        albumsButton.setTextSize(14);

        albumsButton.setTextColor(
                Color.WHITE
        );

        albumsButton.setAllCaps(false);

        albumsButton.setTypeface(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
        );

        albumsButton.setGravity(
                Gravity.CENTER
        );

        albumsButton.setBackground(
                roundedBackground(
                        Color.rgb(30, 30, 38),
                        Color.rgb(75, 75, 90),
                        dp(14)
                )
        );

        albumsButton.setElevation(
                dp(4)
        );

        albumsButton.setOnClickListener(
                v -> searchAlbums()
        );

        LinearLayout.LayoutParams albumsParams =
                new LinearLayout.LayoutParams(
                        0,
                        dp(39),
                        1
                );

        albumsParams.setMargins(
                dp(5),
                0,
                0,
                0
        );

        modeRow.addView(
                albumsButton,
                albumsParams
        );

        searchCard.addView(
                modeRow,
                modeRowParams
        );

        root.addView(
                searchCard,
                searchCardParams
        );

        /*
         * MINI PLAYER FLOTANTE
         */

        contentFrame =
                new android.widget.FrameLayout(this);

        /*
         * =====================================================
         * CUATRO CONTENEDORES INDEPENDIENTES
         * =====================================================
         *
         * Cada pestaña conserva sus propias Views.
         * Cambiar de pestaña NO destruye los ProgressBar,
         * botones, textos ni DownloadSlot visuales.
         */

        mainTabScroll =
                new ScrollView(this);

        mainTabLayout =
                new LinearLayout(this);

        mainTabLayout.setOrientation(
                LinearLayout.VERTICAL
        );

        mainTabScroll.addView(
                mainTabLayout
        );

        spotifyTabScroll =
                new ScrollView(this);

        spotifyTabLayout =
                new LinearLayout(this);

        spotifyTabLayout.setOrientation(
                LinearLayout.VERTICAL
        );

        spotifyTabScroll.addView(
                spotifyTabLayout
        );

        offlineTabScroll =
                new ScrollView(this);

        offlineTabLayout =
                new LinearLayout(this);

        offlineTabLayout.setOrientation(
                LinearLayout.VERTICAL
        );

        offlineTabScroll.addView(
                offlineTabLayout
        );

        downloadsTabScroll =
                new ScrollView(this);

        downloadsTabLayout =
                new LinearLayout(this);

        downloadsTabLayout.setOrientation(
                LinearLayout.VERTICAL
        );

        downloadsTabScroll.addView(
                downloadsTabLayout
        );

        android.widget.FrameLayout.LayoutParams tabParams =
                new android.widget.FrameLayout.LayoutParams(
                        -1,
                        -1
                );

        contentFrame.addView(
                mainTabScroll,
                new android.widget.FrameLayout.LayoutParams(
                        tabParams
                )
        );

        contentFrame.addView(
                spotifyTabScroll,
                new android.widget.FrameLayout.LayoutParams(
                        tabParams
                )
        );

        contentFrame.addView(
                offlineTabScroll,
                new android.widget.FrameLayout.LayoutParams(
                        tabParams
                )
        );

        contentFrame.addView(
                downloadsTabScroll,
                new android.widget.FrameLayout.LayoutParams(
                        tabParams
                )
        );

        spotifyTabScroll.setVisibility(
                android.view.View.GONE
        );

        offlineTabScroll.setVisibility(
                android.view.View.GONE
        );

        downloadsTabScroll.setVisibility(
                android.view.View.GONE
        );

        /*
         * El resto del código sigue trabajando con
         * contentLayout. Ahora apunta a la pestaña activa.
         */
        contentLayout =
                mainTabLayout;

        /*
         * =========================================================
         * MINI-PLAYER
         * =========================================================
         *
         * Solo cambios visuales.
         * La reproducción Media3 y la cola permanecen intactas.
         */

        player =
                new LinearLayout(this);

        player.setOrientation(
                LinearLayout.VERTICAL
        );

        player.setPadding(
                dp(14),
                dp(12),
                dp(14),
                dp(12)
        );

        player.setVisibility(
                android.view.View.GONE
        );

        player.setBackground(
                roundedBackground(
                        Color.rgb(24, 24, 30),
                        Color.rgb(82, 82, 96),
                        dp(20)
                )
        );

        player.setElevation(
                dp(12)
        );

        /*
         * CABECERA
         */

        LinearLayout playerHeader =
                new LinearLayout(this);

        playerHeader.setOrientation(
                LinearLayout.HORIZONTAL
        );

        playerHeader.setGravity(
                Gravity.CENTER_VERTICAL
        );

        TextView playerHeaderTitle =
                new TextView(this);

        playerHeaderTitle.setText(
                "🎵  REPRODUCIENDO"
        );

        playerHeaderTitle.setTextSize(
                11
        );

        playerHeaderTitle.setTextColor(
                Color.LTGRAY
        );

        playerHeaderTitle.setTypeface(
                null,
                android.graphics.Typeface.BOLD
        );

        playerHeaderTitle.setLetterSpacing(
                0.08f
        );

        playerHeaderTitle.setLayoutParams(
                new LinearLayout.LayoutParams(
                        0,
                        -2,
                        1
                )
        );

        playerHeader.addView(
                playerHeaderTitle
        );

        /*
         * BOTÓN MINIMIZAR
         *
         * Oculta el mini-player pero mantiene la reproducción.
         */
        Button minimizePlayerButton =
                roundedButton();

        minimizePlayerButton.setText(
                "—"
        );

        minimizePlayerButton.setTextSize(
                15
        );

        minimizePlayerButton.setMinHeight(
                dp(36)
        );

        minimizePlayerButton.setMinWidth(
                dp(40)
        );

        minimizePlayerButton.setPadding(
                dp(7),
                0,
                dp(7),
                0
        );

        minimizePlayerButton.setOnClickListener(
                v -> {

                    playerMinimized = true;

                    player.setVisibility(
                            android.view.View.GONE
                    );

                    if (restorePlayerButton != null) {
                        restorePlayerButton.setVisibility(
                                android.view.View.VISIBLE
                        );
                    }
                }
        );

        playerHeader.addView(
                minimizePlayerButton
        );

        /*
         * BOTÓN CERRAR
         *
         * Mantiene el comportamiento original:
         * detener la reproducción y ocultar el player.
         */
        Button closePlayerButton =
                roundedButton();

        closePlayerButton.setText(
                "✕"
        );

        closePlayerButton.setTextSize(
                13
        );

        closePlayerButton.setMinHeight(
                dp(36)
        );

        closePlayerButton.setMinWidth(
                dp(40)
        );

        closePlayerButton.setPadding(
                dp(7),
                0,
                dp(7),
                0
        );

        closePlayerButton.setOnClickListener(
                v -> {

                    playerManuallyClosed = true;
                    playerMinimized = false;

                    if (mediaController != null) {
                        mediaController.stop();
                    }

                    player.setVisibility(
                            android.view.View.GONE
                    );

                    if (restorePlayerButton != null) {
                        restorePlayerButton.setVisibility(
                                android.view.View.GONE
                        );
                    }
                }
        );

        playerHeader.addView(
                closePlayerButton
        );

        player.addView(
                playerHeader
        );

        /*
         * SEPARADOR VISUAL
         */

        android.view.View playerSpacer =
                new android.view.View(this);

        LinearLayout.LayoutParams spacerParams =
                new LinearLayout.LayoutParams(
                        -1,
                        dp(5)
                );

        player.addView(
                playerSpacer,
                spacerParams
        );

        /*
         * PORTADA DEL REPRODUCTOR
         */

        playerArtwork =
                new ImageView(this);

        playerArtwork.setScaleType(
                ImageView.ScaleType.CENTER_CROP
        );

        playerArtwork.setBackground(
                roundedBackground(
                        Color.rgb(36, 36, 44),
                        Color.rgb(82, 82, 96),
                        dp(14)
                )
        );

        LinearLayout.LayoutParams artworkParams =
                new LinearLayout.LayoutParams(
                        dp(190),
                        dp(190)
                );

        artworkParams.gravity =
                Gravity.CENTER_HORIZONTAL;

        artworkParams.setMargins(
                0,
                dp(6),
                0,
                dp(8)
        );

        player.addView(
                playerArtwork,
                artworkParams
        );

        /*
         * CANCIÓN ACTUAL
         */

        playerTitle =
                new TextView(this);

        playerTitle.setText(
                "Sin reproducción"
        );

        playerTitle.setTextSize(
                16
        );

        playerTitle.setTextColor(
                Color.WHITE
        );

        playerTitle.setTypeface(
                null,
                android.graphics.Typeface.BOLD
        );

        playerTitle.setSingleLine(
                true
        );

        playerTitle.setEllipsize(
                android.text.TextUtils.TruncateAt.END
        );

        playerTitle.setGravity(
                Gravity.CENTER
        );

        playerTitle.setPadding(
                dp(4),
                dp(2),
                dp(4),
                dp(2)
        );

        player.addView(
                playerTitle
        );

        /*
         * ARTISTA
         */

        playerArtist =
                new TextView(this);

        playerArtist.setText(
                "Artista"
        );

        playerArtist.setTextSize(
                13
        );

        playerArtist.setTextColor(
                Color.LTGRAY
        );

        playerArtist.setGravity(
                Gravity.CENTER
        );

        playerArtist.setSingleLine(
                true
        );

        playerArtist.setEllipsize(
                android.text.TextUtils.TruncateAt.END
        );

        playerArtist.setPadding(
                dp(4),
                0,
                dp(4),
                dp(6)
        );

        player.addView(
                playerArtist
        );

        /*
         * CONTADOR DE PREPARACIÓN DE COLA
         *
         * Indica canciones resueltas/cargadas:
         *
         * 1/23
         * 2/23
         * ...
         * 23/23
         */

        playerQueueProgress =
                new TextView(this);

        playerQueueProgress.setText(
                "0/0"
        );

        playerQueueProgress.setTextSize(
                12
        );

        playerQueueProgress.setTextColor(
                Color.LTGRAY
        );

        playerQueueProgress.setTypeface(
                null,
                android.graphics.Typeface.BOLD
        );

        playerQueueProgress.setGravity(
                Gravity.CENTER
        );

        playerQueueProgress.setTextAlignment(
                android.view.View.TEXT_ALIGNMENT_CENTER
        );

        playerQueueProgress.setPadding(
                0,
                0,
                0,
                dp(2)
        );

        playerQueueProgress.setVisibility(
                android.view.View.GONE
        );

        LinearLayout.LayoutParams queueProgressParams =
                new LinearLayout.LayoutParams(
                        -1,
                        dp(40)
                );

        queueProgressParams.setMargins(
                0,
                0,
                0,
                dp(2)
        );

        player.addView(
                playerQueueProgress,
                queueProgressParams
        );

        /*
         * BARRA DE PROGRESO
         */

        playerSeekBar =
                new SeekBar(this);

        playerSeekBar.setMax(
                1000
        );

        playerSeekBar.setPadding(
                0,
                0,
                0,
                0
        );

        playerSeekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {

                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser) {

                        if (
                                fromUser &&
                                mediaController != null &&
                                mediaController.getDuration() > 0
                        ) {

                            long position =
                                    mediaController.getDuration()
                                            * progress
                                            / 1000L;

                            playerTime.setText(
                                    formatTime(position)
                                            + " / "
                                            + formatTime(
                                            mediaController
                                                    .getDuration()
                                    )
                            );
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(
                            SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(
                            SeekBar seekBar) {

                        if (
                                mediaController != null &&
                                mediaController.getDuration() > 0
                        ) {

                            long position =
                                    mediaController.getDuration()
                                            * seekBar.getProgress()
                                            / 1000L;

                            mediaController.seekTo(
                                    position
                            );
                        }
                    }
                }
        );

        player.addView(
                playerSeekBar
        );

        /*
         * TIEMPOS
         */

        playerTime =
                new TextView(this);

        playerTime.setText(
                "0:00 / 0:00"
        );

        playerTime.setTextSize(
                11
        );

        playerTime.setTextColor(
                Color.LTGRAY
        );

        playerTime.setGravity(
                Gravity.CENTER
        );

        player.addView(
                playerTime
        );

        /*
         * CONTROLES
         */

        LinearLayout controls =
                new LinearLayout(this);

        controls.setOrientation(
                LinearLayout.HORIZONTAL
        );

        controls.setGravity(
                Gravity.CENTER
        );

        previousButton =
                roundedButton();

        previousButton.setText(
                "⏮"
        );

        previousButton.setTextSize(
                17
        );

        previousButton.setOnClickListener(
                v -> {

                    if (mediaController != null) {
                        mediaController
                                .seekToPreviousMediaItem();
                    }
                }
        );

        controls.addView(
                previousButton,
                new LinearLayout.LayoutParams(
                        dp(54),
                        dp(42)
                )
        );

        playPauseButton =
                roundedButton();

        playPauseButton.setText(
                "▶"
        );

        playPauseButton.setTextSize(
                19
        );

        playPauseButton.setOnClickListener(
                v -> togglePlayback()
        );

        LinearLayout.LayoutParams playParams =
                new LinearLayout.LayoutParams(
                        dp(62),
                        dp(44)
                );

        playParams.setMargins(
                dp(8),
                0,
                dp(8),
                0
        );

        controls.addView(
                playPauseButton,
                playParams
        );

        nextButton =
                roundedButton();

        nextButton.setText(
                "⏭"
        );

        nextButton.setTextSize(
                17
        );

        nextButton.setOnClickListener(
                v -> {

                    if (mediaController != null) {
                        mediaController
                                .seekToNextMediaItem();
                    }
                }
        );

        controls.addView(
                nextButton,
                new LinearLayout.LayoutParams(
                        dp(54),
                        dp(42)
                )
        );

        player.addView(
                controls
        );

        /*
         * =========================================================
         * ARRASTRE DEL MINI-PLAYER
         * =========================================================
         *
         * NO MODIFICAR.
         */

        player.setOnTouchListener(
                new android.view.View.OnTouchListener() {

                    float downX;
                    float downY;
                    float startX;
                    float startY;

                    @Override
                    public boolean onTouch(
                            android.view.View v,
                            android.view.MotionEvent event) {

                        switch (event.getActionMasked()) {

                            case android.view.MotionEvent.ACTION_DOWN:

                                downX = event.getRawX();
                                downY = event.getRawY();

                                startX = v.getTranslationX();
                                startY = v.getTranslationY();

                                return true;

                            case android.view.MotionEvent.ACTION_MOVE:

                                float deltaX =
                                        event.getRawX() - downX;

                                float deltaY =
                                        event.getRawY() - downY;

                                v.setTranslationX(
                                        startX + deltaX
                                );

                                v.setTranslationY(
                                        startY + deltaY
                                );

                                return true;

                            case android.view.MotionEvent.ACTION_UP:
                            case android.view.MotionEvent.ACTION_CANCEL:

                                return true;
                        }

                        return true;
                    }
                }
        );

        /*
         * PLAYER FLOTANTE SOBRE EL CONTENIDO
         */

        android.widget.FrameLayout.LayoutParams playerParams =
                new android.widget.FrameLayout.LayoutParams(
                        -1,
                        -2
                );

        playerParams.gravity =
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;

        playerParams.setMargins(
                dp(18),
                0,
                dp(18),
                dp(90)
        );

        contentFrame.addView(
                player,
                playerParams
        );

        /*
         * BOTÓN FLOTANTE PARA RESTAURAR EL MINI-PLAYER
         *
         * Está fuera de "player" para que siga visible
         * cuando el reproductor está minimizado.
         */
        restorePlayerButton =
                roundedButton();

        restorePlayerButton.setText(
                "▶"
        );

        restorePlayerButton.setTextSize(
                17
        );

        restorePlayerButton.setMinHeight(
                dp(52)
        );

        restorePlayerButton.setMinWidth(
                dp(52)
        );

        restorePlayerButton.setPadding(
                0,
                0,
                0,
                0
        );

        restorePlayerButton.setElevation(
                dp(14)
        );

        restorePlayerButton.setVisibility(
                android.view.View.GONE
        );

        restorePlayerButton.setOnClickListener(
                v -> {

                    playerMinimized = false;

                    restorePlayerButton.setVisibility(
                            android.view.View.GONE
                    );

                    if (!playerManuallyClosed) {
                        player.setVisibility(
                                android.view.View.VISIBLE
                        );
                    }

                    updatePlayerUi();
                }
        );

        android.widget.FrameLayout.LayoutParams restoreParams =
                new android.widget.FrameLayout.LayoutParams(
                        dp(52),
                        dp(52)
                );

        restoreParams.gravity =
                Gravity.BOTTOM | Gravity.END;

        restoreParams.setMargins(
                0,
                0,
                dp(18),
                dp(90)
        );

        contentFrame.addView(
                restorePlayerButton,
                restoreParams
        );

        root.addView(
                contentFrame,
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        1
                )
        );

        setContentView(root);
    }

    /*
     * =========================================================
     * NAVEGACIÓN VISUAL
     * =========================================================
     */

    /*
     * =========================================================
     * CAMBIO DE PESTAÑA PERSISTENTE
     * =========================================================
     */

    private void switchContentTab(
            LinearLayout targetLayout,
            ScrollView targetScroll,
            boolean home) {

        if (targetLayout != downloadsTabLayout) {
            restoreServerCardToRoot();
        }

        if (contentFrame == null ||
                targetLayout == null ||
                targetScroll == null) {
            return;
        }

        if (mainTabScroll != null) {
            mainTabScroll.setVisibility(
                    targetScroll == mainTabScroll
                            ? android.view.View.VISIBLE
                            : android.view.View.GONE
            );
        }

        if (spotifyTabScroll != null) {
            spotifyTabScroll.setVisibility(
                    targetScroll == spotifyTabScroll
                            ? android.view.View.VISIBLE
                            : android.view.View.GONE
            );
        }

        if (offlineTabScroll != null) {
            offlineTabScroll.setVisibility(
                    targetScroll == offlineTabScroll
                            ? android.view.View.VISIBLE
                            : android.view.View.GONE
            );
        }

        if (downloadsTabScroll != null) {
            downloadsTabScroll.setVisibility(
                    targetScroll == downloadsTabScroll
                            ? android.view.View.VISIBLE
                            : android.view.View.GONE
            );
        }

        /*
         * MUY IMPORTANTE:
         *
         * contentLayout pasa a ser el contenedor de la
         * pestaña actualmente seleccionada.
         */
        contentLayout = targetLayout;

    // La tarjeta SERVIDOR solo debe ser visible dentro de DESCARGAS.
    if (serverCard != null) {
        serverCard.setVisibility(
                targetLayout == downloadsTabLayout
                        ? android.view.View.VISIBLE
                        : android.view.View.GONE
        );
    }


        if (home) {

            for (int i = 0;
                    i < rootLayout.getChildCount();
                    i++) {

                android.view.View child =
                        rootLayout.getChildAt(i);

                if (child == contentFrame ||
                        child == bottomNavigation) {
                    continue;
                }

                child.setVisibility(
                        android.view.View.VISIBLE
                );
            }

        } else {

            hideMainHome();
        }
    }

    private void restoreServerCardToRoot() {
        // La tarjeta SERVIDOR pertenece exclusivamente a DESCARGAS.
        // No se devuelve nunca al root principal.
    }

    private void showMainHome() {

        if (rootLayout == null ||
                mainTabLayout == null ||
                mainTabScroll == null) {
            return;
        }

        switchContentTab(
                mainTabLayout,
                mainTabScroll,
                true
        );

        /*
         * Solo construimos los resultados la primera vez.
         *
         * Al volver desde otra pestaña NO se ejecuta
         * removeAllViews(), por lo que los DownloadSlot
         * siguen siendo los mismos objetos.
         */
        if (mainTabLayout.getChildCount() == 0 &&
                currentSearchSongs != null &&
                !currentSearchSongs.isEmpty()) {

            renderSearchSongsPage();
        }
    }

    private void hideMainHome() {

        if (rootLayout == null) {
            return;
        }

        for (int i = 0; i < rootLayout.getChildCount(); i++) {

            android.view.View child =
                    rootLayout.getChildAt(i);

            if (child == contentFrame ||
                    child == bottomNavigation) {
                continue;
            }

            child.setVisibility(
                    android.view.View.GONE
            );
        }
    }

    private void connectMediaController() {

        SessionToken token =
                new SessionToken(
                        this,
                        new ComponentName(
                                this,
                                PlayerService.class
                        )
                );

        MediaController.Builder builder =
                new MediaController.Builder(
                        this,
                        token
                );

        com.google.common.util.concurrent.ListenableFuture<MediaController>
                future =
                builder.buildAsync();

        future.addListener(
                () -> {

                    try {

                        mediaController =
                                future.get();

                    } catch (Exception e) {

                        mediaController = null;
                    }

                },
                getMainExecutor()
        );
    }

    private void sendQueueToPlayer(
            List<MediaItem> items) {

        if (items == null ||
                items.isEmpty()) {

            Toast.makeText(
                    this,
                    "La cola está vacía",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        if (mediaController == null) {

            Toast.makeText(
                    this,
                    "Reproductor no conectado",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        QueueRepository.setQueue(items);

        SessionCommand command =
                new SessionCommand(
                        PlayerService.COMMAND_PLAY_QUEUE,
                        Bundle.EMPTY
                );

        mediaController.sendCustomCommand(
                command,
                Bundle.EMPTY
        );
    }

    private void addItemsToPlayerQueue(
            List<MediaItem> items) {

        if (items == null ||
                items.isEmpty()) {
            return;
        }

        if (mediaController == null) {
            return;
        }

        QueueRepository.addItems(items);

        SessionCommand command =
                new SessionCommand(
                        PlayerService.COMMAND_ADD_QUEUE,
                        Bundle.EMPTY
                );

        mediaController.sendCustomCommand(
                command,
                Bundle.EMPTY
        );
    }

    private void saveServerUrl() {

        String url =
                serverInput
                        .getText()
                        .toString()
                        .trim();

        if (
                !url.startsWith("http://") &&
                !url.startsWith("https://")
        ) {

            serverStatus.setText(
                    "🔴 URL no válida"
            );

            serverStatus.setTextColor(
                    Color.rgb(255, 90, 100)
            );

            Toast.makeText(
                    this,
                    "URL no válida",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        while (url.endsWith("/")) {

            url =
                    url.substring(
                            0,
                            url.length() - 1
                    );
        }

        serverInput.setText(url);

        getSharedPreferences(
                "settings",
                MODE_PRIVATE
        )
                .edit()
                .putString(
                        "server_url",
                        url
                )
                .apply();

        checkServerConnection();
    }

    private void checkServerConnection() {

        String url =
                serverInput
                        .getText()
                        .toString()
                        .trim();

        if (
                !url.startsWith("http://") &&
                !url.startsWith("https://")
        ) {

            serverStatus.setText(
                    "🔴 URL no válida"
            );

            serverStatus.setTextColor(
                    Color.rgb(255, 90, 100)
            );

            return;
        }

        while (url.endsWith("/")) {

            url =
                    url.substring(
                            0,
                            url.length() - 1
                    );
        }

        final String serverUrl =
                url;

        serverStatus.setText(
                "🟡 Comprobando..."
        );

        serverStatus.setTextColor(
                Color.rgb(235, 190, 70)
        );

        executor.execute(() -> {

            boolean connected = false;

            try {

                java.net.URL testUrl =
                        new java.net.URL(
                                serverUrl + "/"
                        );

                java.net.HttpURLConnection connection =
                        (java.net.HttpURLConnection)
                                testUrl.openConnection();

                connection.setRequestMethod(
                        "GET"
                );

                connection.setConnectTimeout(
                        5000
                );

                connection.setReadTimeout(
                        5000
                );

                int code =
                        connection.getResponseCode();

                connected =
                        code >= 200 &&
                        code < 500;

                connection.disconnect();

            } catch (Exception ignored) {

                connected = false;
            }

            final boolean result =
                    connected;

            getSharedPreferences(
                    "settings",
                    MODE_PRIVATE
            )
                    .edit()
                    .putString(
                            "server_checked_url",
                            serverUrl
                    )
                    .putBoolean(
                            "server_checked",
                            true
                    )
                    .putBoolean(
                            "server_connected",
                            result
                    )
                    .apply();

            handler.post(() -> {

                if (result) {

                    serverStatus.setText(
                            "🟢 Conectado"
                    );

                    serverStatus.setTextColor(
                            Color.rgb(55, 215, 125)
                    );

                } else {

                    serverStatus.setText(
                            "🔴 Sin conexión"
                    );

                    serverStatus.setTextColor(
                            Color.rgb(255, 90, 100)
                    );
                }
            });
        });
    }

    private ApiClient api() {

        String url =
                serverInput
                        .getText()
                        .toString()
                        .trim();

        while (url.endsWith("/")) {

            url =
                    url.substring(
                            0,
                            url.length() - 1
                    );
        }

        return new ApiClient(url);
    }

    /*
     * ============================================================
     * CANCIONES
     * ============================================================
     */

    private void searchSongs() {

        showMainHome();

        String query =
                searchInput
                        .getText()
                        .toString()
                        .trim();

        if (query.isEmpty()) return;

        contentLayout.removeAllViews();

        LinearLayout loading =
                createSearchLoading(
                        "Buscando canciones"
                );

        contentLayout.addView(loading);

        executor.execute(() -> {

            try {

                List<ApiClient.Song> songs =
                        api().searchSongs(query);

                currentSearchSongs = songs;
                currentSearchPage = 0;

                handler.post(
                        () -> showSongs(songs)
                );

            } catch (Exception e) {

                handler.post(
                        () -> showError(e)
                );
            }
        });
    }

    private LinearLayout createSearchLoading(
            String message) {

        LinearLayout container =
                new LinearLayout(this);

        container.setOrientation(
                LinearLayout.VERTICAL
        );

        container.setGravity(
                Gravity.CENTER
        );

        container.setPadding(
                dp(20),
                dp(70),
                dp(20),
                dp(70)
        );

        /*
         * ECUALIZADOR ANIMADO
         */

        LinearLayout visualizer =
                new LinearLayout(this);

        visualizer.setOrientation(
                LinearLayout.HORIZONTAL
        );

        visualizer.setGravity(
                Gravity.CENTER
        );

        int[] heights = {
                22, 42, 30, 54, 34,
                46, 26
        };

        for (int height : heights) {

            View bar =
                    new View(this);

            GradientDrawable background =
                    new GradientDrawable();

            background.setColor(
                    Color.rgb(145, 105, 255)
            );

            background.setCornerRadius(
                    dp(8)
            );

            bar.setBackground(background);

            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            dp(7),
                            dp(height)
                    );

            params.setMargins(
                    dp(4),
                    0,
                    dp(4),
                    0
            );

            visualizer.addView(
                    bar,
                    params
            );

            ObjectAnimator animator =
                    ObjectAnimator.ofFloat(
                            bar,
                            "scaleY",
                            0.35f,
                            1.0f,
                            0.45f,
                            0.85f,
                            0.35f
                    );

            animator.setDuration(
                    900 + (height * 7L)
            );

            animator.setRepeatCount(
                    ValueAnimator.INFINITE
            );

            animator.setRepeatMode(
                    ValueAnimator.REVERSE
            );

            animator.setStartDelay(
                    visualizer.getChildCount() * 90L
            );

            animator.start();
        }

        container.addView(
                visualizer,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(70)
                )
        );

        /*
         * TEXTO
         */

        TextView loadingText =
                new TextView(this);

        loadingText.setText(message);

        loadingText.setTextSize(17);

        loadingText.setTypeface(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
        );

        loadingText.setTextColor(
                Color.rgb(205, 205, 215)
        );

        loadingText.setGravity(
                Gravity.CENTER
        );

        LinearLayout.LayoutParams textParams =
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                );

        textParams.setMargins(
                0,
                dp(18),
                0,
                0
        );

        container.addView(
                loadingText,
                textParams
        );

        /*
         * SUBTÍTULO SUTIL
         */

        TextView subtitle =
                new TextView(this);

        subtitle.setText(
                "Preparando resultados..."
        );

        subtitle.setTextSize(13);

        subtitle.setTextColor(
                Color.rgb(125, 125, 140)
        );

        subtitle.setGravity(
                Gravity.CENTER
        );

        LinearLayout.LayoutParams subtitleParams =
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                );

        subtitleParams.setMargins(
                0,
                dp(6),
                0,
                0
        );

        container.addView(
                subtitle,
                subtitleParams
        );

        AlphaAnimation pulse =
                new AlphaAnimation(
                        0.45f,
                        1.0f
                );

        pulse.setDuration(850);

        pulse.setRepeatMode(
                Animation.REVERSE
        );

        pulse.setRepeatCount(
                Animation.INFINITE
        );

        subtitle.startAnimation(pulse);

        return container;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable roundedBackground(
            int color,
            int strokeColor,
            int radiusDp) {

        GradientDrawable drawable =
                new GradientDrawable();

        drawable.setColor(color);

        drawable.setCornerRadius(
                dp(radiusDp)
        );

        if (strokeColor != Color.TRANSPARENT) {
            drawable.setStroke(
                    dp(1),
                    strokeColor
            );
        }

        return drawable;
    }

    private void addMainButton(
            LinearLayout parent,
            Button button) {

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                );

        params.setMargins(
                dp(12),
                dp(14),
                dp(12),
                dp(14)
        );

        parent.addView(
                button,
                params
        );
    }

    private Button roundedButton() {

        Button button =
                new Button(this);

        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);

        button.setGravity(
                android.view.Gravity.CENTER
        );

        button.setPadding(
                dp(16),
                dp(4),
                dp(16),
                dp(4)
        );

        button.setMinHeight(dp(48));
        button.setMinWidth(0);

        button.setBackground(
                roundedBackground(
                        Color.rgb(32, 32, 40),
                        Color.rgb(82, 82, 98),
                        18
                )
        );

        button.setElevation(dp(6));
        button.setStateListAnimator(null);

        return button;
    }

    private Button visualButton(
            String text) {

        Button button =
                roundedButton();

        button.setText(text);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);

        button.setGravity(
                android.view.Gravity.CENTER
        );

        button.setPadding(
                dp(14),
                dp(4),
                dp(14),
                dp(4)
        );

        button.setMinHeight(dp(46));
        button.setMinWidth(0);

        button.setBackground(
                roundedBackground(
                        Color.rgb(42, 42, 52),
                        Color.rgb(96, 96, 116),
                        17
                )
        );

        button.setElevation(dp(5));
        button.setStateListAnimator(null);

        return button;
    }


private void loadOfflineThumbnail(
        String thumbnail,
        String videoId,
        ImageView imageView) {

    executor.execute(() -> {

        Bitmap bitmap = null;

        /*
         * 1. Intentar primero la portada guardada
         */
        if (thumbnail != null &&
                !thumbnail.trim().isEmpty()) {

            HttpURLConnection connection = null;

            try {

                URL url =
                        new URL(thumbnail);

                connection =
                        (HttpURLConnection)
                                url.openConnection();

                connection.setConnectTimeout(
                        8000
                );

                connection.setReadTimeout(
                        8000
                );

                connection.setInstanceFollowRedirects(
                        true
                );

                connection.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0"
                );

                connection.connect();

                int responseCode =
                        connection.getResponseCode();

                if (responseCode >= 200 &&
                        responseCode < 300) {

                    try (InputStream input =
                                 connection.getInputStream()) {

                        bitmap =
                                BitmapFactory.decodeStream(
                                        input
                                );
                    }
                }

            } catch (Exception ignored) {

            } finally {

                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        /*
         * 2. Si falla, utilizar la portada
         * directamente desde YouTube
         * usando el videoId.
         */
        if (bitmap == null &&
                videoId != null &&
                !videoId.trim().isEmpty()) {

            HttpURLConnection connection = null;

            try {

                String youtubeUrl =
                        "https://i.ytimg.com/vi/"
                                + videoId.trim()
                                + "/hqdefault.jpg";

                URL url =
                        new URL(youtubeUrl);

                connection =
                        (HttpURLConnection)
                                url.openConnection();

                connection.setConnectTimeout(
                        8000
                );

                connection.setReadTimeout(
                        8000
                );

                connection.setInstanceFollowRedirects(
                        true
                );

                connection.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0"
                );

                connection.connect();

                int responseCode =
                        connection.getResponseCode();

                if (responseCode >= 200 &&
                        responseCode < 300) {

                    try (InputStream input =
                                 connection.getInputStream()) {

                        bitmap =
                                BitmapFactory.decodeStream(
                                        input
                                );
                    }
                }

            } catch (Exception ignored) {

            } finally {

                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        Bitmap result = bitmap;

        handler.post(() -> {

            if (result != null) {

                imageView.setImageBitmap(
                        result
                );

            } else {

                imageView.setImageResource(
                        android.R.drawable.ic_media_play
                );
            }
        });
    });
}


private void loadThumbnail(
            String thumbnail,
            ImageView imageView) {

        if (thumbnail == null ||
                thumbnail.trim().isEmpty()) {

            imageView.setImageResource(
                    android.R.drawable.ic_media_play
            );

            return;
        }

        executor.execute(() -> {

            Bitmap bitmap = null;

            HttpURLConnection connection = null;

            try {

                URL url =
                        new URL(thumbnail);

                connection =
                        (HttpURLConnection)
                                url.openConnection();

                connection.setConnectTimeout(
                        8000
                );

                connection.setReadTimeout(
                        8000
                );

                connection.setInstanceFollowRedirects(
                        true
                );

                connection.connect();

                try (InputStream input =
                             connection.getInputStream()) {

                    bitmap =
                            BitmapFactory.decodeStream(
                                    input
                            );
                }

            } catch (Exception ignored) {

            } finally {

                if (connection != null) {
                    connection.disconnect();
                }
            }

            Bitmap result = bitmap;

            handler.post(() -> {

                if (result != null) {

                    imageView.setImageBitmap(
                            result
                    );

                } else {

                    imageView.setImageResource(
                            android.R.drawable.ic_media_play
                    );
                }
            });
        });
    }

    private void loadSpotifyThumbnail(
            ApiClient.SpotifyTrack track,
            ImageView imageView) {

        imageView.setImageDrawable(null);

        if (track == null) {

            imageView.setImageResource(
                    android.R.drawable.ic_menu_gallery
            );

            return;
        }

        String thumbnail =
                track.thumbnail == null
                        ? ""
                        : track.thumbnail.trim();

        /*
         * Si el CSV ya trae portada, usamos la existente.
         * No cambiamos este comportamiento.
         */
        if (!thumbnail.isEmpty()) {

            loadSpotifyThumbnailUrl(
                    thumbnail,
                    imageView
            );

            return;
        }

        /*
         * El CSV no tiene portada.
         * Intentamos localizarla usando artista + canción.
         */
        imageView.setImageResource(
                android.R.drawable.ic_menu_gallery
        );

        executor.execute(() -> {

            String artworkUrl =
                    findSpotifyArtwork(
                            track.artist,
                            track.title
                    );

            if (artworkUrl == null ||
                    artworkUrl.trim().isEmpty()) {

                return;
            }

            loadSpotifyThumbnailUrl(
                    artworkUrl,
                    imageView
            );
        });
    }


    private String findSpotifyArtwork(
            String artist,
            String title) {

        HttpURLConnection connection = null;

        try {

            String query =
                    ((artist == null ? "" : artist)
                            + " "
                            + (title == null ? "" : title))
                            .trim();

            if (query.isEmpty()) {
                return "";
            }

            String encoded =
                    URLEncoder.encode(
                            query,
                            "UTF-8"
                    );

            URL url =
                    new URL(
                            "https://itunes.apple.com/search"
                                    + "?term="
                                    + encoded
                                    + "&entity=song"
                                    + "&limit=5"
                                    + "&country=ES"
                    );

            connection =
                    (HttpURLConnection)
                            url.openConnection();

            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(true);

            connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0"
            );

            connection.connect();

            try (InputStream input =
                         connection.getInputStream()) {

                byte[] data =
                        input.readAllBytes();

                String json =
                        new String(
                                data,
                                java.nio.charset.StandardCharsets.UTF_8
                        );

                JSONObject root =
                        new JSONObject(json);

                JSONArray results =
                        root.optJSONArray("results");

                if (results == null) {
                    return "";
                }

                for (int i = 0;
                     i < results.length();
                     i++) {

                    JSONObject item =
                            results.optJSONObject(i);

                    if (item == null) {
                        continue;
                    }

                    String artwork =
                            item.optString(
                                    "artworkUrl100",
                                    ""
                            );

                    if (!artwork.isEmpty()) {

                        /*
                         * Pedimos una imagen mayor cuando el
                         * servicio proporciona artworkUrl100.
                         */
                        artwork =
                                artwork.replace(
                                        "100x100bb",
                                        "600x600bb"
                                );

                        return artwork;
                    }
                }
            }

        } catch (Exception ignored) {

        } finally {

            if (connection != null) {
                connection.disconnect();
            }
        }

        return "";
    }


    private void loadSpotifyThumbnailUrl(
            String thumbnail,
            ImageView imageView) {

        executor.execute(() -> {

            Bitmap bitmap = null;

            HttpURLConnection connection = null;

            try {

                URL url =
                        new URL(thumbnail);

                connection =
                        (HttpURLConnection)
                                url.openConnection();

                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(true);

                connection.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0"
                );

                connection.connect();

                try (InputStream input =
                             connection.getInputStream()) {

                    bitmap =
                            BitmapFactory.decodeStream(
                                    input
                            );
                }

            } catch (Exception ignored) {

            } finally {

                if (connection != null) {
                    connection.disconnect();
                }
            }

            Bitmap result = bitmap;

            handler.post(() -> {

                if (result != null) {

                    imageView.setImageBitmap(result);

                } else {

                    imageView.setImageResource(
                            android.R.drawable.ic_menu_gallery
                    );
                }
            });
        });
    }

    private void downloadSelectedSpotifyOffline() {

        List<DownloadSlot> selected =
                new ArrayList<>();

        for (int i = 0;
             i < spotifySelectionChecks.size();
             i++) {

            if (spotifySelectionChecks.get(i).isChecked()) {

                if (i < spotifyOfflineSlots.size()) {

                    selected.add(
                            spotifyOfflineSlots.get(i)
                    );
                }
            }
        }

        if (selected.isEmpty()) {

            Toast.makeText(
                    this,
                    "☑ Selecciona al menos una canción",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        if (offlineFolderUri == null) {

            Toast.makeText(
                    this,
                    "📂 Selecciona primero la carpeta offline",
                    Toast.LENGTH_SHORT
            ).show();

            chooseOfflineFolder();

            return;
        }

        Toast.makeText(
                this,
                "⬇ Descargando "
                        + selected.size()
                        + " canciones offline",
                Toast.LENGTH_SHORT
        ).show();

        for (DownloadSlot slot : selected) {

            startMobileDownloadSpotifyTrack(
                    slot.track,
                    slot
            );
        }
    }


    private List<ApiClient.Song> currentSearchSongs =
            new ArrayList<>();

    private int currentSearchPage = 0;

    private static final int SEARCH_PAGE_SIZE = 10;

    private void showSongs(
            List<ApiClient.Song> songs) {

        /*
         * Guardamos la lista completa.
         * La paginación solo controla qué parte se muestra.
         */
        if (songs != null) {
            currentSearchSongs = songs;

            /*
             * Es una búsqueda nueva: las canciones obtenidas de la
             * búsqueda anterior no deben aparecer como obtenidas.
             */
            searchObtainedVideoIds.clear();
        }

        renderSearchSongsPage();
    }

    private void renderSearchSongsPage() {

        contentLayout.removeAllViews();

        List<ApiClient.Song> songs =
                currentSearchSongs;

        if (songs == null) {
            songs = new ArrayList<>();
        }

        if (songs.isEmpty()) {

            TextView empty =
                    new TextView(this);

            empty.setText(
                    "No se encontraron canciones"
            );

            empty.setTextSize(16);

            empty.setTextColor(
                    Color.LTGRAY
            );

            empty.setGravity(
                    Gravity.CENTER
            );

            empty.setPadding(
                    dp(20),
                    dp(30),
                    dp(20),
                    dp(30)
            );

            contentLayout.addView(
                    empty
            );

            return;
        }

        /*
         * ============================================================
         * CABECERA
         * ============================================================
         */

        LinearLayout header =
                new LinearLayout(this);

        header.setOrientation(
                LinearLayout.VERTICAL
        );

        header.setPadding(
                dp(12),
                dp(8),
                dp(12),
                dp(4)
        );

        TextView resultCount =
                new TextView(this);

        resultCount.setText(
                "🎵 " + songs.size() +
                " resultados encontrados"
        );

        resultCount.setTextSize(16);
        resultCount.setTextColor(
                Color.WHITE
        );

        header.addView(
                resultCount
        );

        /*
         * ============================================================
         * REPRODUCIR TODO
         * ============================================================
         *
         * IMPORTANTE:
         * usa TODOS los resultados, no solamente la página visible.
         */

        final List<ApiClient.Song> allSearchSongs =
                songs;

        Button playAll =
                roundedButton();

        playAll.setText(
                "▶ REPRODUCIR TODO"
        );

        LinearLayout.LayoutParams playAllParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        playAllParams.setMargins(
                0,
                dp(8),
                0,
                dp(8)
        );

        playAll.setOnClickListener(
                v -> playSongsQueue(allSearchSongs)
        );

        header.addView(
                playAll,
                playAllParams
        );

        contentLayout.addView(
                header
        );

        /*
         * ============================================================
         * PAGINACIÓN
         * ============================================================
         */

        final int totalPages =
                (songs.size() +
                        SEARCH_PAGE_SIZE - 1)
                        / SEARCH_PAGE_SIZE;

        if (currentSearchPage >= totalPages) {
            currentSearchPage =
                    Math.max(0, totalPages - 1);
        }

        int startIndex =
                currentSearchPage *
                SEARCH_PAGE_SIZE;

        int endIndex =
                Math.min(
                        startIndex +
                        SEARCH_PAGE_SIZE,
                        songs.size()
                );

        /*
         * ============================================================
         * RESULTADOS DE LA PÁGINA ACTUAL
         * ============================================================
         */

        for (
                int index = startIndex;
                index < endIndex;
                index++
        ) {

            ApiClient.Song song =
                    songs.get(index);

            LinearLayout card =
                    new LinearLayout(this);

            card.setOrientation(
                    LinearLayout.VERTICAL
            );

            card.setPadding(
                    dp(12),
                    dp(12),
                    dp(12),
                    dp(12)
            );

            card.setBackground(
                    roundedBackground(
                            Color.rgb(25, 25, 32),
                            Color.rgb(68, 68, 82),
                            20
                    )
            );

            card.setElevation(
                    dp(4)
            );

            LinearLayout.LayoutParams cardParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );

            cardParams.setMargins(
                    0,
                    dp(6),
                    0,
                    dp(6)
            );

            LinearLayout top =
                    new LinearLayout(this);

            top.setOrientation(
                    LinearLayout.HORIZONTAL
            );

            ImageView cover =
                    new ImageView(this);

            LinearLayout.LayoutParams coverParams =
                    new LinearLayout.LayoutParams(
                            dp(100),
                            dp(100)
                    );

            coverParams.setMargins(
                    0,
                    0,
                    dp(14),
                    0
            );

            cover.setScaleType(
                    ImageView.ScaleType.CENTER_CROP
            );

            cover.setBackground(
                    roundedBackground(
                            Color.rgb(42, 42, 49),
                            Color.TRANSPARENT,
                            14
                    )
            );

            cover.setClipToOutline(true);

            cover.setImageResource(
                    android.R.drawable.ic_media_play
            );

            top.addView(
                    cover,
                    coverParams
            );

            LinearLayout info =
                    new LinearLayout(this);

            info.setOrientation(
                    LinearLayout.VERTICAL
            );

            info.setGravity(
                    Gravity.CENTER_VERTICAL
            );

            LinearLayout.LayoutParams infoParams =
                    new LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                    );

            TextView title =
                    new TextView(this);

            title.setText(
                    song.title
            );

            title.setTextSize(17);
            title.setTextColor(
                    Color.WHITE
            );

            title.setTypeface(
                    null,
                    android.graphics.Typeface.BOLD
            );

            title.setMaxLines(2);

            title.setEllipsize(
                    android.text.TextUtils.TruncateAt.END
            );

            info.addView(title);

            TextView artist =
                    new TextView(this);

            String artistText =
                    song.artist == null ||
                    song.artist.trim().isEmpty()
                    ? song.channel
                    : song.artist;

            String durationText =
                    song.duration > 0
                    ? formatTime(song.duration * 1000L)
                    : "--:--";

            artist.setText(
                    artistText +
                    "  ·  ⏱ " +
                    durationText
            );

            artist.setTextSize(13);
            artist.setTextColor(
                    Color.rgb(170, 170, 182)
            );

            artist.setMaxLines(1);

            artist.setEllipsize(
                    android.text.TextUtils.TruncateAt.END
            );

            LinearLayout.LayoutParams artistParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );

            artistParams.setMargins(
                    0,
                    dp(5),
                    0,
                    0
            );

            info.addView(
                    artist,
                    artistParams
            );

            /*
             * ESTADO OBTENIDA
             *
             * Exactamente el mismo componente visual usado por
             * álbumes y Spotify.
             */
            TextView obtainedStatus =
                    createObtainedStatusView();

            /*
             * Guardamos el ID en la propia vista para poder localizar
             * exactamente esta tarjeta cuando la canción se prepare.
             */
            obtainedStatus.setTag(
                    song.id
            );

            if (
                    song.id != null &&
                    searchObtainedVideoIds.contains(song.id)
            ) {
                obtainedStatus.setVisibility(
                        android.view.View.VISIBLE
                );
            }

            searchPreparationStatusViews.add(
                    obtainedStatus
            );

            info.addView(
                    obtainedStatus
            );

            top.addView(
                    info,
                    infoParams
            );

            card.addView(top);

            /*
             * ========================================================
             * BOTONES
             * ========================================================
             */

            LinearLayout buttons =
                    new LinearLayout(this);

            buttons.setOrientation(
                    LinearLayout.HORIZONTAL
            );

            buttons.setGravity(
                    Gravity.CENTER_VERTICAL
            );

            LinearLayout.LayoutParams buttonsParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );

            buttonsParams.setMargins(
                    0,
                    dp(10),
                    0,
                    0
            );

            Button play =
                    visualButton(
                            "▶  Escuchar"
                    );

            play.setTextSize(13);
            play.setTypeface(
                    null,
                    android.graphics.Typeface.BOLD
            );
            play.setBackground(
                    roundedBackground(
                            Color.rgb(70, 70, 88),
                            Color.rgb(125, 125, 150),
                            18
                    )
            );
            play.setElevation(
                    dp(7)
            );

            LinearLayout.LayoutParams playParams =
                    new LinearLayout.LayoutParams(
                            0,
                            dp(42),
                            1f
                    );

            playParams.setMargins(
                    0,
                    0,
                    dp(6),
                    0
            );

            final int finalSongIndex =
                    index;

            final List<ApiClient.Song> finalSongs =
                    songs;

            play.setOnClickListener(
                    v -> playSongsQueue(
                            finalSongs.subList(
                                    finalSongIndex,
                                    finalSongs.size()
                            )
                    )
            );

            buttons.addView(
                    play,
                    playParams
            );

            Button download =
                    visualButton(
                            "⬇  Descargar"
                    );

            download.setTextSize(12);
            download.setTextColor(
                    Color.rgb(225, 225, 235)
            );
            download.setBackground(
                    roundedBackground(
                            Color.rgb(38, 38, 48),
                            Color.rgb(82, 82, 100),
                            18
                    )
            );
            download.setElevation(
                    dp(5)
            );

            LinearLayout.LayoutParams downloadParams =
                    new LinearLayout.LayoutParams(
                            0,
                            dp(42),
                            1f
                    );

            downloadParams.setMargins(
                    dp(6),
                    0,
                    0,
                    0
            );

            buttons.addView(
                    download,
                    downloadParams
            );

            Button offline =
                    visualButton(
                            "📱  Offline"
                    );

            offline.setTextSize(12);
            offline.setTextColor(
                    Color.rgb(225, 225, 235)
            );
            offline.setBackground(
                    roundedBackground(
                            Color.rgb(34, 40, 48),
                            Color.rgb(78, 96, 112),
                            18
                    )
            );
            offline.setElevation(
                    dp(5)
            );

            LinearLayout.LayoutParams offlineParams =
                    new LinearLayout.LayoutParams(
                            0,
                            dp(42),
                            1f
                    );

            offlineParams.setMargins(
                    dp(6),
                    0,
                    0,
                    0
            );

            buttons.addView(
                    offline,
                    offlineParams
            );

            card.addView(
                    buttons,
                    buttonsParams
            );

            ProgressBar progress =
                    new ProgressBar(
                            this,
                            null,
                            android.R.attr.progressBarStyleHorizontal
                    );

            progress.setMax(100);
            progress.setProgress(0);

            LinearLayout.LayoutParams progressParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(4)
                    );

            progressParams.setMargins(
                    0,
                    dp(10),
                    0,
                    0
            );

            card.addView(
                    progress,
                    progressParams
            );

            TextView status =
                    new TextView(this);

            status.setText(
                    "Listo para descargar"
            );

            status.setTextSize(12);

            status.setTextColor(
                    Color.rgb(145, 145, 155)
            );

            LinearLayout.LayoutParams statusParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );

            statusParams.setMargins(
                    0,
                    dp(5),
                    0,
                    0
            );

            card.addView(
                    status,
                    statusParams
            );

            DownloadSlot slot =
                    new DownloadSlot(
                            song.id,
                            song.title,
                            download,
                            progress,
                            status
                    );

            download.setOnClickListener(
                    v -> startNormalDownload(slot)
            );

            offline.setOnClickListener(
                    v -> startMobileDownload(slot)
            );

            contentLayout.addView(
                    card,
                    cardParams
            );

            loadThumbnail(
                    song.thumbnail,
                    cover
            );
        }

        /*
         * ============================================================
         * NAVEGACIÓN
         * ============================================================
         */

        if (totalPages > 1) {

            LinearLayout navigation =
                    new LinearLayout(this);

            navigation.setOrientation(
                    LinearLayout.HORIZONTAL
            );

            navigation.setGravity(
                    Gravity.CENTER
            );

            navigation.setPadding(
                    dp(12),
                    dp(16),
                    dp(12),
                    dp(20)
            );

            Button previous =
                    roundedButton();

            previous.setText(
                    "◀ ANTERIOR"
            );

            previous.setEnabled(
                    currentSearchPage > 0
            );

            LinearLayout.LayoutParams previousParams =
                    new LinearLayout.LayoutParams(
                            0,
                            dp(48),
                            1f
                    );

            previousParams.setMargins(
                    0,
                    0,
                    dp(6),
                    0
            );

            previous.setOnClickListener(
                    v -> {

                        if (currentSearchPage > 0) {

                            currentSearchPage--;

                            renderSearchSongsPage();
                        }
                    }
            );

            navigation.addView(
                    previous,
                    previousParams
            );

            TextView page =
                    new TextView(this);

            page.setText(
                    "Página " +
                    (currentSearchPage + 1) +
                    " / " +
                    totalPages
            );

            page.setTextSize(14);
            page.setTextColor(
                    Color.WHITE
            );

            page.setGravity(
                    Gravity.CENTER
            );

            LinearLayout.LayoutParams pageParams =
                    new LinearLayout.LayoutParams(
                            0,
                            dp(48),
                            1f
                    );

            navigation.addView(
                    page,
                    pageParams
            );

            Button next =
                    roundedButton();

            next.setText(
                    "SIGUIENTE ▶"
            );

            next.setEnabled(
                    currentSearchPage <
                    totalPages - 1
            );

            LinearLayout.LayoutParams nextParams =
                    new LinearLayout.LayoutParams(
                            0,
                            dp(48),
                            1f
                    );

            nextParams.setMargins(
                    dp(6),
                    0,
                    0,
                    0
            );

            next.setOnClickListener(
                    v -> {

                        if (
                                currentSearchPage <
                                totalPages - 1
                        ) {

                            currentSearchPage++;

                            renderSearchSongsPage();
                        }
                    }
            );

            navigation.addView(
                    next,
                    nextParams
            );

            contentLayout.addView(
                    navigation
            );
        }
    }



    private void playSongsQueue(
            List<ApiClient.Song> songs) {

        if (songs == null || songs.isEmpty()) {
            return;
        }

        /*
         * Cada reproducción de cola obtiene su propia generación.
         * Esto evita que una preparación anterior pueda actualizar
         * el estado visual de una cola nueva.
         */
        final long queueGeneration =
                beginQueuePreparation(
                        songs.size()
                );

        final long playbackGeneration =
                beginIndividualPlayback();

        Toast.makeText(
                this,
                "Preparando primera canción...",
                Toast.LENGTH_SHORT
        ).show();

        executor.execute(() -> {

            boolean firstStarted = false;
            int preparedCount = 0;

            for (ApiClient.Song song : songs) {

                try {

                    String videoId = song.id;

                    if (
                            videoId == null ||
                            videoId.trim().isEmpty()
                    ) {
                        continue;
                    }

                    String url =
                            api().getPreviewUrl(
                                    videoId
                            );

                    if (
                            url == null ||
                            url.trim().isEmpty()
                    ) {
                        continue;
                    }

                    MediaItem item =
                            new MediaItem.Builder()
                                    .setUri(url)
                                    .setMediaMetadata(
                                            new MediaMetadata.Builder()
                                                    .setTitle(
                                                            song.title
                                                    )
                                                    .setArtist(
                                                            song.artist == null ||
                                                            song.artist.trim().isEmpty()
                                                                    ? song.channel
                                                                    : song.artist
                                                    )
                                                    .setAlbumTitle(
                                                            song.album
                                                    )
                                                    .setArtworkUri(
                                                            song.thumbnail == null ||
                                                            song.thumbnail.trim().isEmpty()
                                                                    ? null
                                                                    : Uri.parse(song.thumbnail)
                                                    )
                                                    .build()
                                    )
                                    .build();

                    /*
                     * La canción ya tiene su URL de reproducción resuelta.
                     * La marcamos como OBTENIDA inmediatamente.
                     */
                    final String preparedVideoId =
                            videoId;

                    preparedCount++;

                    final int finalPreparedCount =
                            preparedCount;

                    handler.post(() -> {

                        if (!isCurrentQueuePreparation(
                                queueGeneration)) {
                            return;
                        }

                        /*
                         * Guardamos el estado por ID para que permanezca
                         * aunque el usuario cambie de página.
                         */
                        searchObtainedVideoIds.add(
                                preparedVideoId
                        );

                        /*
                         * Si la tarjeta está actualmente visible,
                         * mostramos inmediatamente ✓ OBTENIDA.
                         */
                        for (
                                TextView status :
                                searchPreparationStatusViews
                        ) {

                            if (status == null) {
                                continue;
                            }

                            Object tag =
                                    status.getTag();

                            if (
                                    preparedVideoId.equals(tag)
                            ) {

                                status.setText(
                                        "✓ OBTENIDA"
                                );

                                status.setVisibility(
                                        android.view.View.VISIBLE
                                );

                                break;
                            }
                        }

                        /*
                         * Mismo contador de preparación utilizado por
                         * álbumes y Spotify.
                         */
                        updateQueuePreparationProgress(
                                queueGeneration,
                                finalPreparedCount,
                                songs.size()
                        );
                    });

                    if (!firstStarted) {

                        firstStarted = true;

                        List<MediaItem> first =
                                new ArrayList<>();

                        first.add(item);

                        handler.post(
                                () -> {

                                    if (!isCurrentIndividualPlayback(
                                            playbackGeneration)) {
                                        return;
                                    }

                                    sendQueueToPlayer(
                                            first
                                    );
                                }
                        );

                    } else {

                        List<MediaItem> next =
                                new ArrayList<>();

                        next.add(item);

                        handler.post(
                                () -> {

                                    if (!isCurrentIndividualPlayback(
                                            playbackGeneration)) {
                                        return;
                                    }

                                    addItemsToPlayerQueue(
                                            next
                                    );
                                }
                        );
                    }

                } catch (Exception ignored) {

                    /*
                     * Si una canción falla, continúa con la siguiente.
                     */
                }
            }

            /*
             * La preparación ha terminado.
             *
             * No ocultamos el estado ✓ OBTENIDA de las tarjetas.
             * Solo ocultamos el contador de preparación del mini-player.
             */
            handler.post(() -> {

                if (!isCurrentQueuePreparation(
                        queueGeneration)) {
                    return;
                }

                hideQueuePreparationProgress();
            });
        });
    }

    private void playSong(
            String videoId,
            String title,
            String thumbnail) {

        Toast.makeText(
                this,
                "Preparando: " + title,
                Toast.LENGTH_SHORT
        ).show();

        executor.execute(() -> {

            try {

                String url =
                        api().getPreviewUrl(
                                videoId
                        );

                handler.post(
                        () -> playResolved(
                                url,
                                title,
                                thumbnail
                        )
                );

            } catch (Exception e) {

                handler.post(
                        () -> showError(e)
                );
            }
        });
    }

    /*
     * ============================================================
     * ÁLBUMES
     * ============================================================
     */

    private void searchAlbums() {

        String query =
                searchInput
                        .getText()
                        .toString()
                        .trim();

        if (query.isEmpty()) return;

        contentLayout.removeAllViews();

        LinearLayout loading =
                createSearchLoading(
                        "Buscando álbumes"
                );

        contentLayout.addView(loading);

        executor.execute(() -> {

            try {

                List<ApiClient.Album> albums =
                        api().searchAlbums(query);

                handler.post(
                        () -> showAlbums(albums)
                );

            } catch (Exception e) {

                handler.post(
                        () -> showError(e)
                );
            }
        });
    }

    private void showAlbums(
            List<ApiClient.Album> albums) {

        lastAlbumSearchResults =
                albums == null
                        ? new ArrayList<>()
                        : new ArrayList<>(albums);

        showMainHome();

        contentLayout.removeAllViews();

        for (
                ApiClient.Album album :
                albums
        ) {

            LinearLayout card =
                    new LinearLayout(this);

            card.setOrientation(
                    LinearLayout.HORIZONTAL
            );

            card.setGravity(
                    Gravity.CENTER_VERTICAL
            );

            card.setPadding(
                    dp(10),
                    dp(10),
                    dp(10),
                    dp(10)
            );

            card.setBackground(
                    roundedBackground(
                            Color.rgb(30, 30, 36),
                            Color.rgb(65, 65, 75),
                            18
                    )
            );

            LinearLayout.LayoutParams cardParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );

            cardParams.setMargins(
                    dp(8),
                    dp(6),
                    dp(8),
                    dp(6)
            );

            ImageView cover =
                    new ImageView(this);

            cover.setScaleType(
                    ImageView.ScaleType.CENTER_CROP
            );

            LinearLayout.LayoutParams coverParams =
                    new LinearLayout.LayoutParams(
                            dp(92),
                            dp(92)
                    );

            coverParams.setMargins(
                    0,
                    0,
                    dp(14),
                    0
            );

            card.addView(
                    cover,
                    coverParams
            );

            LinearLayout info =
                    new LinearLayout(this);

            info.setOrientation(
                    LinearLayout.VERTICAL
            );

            info.setGravity(
                    Gravity.CENTER_VERTICAL
            );

            TextView title =
                    new TextView(this);

            title.setText(
                    album.title
            );

            title.setTextSize(18);

            title.setTextColor(
                    Color.WHITE
            );

            title.setMaxLines(2);

            info.addView(title);

            TextView artist =
                    new TextView(this);

            artist.setText(
                    album.artist
            );

            artist.setTextSize(14);

            artist.setTextColor(
                    Color.rgb(190, 190, 200)
            );

            info.addView(artist);

            TextView date =
                    new TextView(this);

            date.setText(
                    album.date == null
                            ? ""
                            : album.date
            );

            date.setTextSize(12);

            date.setTextColor(
                    Color.rgb(140, 140, 150)
            );

            info.addView(date);

            TextView open =
                    new TextView(this);

            open.setText(
                    "Tocar álbum ›"
            );

            open.setTextSize(13);

            open.setTextColor(
                    Color.rgb(210, 210, 220)
            );

            LinearLayout.LayoutParams openParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );

            openParams.setMargins(
                    0,
                    dp(8),
                    0,
                    0
            );

            info.addView(
                    open,
                    openParams
            );

            card.addView(
                    info,
                    new LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                    )
            );

            card.setOnClickListener(
                    v -> openAlbum(album.id)
            );

            contentLayout.addView(
                    card,
                    cardParams
            );

            String albumCoverUrl =
                    "https://coverartarchive.org/release-group/"
                            + album.id
                            + "/front";

            loadThumbnail(
                    albumCoverUrl,
                    cover
            );
        }

        if (albums.isEmpty()) {

            TextView empty =
                    new TextView(this);

            empty.setText(
                    "No se encontraron álbumes"
            );

            empty.setTextSize(16);

            empty.setTextColor(
                    Color.LTGRAY
            );

            empty.setGravity(
                    Gravity.CENTER
            );

            empty.setPadding(
                    dp(20),
                    dp(30),
                    dp(20),
                    dp(30)
            );

            contentLayout.addView(empty);
        }
    }

    private void openAlbum(
            String albumId) {

        contentLayout.removeAllViews();

        TextView loading =
                new TextView(this);

        loading.setText(
                "Cargando álbum..."
        );

        loading.setTextSize(18);

        loading.setTextColor(
                Color.WHITE
        );

        loading.setGravity(
                Gravity.CENTER
        );

        contentLayout.addView(loading);

        executor.execute(() -> {

            try {

                ApiClient.Album album =
                        api().getAlbum(albumId);

                handler.post(
                        () -> showAlbum(album)
                );

            } catch (Exception e) {

                handler.post(
                        () -> showError(e)
                );
            }
        });
    }

    private void showAlbum(
            ApiClient.Album album) {

        contentLayout.removeAllViews();

        /*
         * ============================================================
         * CABECERA DEL ÁLBUM
         * ============================================================
         */

        LinearLayout header =
                new LinearLayout(this);

        header.setOrientation(
                LinearLayout.VERTICAL
        );

        header.setGravity(
                Gravity.CENTER_HORIZONTAL
        );

        header.setPadding(
                dp(12),
                dp(12),
                dp(12),
                dp(16)
        );

        Button backToAlbumsButton =
                new Button(this);

        backToAlbumsButton.setText(
                "← VOLVER A ÁLBUMES"
        );

        backToAlbumsButton.setTextSize(13);

        backToAlbumsButton.setTextColor(
                Color.WHITE
        );

        backToAlbumsButton.setAllCaps(false);

        backToAlbumsButton.setBackground(
                roundedBackground(
                        Color.rgb(42, 42, 50),
                        Color.rgb(90, 90, 105),
                        dp(14)
                )
        );

        LinearLayout.LayoutParams backParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(44)
                );

        backParams.setMargins(
                0,
                0,
                0,
                dp(14)
        );

        header.addView(
                backToAlbumsButton,
                backParams
        );

        backToAlbumsButton.setOnClickListener(
                v -> {

                    if (
                            lastAlbumSearchResults != null &&
                            !lastAlbumSearchResults.isEmpty()
                    ) {
                        showAlbums(
                                lastAlbumSearchResults
                        );
                    } else {
                        showMainHome();
                    }
                }
        );

        ImageView albumCover =
                new ImageView(this);

        albumCover.setScaleType(
                ImageView.ScaleType.CENTER_CROP
        );

        LinearLayout.LayoutParams albumCoverParams =
                new LinearLayout.LayoutParams(
                        dp(220),
                        dp(220)
                );

        albumCoverParams.setMargins(
                0,
                0,
                0,
                dp(14)
        );

        header.addView(
                albumCover,
                albumCoverParams
        );

        TextView heading =
                new TextView(this);

        heading.setText(
                "💿 "
                        + album.title
                        + "\n"
                        + album.artist
        );

        heading.setTextSize(22);

        heading.setTextColor(
                Color.WHITE
        );

        heading.setGravity(
                Gravity.CENTER
        );

        heading.setMaxLines(3);

        header.addView(heading);

        contentLayout.addView(header);

        /*
         * ============================================================
         * PORTADA DEL ÁLBUM
         * ============================================================
         *
         * Las pistas del álbum traen thumbnail.
         * Usamos la primera como portada principal.
         */

        String albumCoverUrl =
                "https://coverartarchive.org/release-group/"
                        + album.id
                        + "/front";

        if (
                album.tracks != null &&
                !album.tracks.isEmpty() &&
                album.tracks.get(0).thumbnail != null &&
                !album.tracks.get(0).thumbnail.trim().isEmpty()
        ) {

            albumCoverUrl =
                    album.tracks.get(0).thumbnail;
        }

        loadThumbnail(
                albumCoverUrl,
                albumCover
        );

        /*
         * ============================================================
         * BOTÓN REPRODUCIR ÁLBUM
         * ============================================================
         */

        Button playAlbum =
                visualButton(
                        "▶ REPRODUCIR ÁLBUM COMPLETO"
                );

        playAlbum.setOnClickListener(
                v -> playAlbum(album)
        );

        contentLayout.addView(
                playAlbum,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(48)
                )
        );

        /*
         * ============================================================
         * BOTÓN DESCARGAR ÁLBUM
         * ============================================================
         */

        Button downloadAlbum =
                visualButton(
                        "⬇ DESCARGAR ÁLBUM COMPLETO"
                );

        LinearLayout.LayoutParams downloadAlbumParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(48)
                );

        downloadAlbumParams.setMargins(
                0,
                dp(8),
                0,
                dp(12)
        );

        contentLayout.addView(
                downloadAlbum,
                downloadAlbumParams
        );

        Button downloadAlbumOffline =
                visualButton(
                        "📱 DESCARGAR ÁLBUM COMPLETO OFFLINE"
                );

        LinearLayout.LayoutParams downloadAlbumOfflineParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(48)
                );

        downloadAlbumOfflineParams.setMargins(
                0,
                0,
                0,
                dp(12)
        );

        contentLayout.addView(
                downloadAlbumOffline,
                downloadAlbumOfflineParams
        );

        List<DownloadSlot> slots =
                new ArrayList<>();

        List<DownloadSlot> offlineAlbumSlots =
                new ArrayList<>();

        albumPreparationStatusViews.clear();

        /*
         * ============================================================
         * CANCIONES DEL ÁLBUM
         * ============================================================
         */

        int albumTrackIndex = 0;

        for (
                ApiClient.Song track :
                album.tracks
        ) {

            final int currentAlbumTrackIndex =
                    albumTrackIndex++;

            LinearLayout card =
                    new LinearLayout(this);

            card.setOrientation(
                    LinearLayout.VERTICAL
            );

            card.setPadding(
                    dp(10),
                    dp(10),
                    dp(10),
                    dp(10)
            );

            card.setBackground(
                    roundedBackground(
                            Color.rgb(30, 30, 36),
                            Color.rgb(65, 65, 75),
                            18
                    )
            );

            LinearLayout.LayoutParams cardParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );

            cardParams.setMargins(
                    dp(8),
                    dp(5),
                    dp(8),
                    dp(5)
            );

            /*
             * FILA SUPERIOR: PORTADA + INFORMACIÓN
             */

            LinearLayout top =
                    new LinearLayout(this);

            top.setOrientation(
                    LinearLayout.HORIZONTAL
            );

            top.setGravity(
                    Gravity.CENTER_VERTICAL
            );

            ImageView cover =
                    new ImageView(this);

            cover.setScaleType(
                    ImageView.ScaleType.CENTER_CROP
            );

            LinearLayout.LayoutParams coverParams =
                    new LinearLayout.LayoutParams(
                            dp(76),
                            dp(76)
                    );

            coverParams.setMargins(
                    0,
                    0,
                    dp(12),
                    0
            );

            top.addView(
                    cover,
                    coverParams
            );

            LinearLayout info =
                    new LinearLayout(this);

            info.setOrientation(
                    LinearLayout.VERTICAL
            );

            info.setGravity(
                    Gravity.CENTER_VERTICAL
            );

            TextView title =
                    new TextView(this);

            title.setText(
                    track.number
                            + ". "
                            + track.title
            );

            title.setTextSize(16);

            title.setTextColor(
                    Color.WHITE
            );

            title.setMaxLines(2);

            info.addView(title);

            TextView artist =
                    new TextView(this);

            String artistText =
                    track.artist == null ||
                    track.artist.trim().isEmpty()
                            ? album.artist
                            : track.artist;

            artist.setText(
                    artistText
                            + "  ·  "
                            + formatTime(
                                    track.duration * 1000L
                            )
            );

            artist.setTextSize(13);

            artist.setTextColor(
                    Color.rgb(170, 170, 180)
            );

            artist.setMaxLines(2);

            info.addView(artist);

            TextView obtainedStatus =
                    createObtainedStatusView();

            info.addView(
                    obtainedStatus
            );

            albumPreparationStatusViews.add(
                    obtainedStatus
            );

            top.addView(
                    info,
                    new LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                    )
            );

            card.addView(top);

            /*
             * BOTONES
             */

            LinearLayout buttons =
                    new LinearLayout(this);

            buttons.setOrientation(
                    LinearLayout.HORIZONTAL
            );

            buttons.setGravity(
                    Gravity.CENTER_VERTICAL
            );

            LinearLayout.LayoutParams buttonsParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );

            buttonsParams.setMargins(
                    0,
                    dp(8),
                    0,
                    0
            );

            Button play =
                    visualButton("▶ Tocar");

            LinearLayout.LayoutParams playParams =
                    new LinearLayout.LayoutParams(
                            0,
                            dp(44),
                            1f
                    );

            playParams.setMargins(
                    0,
                    0,
                    dp(5),
                    0
            );

            buttons.addView(
                    play,
                    playParams
            );

            Button download =
                    visualButton(
                            "⬇ Descargar"
                    );

            LinearLayout.LayoutParams downloadParams =
                    new LinearLayout.LayoutParams(
                            0,
                            dp(44),
                            1f
                    );

            downloadParams.setMargins(
                    dp(5),
                    0,
                    0,
                    0
            );

            buttons.addView(
                    download,
                    downloadParams
            );

            Button offline =
                    visualButton(
                            "📱 Offline"
                    );

            LinearLayout.LayoutParams offlineParams =
                    new LinearLayout.LayoutParams(
                            0,
                            dp(44),
                            1f
                    );

            offlineParams.setMargins(
                    dp(6),
                    0,
                    0,
                    0
            );

            buttons.addView(
                    offline,
                    offlineParams
            );

            card.addView(
                    buttons,
                    buttonsParams
            );

            /*
             * PROGRESO
             */

            ProgressBar progress =
                    new ProgressBar(
                            this,
                            null,
                            android.R.attr.progressBarStyleHorizontal
                    );

            progress.setMax(100);
            progress.setProgress(0);

            LinearLayout.LayoutParams progressParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(6)
                    );

            progressParams.setMargins(
                    0,
                    dp(8),
                    0,
                    0
            );

            card.addView(
                    progress,
                    progressParams
            );

            /*
             * ESTADO
             */

            TextView status =
                    new TextView(this);

            status.setText(
                    "Listo para descargar"
            );

            status.setTextSize(12);

            status.setTextColor(
                    Color.rgb(145, 145, 155)
            );

            LinearLayout.LayoutParams statusParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );

            statusParams.setMargins(
                    0,
                    dp(5),
                    0,
                    0
            );

            card.addView(
                    status,
                    statusParams
            );

            /*
             * SLOT DE DESCARGA
             */

            DownloadSlot slot =
                    new DownloadSlot(
                            track,
                            download,
                            progress,
                            status
                    );

            slots.add(slot);

            /*
             * OFFLINE
             */

            ProgressBar offlineProgress =
                    new ProgressBar(
                            this,
                            null,
                            android.R.attr.progressBarStyleHorizontal
                    );

            offlineProgress.setMax(100);
            offlineProgress.setProgress(0);

            LinearLayout.LayoutParams offlineProgressParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(5)
                    );

            offlineProgressParams.setMargins(
                    0,
                    dp(5),
                    0,
                    0
            );

            card.addView(
                    offlineProgress,
                    offlineProgressParams
            );

            TextView offlineStatus =
                    new TextView(this);

            offlineStatus.setText(
                    "📱 Listo para guardar offline"
            );

            offlineStatus.setTextSize(11);

            offlineStatus.setTextColor(
                    Color.rgb(135, 135, 145)
            );

            LinearLayout.LayoutParams offlineStatusParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );

            offlineStatusParams.setMargins(
                    0,
                    dp(3),
                    0,
                    0
            );

            card.addView(
                    offlineStatus,
                    offlineStatusParams
            );

            DownloadSlot offlineSlot =
                    new DownloadSlot(
                            track,
                            offline,
                            offlineProgress,
                            offlineStatus
                    );

            offlineAlbumSlots.add(
                    offlineSlot
            );

            /*
             * REPRODUCCIÓN
             */

            play.setOnClickListener(
                    v -> playAlbumFromIndex(
                            album,
                            currentAlbumTrackIndex
                    )
            );

            /*
             * DESCARGA
             */

            download.setOnClickListener(
                    v -> downloadAlbumTrack(
                            slot
                    )
            );

            /*
             * OFFLINE
             */

            offline.setOnClickListener(
                    v -> startMobileDownloadAlbumTrack(
                            track,
                            offlineSlot
                    )
            );

            contentLayout.addView(
                    card,
                    cardParams
            );

            /*
             * PORTADA DE LA CANCIÓN
             */

            String trackThumbnail =
                    track.thumbnail;

            if (
                    trackThumbnail == null ||
                    trackThumbnail.trim().isEmpty()
            ) {
                trackThumbnail =
                        "https://coverartarchive.org/release-group/"
                                + album.id
                                + "/front";
            }

            loadThumbnail(
                    trackThumbnail,
                    cover
            );
        }

        /*
         * DESCARGAR TODO EL ÁLBUM
         */

        downloadAlbum.setOnClickListener(
                v -> downloadAlbum(
                        album,
                        slots
                )
        );

        downloadAlbumOffline.setOnClickListener(
                v -> downloadAlbumOffline(
                        offlineAlbumSlots
                )
        );
    }

    private void resolveAndPlayAlbumTrack(
            ApiClient.Song track,
            String albumId) {

        Toast.makeText(
                this,
                "Buscando audio: "
                        + track.title,
                Toast.LENGTH_SHORT
        ).show();

        executor.execute(() -> {

            try {

                ApiClient.Song resolved =
                        api().resolveAlbumTrack(
                                track.artist,
                                track.title
                        );

                String url =
                        api().getPreviewUrl(
                                resolved.id
                        );

                String thumbnail =
                        track.thumbnail;

                if (
                        thumbnail == null ||
                        thumbnail.trim().isEmpty()
                ) {
                    thumbnail =
                            resolved.thumbnail;
                }

                if (
                        (thumbnail == null ||
                                thumbnail.trim().isEmpty()) &&
                        albumId != null &&
                        !albumId.trim().isEmpty()
                ) {
                    thumbnail =
                            "https://coverartarchive.org/release-group/"
                                    + albumId
                                    + "/front";
                }

                final String finalThumbnail =
                        thumbnail;

                handler.post(
                        () -> playResolved(
                                url,
                                resolved.title,
                                finalThumbnail
                        )
                );

            } catch (Exception e) {

                handler.post(
                        () -> showError(e)
                );
            }
        });
    }

    private void playResolved(
            String url,
            String title) {

        playResolved(
                url,
                title,
                ""
        );
    }

    private void playResolved(
            String url,
            String title,
            String thumbnail) {

        if (mediaController == null) {

            Toast.makeText(
                    this,
                    "Reproductor no conectado",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        MediaMetadata.Builder metadata =
                new MediaMetadata.Builder()
                        .setTitle(title);

        if (thumbnail != null &&
                !thumbnail.trim().isEmpty()) {
            metadata.setArtworkUri(
                    Uri.parse(thumbnail)
            );
        }

        MediaItem item =
                new MediaItem.Builder()
                        .setUri(url)
                        .setMediaMetadata(
                                metadata.build()
                        )
                        .build();

        playerManuallyClosed = false;

        mediaController.setMediaItem(item);
        mediaController.prepare();
        mediaController.play();
    }

    private void playAlbumFromIndex(
            ApiClient.Album album,
            int startIndex) {

        if (album == null ||
                album.tracks == null ||
                album.tracks.isEmpty() ||
                startIndex < 0 ||
                startIndex >= album.tracks.size()) {
            return;
        }

        final long playbackGeneration =
                beginIndividualPlayback();

        Toast.makeText(
                this,
                "Preparando desde la pista "
                        + (startIndex + 1)
                        + "...",
                Toast.LENGTH_SHORT
        ).show();

        executor.execute(() -> {

            try {
                ApiClient.Song firstTrack =
                        album.tracks.get(startIndex);

                ApiClient.Song firstResolved =
                        api().resolveAlbumTrack(
                                firstTrack.artist,
                                firstTrack.title
                        );

                if (firstResolved == null ||
                        firstResolved.id == null ||
                        firstResolved.id.trim().isEmpty()) {
                    handler.post(() ->
                            Toast.makeText(
                                    this,
                                    "No se pudo preparar la pista",
                                    Toast.LENGTH_SHORT
                            ).show()
                    );
                    return;
                }

                String firstUrl =
                        api().getPreviewUrl(
                                firstResolved.id
                        );

                if (firstUrl == null ||
                        firstUrl.trim().isEmpty()) {
                    handler.post(() ->
                            Toast.makeText(
                                    this,
                                    "No se pudo obtener el audio",
                                    Toast.LENGTH_SHORT
                            ).show()
                    );
                    return;
                }

                String firstThumbnail =
                        firstTrack.thumbnail;

                if (firstThumbnail == null ||
                        firstThumbnail.trim().isEmpty()) {
                    firstThumbnail =
                            firstResolved.thumbnail;
                }

                if ((firstThumbnail == null ||
                        firstThumbnail.trim().isEmpty()) &&
                        album.id != null &&
                        !album.id.trim().isEmpty()) {

                    firstThumbnail =
                            "https://coverartarchive.org/release-group/"
                                    + album.id
                                    + "/front";
                }

                MediaMetadata.Builder firstMetadata =
                        new MediaMetadata.Builder()
                                .setTitle(firstTrack.title)
                                .setArtist(firstTrack.artist)
                                .setAlbumTitle(album.title);

                if (firstThumbnail != null &&
                        !firstThumbnail.trim().isEmpty()) {

                    firstMetadata.setArtworkUri(
                            Uri.parse(firstThumbnail)
                    );
                }

                MediaItem firstItem =
                        new MediaItem.Builder()
                                .setUri(firstUrl)
                                .setMediaMetadata(
                                        firstMetadata.build()
                                )
                                .build();

                List<MediaItem> firstList =
                        new ArrayList<>();

                firstList.add(firstItem);

                handler.post(() -> {

                    if (!isCurrentIndividualPlayback(
                            playbackGeneration)) {
                        return;
                    }

                    sendQueueToPlayer(firstList);
                });

                if (startIndex + 1 >= album.tracks.size()) {
                    return;
                }

                int remaining =
                        album.tracks.size()
                                - startIndex
                                - 1;

                int threads =
                        Math.min(
                                6,
                                Math.max(2, remaining)
                        );

                ExecutorService resolver =
                        Executors.newFixedThreadPool(
                                threads
                        );

                java.util.concurrent.ExecutorCompletionService<
                        java.util.AbstractMap.SimpleEntry<
                                Integer,
                                MediaItem
                        >
                > completionService =
                        new java.util.concurrent.ExecutorCompletionService<>(
                                resolver
                        );

                int submitted = 0;

                for (
                        int index = startIndex + 1;
                        index < album.tracks.size();
                        index++
                ) {

                    final int trackIndex = index;

                    completionService.submit(() -> {

                        ApiClient.Song track =
                                album.tracks.get(trackIndex);

                        ApiClient.Song resolved =
                                api().resolveAlbumTrack(
                                        track.artist,
                                        track.title
                                );

                        if (resolved == null ||
                                resolved.id == null ||
                                resolved.id.trim().isEmpty()) {
                            return null;
                        }

                        String url =
                                api().getPreviewUrl(
                                        resolved.id
                                );

                        if (url == null ||
                                url.trim().isEmpty()) {
                            return null;
                        }

                        String thumbnail =
                                track.thumbnail;

                        if (thumbnail == null ||
                                thumbnail.trim().isEmpty()) {
                            thumbnail =
                                    resolved.thumbnail;
                        }

                        if ((thumbnail == null ||
                                thumbnail.trim().isEmpty()) &&
                                album.id != null &&
                                !album.id.trim().isEmpty()) {

                            thumbnail =
                                    "https://coverartarchive.org/release-group/"
                                            + album.id
                                            + "/front";
                        }

                        MediaMetadata.Builder metadata =
                                new MediaMetadata.Builder()
                                        .setTitle(track.title)
                                        .setArtist(track.artist)
                                        .setAlbumTitle(album.title);

                        if (thumbnail != null &&
                                !thumbnail.trim().isEmpty()) {

                            metadata.setArtworkUri(
                                    Uri.parse(thumbnail)
                            );
                        }

                        MediaItem item =
                                new MediaItem.Builder()
                                        .setUri(url)
                                        .setMediaMetadata(
                                                metadata.build()
                                        )
                                        .build();

                        return new java.util.AbstractMap.SimpleEntry<>(
                                trackIndex,
                                item
                        );
                    });

                    submitted++;
                }

                Map<Integer, MediaItem> ready =
                        new java.util.HashMap<>();

                int nextIndex = startIndex + 1;

                for (int i = 0; i < submitted; i++) {

                    java.util.concurrent.Future<
                            java.util.AbstractMap.SimpleEntry<
                                    Integer,
                                    MediaItem
                            >
                    > future =
                            completionService.take();

                    java.util.AbstractMap.SimpleEntry<
                            Integer,
                            MediaItem
                    > result =
                            future.get();

                    if (result == null) {
                        continue;
                    }

                    ready.put(
                            result.getKey(),
                            result.getValue()
                    );

                    while (ready.containsKey(nextIndex)) {

                        MediaItem item =
                                ready.remove(nextIndex);

                        List<MediaItem> next =
                                new ArrayList<>();

                        next.add(item);

                        final List<MediaItem> itemsToAdd =
                                next;

                        handler.post(() -> {

                            if (!isCurrentIndividualPlayback(
                                    playbackGeneration)) {
                                return;
                            }

                            addItemsToPlayerQueue(
                                    itemsToAdd
                            );
                        });

                        nextIndex++;
                    }
                }

                resolver.shutdown();

            } catch (Exception error) {

                Log.e(
                        "MUSIC_DOWNLOAD",
                        "Error preparando álbum",
                        error
                );
            }
        });
    }

    private void playAlbum(
            ApiClient.Album album) {

        
        final int albumTotal =
                album != null &&
                album.tracks != null
                        ? album.tracks.size()
                        : 0;

        final long queueGeneration =
                beginQueuePreparation(
                        albumTotal
                );

Toast.makeText(
                this,
                "Preparando álbum completo...",
                Toast.LENGTH_LONG
        ).show();

        executor.execute(() -> {

            List<MediaItem> items =
                    new ArrayList<>();

            int failed = 0;
            int preparationTrackIndex = 0;

            for (
                    ApiClient.Song track :
                    album.tracks
            ) {

                final int currentPreparationTrackIndex =
                        preparationTrackIndex++;

                try {

                    ApiClient.Song resolved =
                            api().resolveAlbumTrack(
                                    track.artist,
                                    track.title
                            );

                    if (
                            resolved == null ||
                            resolved.id == null ||
                            resolved.id.trim().isEmpty()
                    ) {

                        failed++;
                        continue;
                    }

                    String url =
                            api().getPreviewUrl(
                                    resolved.id
                            );

                    if (
                            url == null ||
                            url.trim().isEmpty()
                    ) {

                        failed++;
                        continue;
                    }

                    String thumbnail =
                            track.thumbnail;

                    if (
                            thumbnail == null ||
                            thumbnail.trim().isEmpty()
                    ) {
                        thumbnail =
                                resolved.thumbnail;
                    }

                    if (
                            (thumbnail == null ||
                                    thumbnail.trim().isEmpty()) &&
                            album.id != null &&
                            !album.id.trim().isEmpty()
                    ) {
                        thumbnail =
                                "https://coverartarchive.org/release-group/"
                                        + album.id
                                        + "/front";
                    }

                    MediaMetadata.Builder metadata =
                            new MediaMetadata.Builder()
                                    .setTitle(
                                            track.title
                                    )
                                    .setArtist(
                                            track.artist
                                    )
                                    .setAlbumTitle(
                                            album.title
                                    );

                    if (
                            thumbnail != null &&
                            !thumbnail.trim().isEmpty()
                    ) {
                        metadata.setArtworkUri(
                                Uri.parse(thumbnail)
                        );
                    }

                    MediaItem item =
                            new MediaItem.Builder()
                                    .setUri(url)
                                    .setMediaMetadata(
                                            metadata.build()
                                    )
                                    .build();

                    items.add(item);

                    updateQueuePreparationProgress(
                            queueGeneration,
                            items.size(),
                            albumTotal
                    );

                    markAlbumTrackObtained(
                            queueGeneration,
                            currentPreparationTrackIndex
                    );

                    /*
                     * La primera canción comienza inmediatamente.
                     * Las siguientes se añaden progresivamente.
                     */
                    if (items.size() == 1) {

                        final MediaItem firstItem = item;

                        handler.post(() -> {

                            List<MediaItem> first =
                                    new ArrayList<>();

                            first.add(firstItem);

                            sendQueueToPlayer(first);
                        });

                    } else {

                        List<MediaItem> next =
                                new ArrayList<>();

                        next.add(item);

                        handler.post(() ->
                                addItemsToPlayerQueue(next)
                        );
                    }

                } catch (Exception trackError) {

                    failed++;

                    // Se salta esta pista y continúa con el álbum.
                }
            }

            final int totalPrepared = items.size();
            final int totalFailed = failed;

            handler.post(() -> {

                if (
                        mediaController == null
                ) {

                    Toast.makeText(
                            this,
                            "Reproductor no conectado",
                            Toast.LENGTH_SHORT
                    ).show();

                    return;
                }

                if (
                        items.isEmpty()
                ) {

                    Toast.makeText(
                            this,
                            "No se pudo preparar ninguna canción del álbum",
                            Toast.LENGTH_LONG
                    ).show();

                    return;
                }

                String message;

                if (
                        totalFailed > 0
                ) {

                    message =
                            "Álbum: "
                                    + totalPrepared
                                    + " canciones"
                                    + " ("
                                    + totalFailed
                                    + " no disponibles)";

                } else {

                    message =
                            "Álbum preparado: "
                                    + totalPrepared
                                    + " canciones";
                }

                Toast.makeText(
                        this,
                        message,
                        Toast.LENGTH_LONG
                ).show();
            });
        });
    }

    /*
     * ============================================================
     * SPOTIFY
     * ============================================================
     */

    private void showSpotifyHome() {

        if (spotifyTabLayout == null ||
                spotifyTabScroll == null) {
            return;
        }

        switchContentTab(
                spotifyTabLayout,
                spotifyTabScroll,
                false
        );

        /*
         * Si Spotify ya tiene contenido, lo conservamos.
         */
        if (spotifyTabLayout.getChildCount() > 0) {
            return;
        }

        TextView heading =
                new TextView(this);

        heading.setText(
                "🎧 Spotify"
        );

        heading.setTextSize(24);

        heading.setGravity(
                Gravity.CENTER
        );

        contentLayout.addView(heading);

        EditText spotifyUrl =
                new EditText(this);

        spotifyUrl.setHint(
                "URL de playlist de Spotify"
        );

        spotifyUrl.setSingleLine(true);

        contentLayout.addView(spotifyUrl);

        Button importButton =
                roundedButton();

        importButton.setText(
                "📥 IMPORTAR PLAYLIST"
        );

        importButton.setOnClickListener(
                v -> {

                    String url =
                            spotifyUrl
                                    .getText()
                                    .toString()
                                    .trim();

                    if (url.isEmpty()) {

                        Toast.makeText(
                                this,
                                "Introduce una URL de Spotify",
                                Toast.LENGTH_SHORT
                        ).show();

                        return;
                    }

                    importSpotify(url);
                }
        );

        contentLayout.addView(importButton);

        Button exportifyButton =
                roundedButton();

        exportifyButton.setText(
                "🌐 ABRIR EXPORTIFY"
        );

        exportifyButton.setOnClickListener(
                v -> {
                    Intent intent =
                            new Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://exportify.net/")
                            );

                    startActivity(intent);
                }
        );

        contentLayout.addView(exportifyButton);

        Button fileButton =
                roundedButton();

        fileButton.setText(
                "📂 AÑADIR LISTA DESDE ARCHIVO"
        );

        fileButton.setOnClickListener(
                v -> {

                    Intent intent =
                            new Intent(
                                    Intent.ACTION_OPEN_DOCUMENT
                            );

                    intent.addCategory(
                            Intent.CATEGORY_OPENABLE
                    );

                    intent.setType(
                            "*/*"
                    );

                    startActivityForResult(
                            intent,
                            PICK_PLAYLIST_FILE
                    );
                }
        );

        contentLayout.addView(fileButton);

        Button savedButton =
                roundedButton();

        savedButton.setText(
                "📚 LISTAS GUARDADAS"
        );

        savedButton.setOnClickListener(
                v -> loadSpotifyLists()
        );

        contentLayout.addView(savedButton);

        loadSpotifyLists();
    }

    private void importSpotify(
            String url) {

        contentLayout.removeAllViews();

        TextView loading =
                new TextView(this);

        loading.setText(
                "Importando playlist de Spotify..."
        );

        loading.setTextSize(18);

        contentLayout.addView(loading);

        executor.execute(() -> {

            try {

                ApiClient.SpotifyPlaylist playlist =
                        api().importSpotifyPlaylist(
                                url
                        );

                handler.post(
                        () -> showSpotifyPlaylist(
                                playlist
                        )
                );

            } catch (Exception e) {

                handler.post(
                        () -> showError(e)
                );
            }
        });
    }

    private void loadSpotifyLists() {

        executor.execute(() -> {

            try {

                List<ApiClient.SpotifyPlaylist> lists =
                        api().getSpotifyLists();

                handler.post(
                        () -> showSpotifyLists(lists)
                );

            } catch (Exception e) {

                handler.post(
                        () -> showError(e)
                );
            }
        });
    }

    private void showSpotifyLists(
            List<ApiClient.SpotifyPlaylist> lists) {

        contentLayout.removeAllViews();

        TextView heading =
                new TextView(this);

        heading.setText(
                "🎧 Spotify"
        );

        heading.setTextSize(24);

        heading.setGravity(
                Gravity.CENTER
        );

        contentLayout.addView(heading);


        EditText spotifyUrl =
                new EditText(this);

        spotifyUrl.setHint(
                "URL de playlist de Spotify"
        );

        spotifyUrl.setSingleLine(true);

        contentLayout.addView(
                spotifyUrl
        );


        Button importButton =
                roundedButton();

        importButton.setText(
                "📥 IMPORTAR PLAYLIST"
        );

        importButton.setOnClickListener(
                v -> {

                    String url =
                            spotifyUrl
                                    .getText()
                                    .toString()
                                    .trim();

                    if (url.isEmpty()) {

                        Toast.makeText(
                                this,
                                "Introduce una URL de Spotify",
                                Toast.LENGTH_SHORT
                        ).show();

                        return;
                    }

                    importSpotify(url);
                }
        );

        contentLayout.addView(
                importButton
        );


        Button exportifyButton =
                roundedButton();

        exportifyButton.setText(
                "🌐 ABRIR EXPORTIFY"
        );

        exportifyButton.setOnClickListener(
                v -> {
                    Intent intent =
                            new Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://exportify.net/")
                            );

                    startActivity(intent);
                }
        );

        contentLayout.addView(
                exportifyButton
        );


        Button fileButton =
                roundedButton();

        fileButton.setText(
                "📂 AÑADIR LISTA DESDE ARCHIVO"
        );

        fileButton.setOnClickListener(
                v -> {

                    Intent intent =
                            new Intent(
                                    Intent.ACTION_OPEN_DOCUMENT
                            );

                    intent.addCategory(
                            Intent.CATEGORY_OPENABLE
                    );

                    intent.setType(
                            "*/*"
                    );

                    startActivityForResult(
                            intent,
                            PICK_PLAYLIST_FILE
                    );
                }
        );

        contentLayout.addView(
                fileButton
        );


        Button savedButton =
                roundedButton();

        savedButton.setText(
                "📚 LISTAS GUARDADAS"
        );

        savedButton.setOnClickListener(
                v -> loadSpotifyLists()
        );

        contentLayout.addView(
                savedButton
        );


        TextView savedHeading =
                new TextView(this);

        savedHeading.setText(
                "\n📚 Listas guardadas"
        );

        savedHeading.setTextSize(20);

        contentLayout.addView(
                savedHeading
        );


        if (lists.isEmpty()) {

            TextView empty =
                    new TextView(this);

            empty.setText(
                    "No hay listas guardadas"
            );

            contentLayout.addView(
                    empty
            );

            return;
        }


        for (
                ApiClient.SpotifyPlaylist playlist :
                lists
        ) {

            LinearLayout row =
                    new LinearLayout(this);

            row.setOrientation(
                    LinearLayout.VERTICAL
            );


            Button open =
                    roundedButton();


            String count =
                    playlist.trackCount > 0
                            ? " · "
                            + playlist.trackCount
                            + " canciones"
                            : "";


            open.setText(
                    "🎧 "
                            + playlist.name
                            + count
            );

            /*
             * Persiana horizontal:
             * el nombre largo se desplaza dentro del botón
             * y nunca se sale de los límites de la pantalla.
             */
            open.setSingleLine(true);

            open.setEllipsize(
                    android.text.TextUtils.TruncateAt.MARQUEE
            );

            open.setMarqueeRepeatLimit(
                    -1
            );

            open.setSelected(true);

            open.setHorizontallyScrolling(true);

            open.setOnClickListener(
                    v -> loadSpotifyPlaylist(
                            playlist.id
                    )
            );


            row.addView(
                    open
            );


            Button delete =
                    roundedButton();

            delete.setText(
                    "🗑 Borrar"
            );


            delete.setOnClickListener(
                    v -> deleteSpotifyPlaylist(
                            playlist
                    )
            );


            row.addView(
                    delete
            );


            contentLayout.addView(
                    row
            );
        }
    }

    private void loadSpotifyPlaylist(
            String id) {

        contentLayout.removeAllViews();

        TextView loading =
                new TextView(this);

        loading.setText(
                "Cargando playlist..."
        );

        loading.setTextSize(18);

        contentLayout.addView(loading);

        executor.execute(() -> {

            try {

                ApiClient.SpotifyPlaylist playlist =
                        api().loadSpotifyList(id);

                handler.post(
                        () -> showSpotifyPlaylist(
                                playlist
                        )
                );

            } catch (Exception e) {

                handler.post(
                        () -> showError(e)
                );
            }
        });
    }

    private final List<CheckBox> spotifySelectionChecks =
            new ArrayList<>();

    private final List<CheckBox> offlineSelectionChecks =
            new ArrayList<>();

    private final List<ApiClient.SpotifyTrack> spotifySelectionTracks =
            new ArrayList<>();

    private final List<DownloadSlot> spotifyOfflineSlots =
            new ArrayList<>();

    /*
     * DESCARGAS OFFLINE DE LA SESIÓN
     *
     * Cada job conserva su DownloadSlot original.
     * La pestaña Descargas utiliza vistas espejo.
     */
    private static class MobileDownloadMonitor {

        final String job;
        final DownloadSlot slot;

        TextView titleView;
        TextView statusView;
        TextView progressView;
        ProgressBar progressBar;

        Button pauseButton;
        Button resumeButton;
        Button cancelButton;

        MobileDownloadMonitor(
                String job,
                DownloadSlot slot) {

            this.job = job;
            this.slot = slot;
        }
    }

    private final List<MobileDownloadMonitor> mobileDownloadMonitors =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private LinearLayout downloadsActiveList;

    private MobileDownloadMonitor findMobileDownloadMonitor(
            String job) {

        for (MobileDownloadMonitor monitor :
                mobileDownloadMonitors) {

            if (monitor.job.equals(job)) {
                return monitor;
            }
        }

        return null;
    }

    private void registerMobileDownload(
            String job,
            DownloadSlot slot) {

        if (job == null ||
                job.isEmpty() ||
                slot == null) {

            return;
        }

        if (findMobileDownloadMonitor(job) != null) {
            return;
        }

        MobileDownloadMonitor monitor =
                new MobileDownloadMonitor(
                        job,
                        slot
                );

        mobileDownloadMonitors.add(
                monitor
        );

        handler.post(() -> {

            ensureMobileDownloadMonitorCard(
                    monitor
            );

            updateMobileDownloadMonitor(
                    job,
                    slot.progress.getProgress(),
                    slot.status.getText() == null
                            ? "📱 Preparando descarga offline..."
                            : slot.status.getText().toString()
            );
        });
    }

    private void updateMobileDownloadMonitor(
            String job,
            int progress,
            String status) {

        MobileDownloadMonitor monitor =
                findMobileDownloadMonitor(job);

        if (monitor == null) {
            return;
        }

        ensureMobileDownloadMonitorCard(
                monitor
        );

        int safeProgress =
                Math.max(
                        0,
                        Math.min(
                                100,
                                progress
                        )
                );

        if (monitor.progressBar != null) {

            monitor.progressBar.setProgress(
                    safeProgress
            );
        }

        if (monitor.progressView != null) {

            monitor.progressView.setText(
                    safeProgress + "%"
            );
        }

        if (monitor.statusView != null) {

            monitor.statusView.setText(
                    status == null || status.isEmpty()
                            ? "📱 Descarga offline"
                            : status
            );
        }
    }

    private void finishMobileDownloadMonitor(
            String job,
            String status) {

        updateMobileDownloadMonitor(
                job,
                100,
                status
        );
    }

    private void ensureMobileDownloadMonitorCard(
            MobileDownloadMonitor monitor) {

        if (downloadsActiveList == null) {
            return;
        }

        if (monitor.statusView != null &&
                monitor.progressBar != null &&
                monitor.progressView != null) {

            return;
        }

        /*
         * Si ya existía el texto de "no hay descargas",
         * lo eliminamos al aparecer la primera descarga.
         */
        if (downloadsActiveList.getChildCount() == 1) {

            android.view.View first =
                    downloadsActiveList.getChildAt(0);

            if (first instanceof TextView) {

                TextView tv =
                        (TextView) first;

                if ("No hay descargas en esta sesión."
                        .contentEquals(
                                tv.getText())) {

                    downloadsActiveList.removeView(
                            first
                    );
                }
            }
        }

        addMobileDownloadMonitorCard(
                downloadsActiveList,
                monitor
        );
    }

    private void addMobileDownloadMonitorCard(
            LinearLayout parent,
            MobileDownloadMonitor monitor) {

        if (parent == null ||
                monitor == null) {

            return;
        }

        LinearLayout card =
                new LinearLayout(this);

        card.setOrientation(
                LinearLayout.VERTICAL
        );

        card.setPadding(
                dp(14),
                dp(12),
                dp(14),
                dp(12)
        );

        card.setBackground(
                roundedBackground(
                        Color.rgb(
                                28,
                                28,
                                36
                        ),
                        Color.rgb(
                                78,
                                78,
                                92
                        ),
                        16
                )
        );

        card.setElevation(
                dp(4)
        );

        LinearLayout.LayoutParams cardParams =
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                );

        cardParams.setMargins(
                dp(8),
                dp(6),
                dp(8),
                dp(6)
        );

        parent.addView(
                card,
                cardParams
        );

        TextView title =
                new TextView(this);

        title.setText(
                "🎵 " + monitor.slot.title
        );

        title.setTextSize(17);
        title.setTextColor(
                Color.WHITE
        );

        title.setTypeface(
                null,
                android.graphics.Typeface.BOLD
        );

        card.addView(
                title
        );

        TextView type =
                new TextView(this);

        type.setText(
                "📱 OFFLINE"
        );

        type.setTextSize(13);
        type.setTextColor(
                Color.rgb(
                        120,
                        210,
                        255
                )
        );

        type.setPadding(
                0,
                dp(3),
                0,
                dp(2)
        );

        card.addView(
                type
        );

        TextView status =
                new TextView(this);

        String initialStatus =
                monitor.slot.status.getText() == null
                        ? "📱 Preparando descarga offline..."
                        : monitor.slot.status.getText()
                                .toString();

        status.setText(
                initialStatus
        );

        status.setTextSize(14);
        status.setTextColor(
                Color.rgb(
                        190,
                        190,
                        200
                )
        );

        status.setPadding(
                0,
                dp(6),
                0,
                dp(4)
        );

        card.addView(
                status
        );

        ProgressBar progress =
                new ProgressBar(
                        this,
                        null,
                        android.R.attr.progressBarStyleHorizontal
                );

        progress.setMax(
                100
        );

        progress.setProgress(
                monitor.slot.progress.getProgress()
        );

        LinearLayout.LayoutParams progressParams =
                new LinearLayout.LayoutParams(
                        -1,
                        dp(12)
                );

        progressParams.setMargins(
                0,
                dp(4),
                0,
                dp(2)
        );

        card.addView(
                progress,
                progressParams
        );

        TextView percent =
                new TextView(this);

        percent.setText(
                monitor.slot.progress.getProgress()
                        + "%"
        );

        percent.setTextSize(13);
        percent.setTextColor(
                Color.rgb(
                        170,
                        170,
                        180
                )
        );

        percent.setGravity(
                android.view.Gravity.END
        );

        card.addView(
                percent
        );

        /*
         * CONTROLES
         */

        LinearLayout controls =
                new LinearLayout(this);

        controls.setOrientation(
                LinearLayout.HORIZONTAL
        );

        controls.setGravity(
                android.view.Gravity.CENTER_VERTICAL
        );

        LinearLayout.LayoutParams controlsParams =
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                );

        controlsParams.setMargins(
                0,
                dp(8),
                0,
                0
        );

        card.addView(
                controls,
                controlsParams
        );

        Button pauseButton =
                new Button(this);

        pauseButton.setText(
                "⏸ Pausar"
        );

        pauseButton.setTextSize(12);

        Button resumeButton =
                new Button(this);

        resumeButton.setText(
                "▶ Reanudar"
        );

        resumeButton.setTextSize(12);

        Button cancelButton =
                new Button(this);

        cancelButton.setText(
                "✕ Cancelar"
        );

        cancelButton.setTextSize(12);

        LinearLayout.LayoutParams buttonParams =
                new LinearLayout.LayoutParams(
                        0,
                        dp(46),
                        1f
                );

        buttonParams.setMargins(
                dp(2),
                0,
                dp(2),
                0
        );

        controls.addView(
                pauseButton,
                buttonParams
        );

        controls.addView(
                resumeButton,
                buttonParams
        );

        controls.addView(
                cancelButton,
                buttonParams
        );

        /*
         * PAUSAR
         */

        pauseButton.setOnClickListener(
                v -> {

                    pauseButton.setEnabled(false);

                    status.setText(
                            "⏸ Pausando descarga..."
                    );

                    executor.execute(() -> {

                        try {

                            JSONObject result =
                                    api().pauseDownload(
                                            monitor.job
                                    );

                            boolean ok =
                                    result.optBoolean(
                                            "ok",
                                            false
                                    );

                            String message =
                                    result.optString(
                                            "message",
                                            ""
                                    );

                            String serverStatus =
                                    result.optString(
                                            "status",
                                            "paused"
                                    );

                            handler.post(() -> {

                                if (ok) {

                                    status.setText(
                                            "⏸ "
                                                    + (
                                                    message.isEmpty()
                                                            ? "Descarga pausada."
                                                            : message
                                            )
                                    );

                                    updateMobileDownloadMonitorControls(
                                            monitor,
                                            serverStatus
                                    );

                                } else {

                                    status.setText(
                                            "❌ "
                                                    + (
                                                    message.isEmpty()
                                                            ? "No se pudo pausar."
                                                            : message
                                            )
                                    );

                                    updateMobileDownloadMonitorControls(
                                            monitor,
                                            "running"
                                    );
                                }
                            });

                        } catch (Exception e) {

                            handler.post(() -> {

                                status.setText(
                                        "❌ Error al pausar: "
                                                + e.getMessage()
                                );

                                updateMobileDownloadMonitorControls(
                                        monitor,
                                        "running"
                                );
                            });
                        }
                    });
                }
        );

        /*
         * REANUDAR
         */

        resumeButton.setOnClickListener(
                v -> {

                    resumeButton.setEnabled(false);

                    status.setText(
                            "▶ Reanudando descarga..."
                    );

                    executor.execute(() -> {

                        try {

                            JSONObject result =
                                    api().resumeDownload(
                                            monitor.job
                                    );

                            boolean ok =
                                    result.optBoolean(
                                            "ok",
                                            false
                                    );

                            String message =
                                    result.optString(
                                            "message",
                                            ""
                                    );

                            String serverStatus =
                                    result.optString(
                                            "status",
                                            "running"
                                    );

                            handler.post(() -> {

                                if (ok) {

                                    status.setText(
                                            "▶ "
                                                    + (
                                                    message.isEmpty()
                                                            ? "Descarga reanudada."
                                                            : message
                                            )
                                    );

                                    updateMobileDownloadMonitorControls(
                                            monitor,
                                            serverStatus
                                    );

                                } else {

                                    status.setText(
                                            "❌ "
                                                    + (
                                                    message.isEmpty()
                                                            ? "No se pudo reanudar."
                                                            : message
                                            )
                                    );

                                    updateMobileDownloadMonitorControls(
                                            monitor,
                                            "paused"
                                    );
                                }
                            });

                        } catch (Exception e) {

                            handler.post(() -> {

                                status.setText(
                                        "❌ Error al reanudar: "
                                                + e.getMessage()
                                );

                                updateMobileDownloadMonitorControls(
                                        monitor,
                                        "paused"
                                );
                            });
                        }
                    });
                }
        );

        /*
         * CANCELAR
         */

        cancelButton.setOnClickListener(
                v -> {

                    new AlertDialog.Builder(this)
                            .setTitle(
                                    "Cancelar descarga"
                            )
                            .setMessage(
                                    "¿Quieres cancelar esta descarga?"
                            )
                            .setNegativeButton(
                                    "No",
                                    null
                            )
                            .setPositiveButton(
                                    "Sí, cancelar",
                                    (dialog, which) -> {

                                        cancelButton.setEnabled(
                                                false
                                        );

                                        pauseButton.setEnabled(
                                                false
                                        );

                                        resumeButton.setEnabled(
                                                false
                                        );

                                        status.setText(
                                                "✕ Cancelando descarga..."
                                        );

                                        executor.execute(() -> {

                                            try {

                                                JSONObject result =
                                                        api().cancelDownload(
                                                                monitor.job
                                                        );

                                                boolean ok =
                                                        result.optBoolean(
                                                                "ok",
                                                                false
                                                        );

                                                String message =
                                                        result.optString(
                                                                "message",
                                                                ""
                                                        );

                                                String serverStatus =
                                                        result.optString(
                                                                "status",
                                                                "cancelled"
                                                        );

                                                handler.post(() -> {

                                                    if (ok) {

                                                        status.setText(
                                                                "✕ "
                                                                        + (
                                                                        message.isEmpty()
                                                                                ? "Descarga cancelada."
                                                                                : message
                                                                        )
                                                        );

                                                        updateMobileDownloadMonitorControls(
                                                                monitor,
                                                                serverStatus
                                                        );

                                                    } else {

                                                        status.setText(
                                                                "❌ "
                                                                        + (
                                                                        message.isEmpty()
                                                                                ? "No se pudo cancelar."
                                                                                : message
                                                                        )
                                                        );

                                                        updateMobileDownloadMonitorControls(
                                                                monitor,
                                                                "running"
                                                        );
                                                    }
                                                });

                                            } catch (Exception e) {

                                                handler.post(() -> {

                                                    status.setText(
                                                            "❌ Error al cancelar: "
                                                                    + e.getMessage()
                                                    );

                                                    updateMobileDownloadMonitorControls(
                                                            monitor,
                                                            "running"
                                                    );
                                                });
                                            }
                                        });
                                    }
                            )
                            .show();
                }
        );

        monitor.titleView =
                title;

        monitor.statusView =
                status;

        monitor.progressBar =
                progress;

        monitor.progressView =
                percent;

        monitor.pauseButton =
                pauseButton;

        monitor.resumeButton =
                resumeButton;

        monitor.cancelButton =
                cancelButton;

        updateMobileDownloadMonitorControls(
                monitor,
                initialStatus
        );
    }

    private void updateMobileDownloadMonitorControls(
            MobileDownloadMonitor monitor,
            String state) {

        if (monitor == null) {
            return;
        }

        String value =
                state == null
                        ? ""
                        : state.toLowerCase(
                                java.util.Locale.ROOT
                        );

        boolean paused =
                "paused".equals(value)
                        || value.contains("paus");

        boolean finished =
                "done".equals(value)
                        || "cancelled".equals(value)
                        || "error".equals(value)
                        || value.contains("completado")
                        || value.contains("cancelada")
                        || value.contains("error");

        boolean active =
                !finished;

        if (monitor.pauseButton != null) {

            monitor.pauseButton.setEnabled(
                    active && !paused
            );
        }

        if (monitor.resumeButton != null) {

            monitor.resumeButton.setEnabled(
                    active && paused
            );
        }

        if (monitor.cancelButton != null) {

            monitor.cancelButton.setEnabled(
                    active
            );
        }
    }

    private void refreshMobileDownloadMonitorViews() {

        for (MobileDownloadMonitor monitor :
                mobileDownloadMonitors) {

            if (downloadsActiveList != null) {

                ensureMobileDownloadMonitorCard(
                        monitor
                );
            }

            int progress =
                    monitor.slot.progress.getProgress();

            String status =
                    monitor.slot.status.getText() == null
                            ? ""
                            : monitor.slot.status
                                    .getText()
                                    .toString();

            updateMobileDownloadMonitor(
                    monitor.job,
                    progress,
                    status
            );
        }
    }

    /*
     * BOTÓN MODERNO EXCLUSIVO DE SPOTIFY
     *
     * No modifica visualButton() ni roundedButton()
     * porque esos métodos pueden ser utilizados por
     * otras pantallas de la aplicación.
     */
    private Button spotifyModernButton(
            String text,
            boolean primary) {

        Button button = new Button(this);

        button.setText(text);
        button.setSingleLine(true);
        button.setAllCaps(false);
        button.setTextSize(primary ? 13 : 12);
        button.setTypeface(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
        );

        button.setTextColor(
                primary
                        ? Color.WHITE
                        : Color.rgb(215, 215, 225)
        );

        button.setGravity(Gravity.CENTER);

        button.setPadding(
                dp(8),
                0,
                dp(8),
                0
        );

        android.graphics.drawable.GradientDrawable background =
                new android.graphics.drawable.GradientDrawable();

        background.setColor(
                primary
                        ? Color.rgb(38, 42, 48)
                        : Color.rgb(29, 31, 37)
        );

        background.setCornerRadius(
                dp(13)
        );

        background.setStroke(
                dp(1),
                primary
                        ? Color.rgb(78, 82, 92)
                        : Color.rgb(55, 58, 68)
        );

        button.setBackground(background);

        if (android.os.Build.VERSION.SDK_INT >= 21) {
            button.setElevation(
                    dp(2)
            );
        }

        button.setMinHeight(0);
        button.setMinimumHeight(0);

        return button;
    }

    private void showSpotifyPlaylist(
            ApiClient.SpotifyPlaylist playlist) {

        contentLayout.removeAllViews();

        spotifySelectionChecks.clear();
        spotifySelectionTracks.clear();
        spotifyOfflineSlots.clear();

        Button backToSpotifyLists =
                spotifyModernButton(
                        "←  Volver a listas",
                        false
                );

        backToSpotifyLists.setTextSize(14);
        backToSpotifyLists.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams backButtonParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(46)
                );

        backButtonParams.setMargins(
                dp(4),
                dp(2),
                dp(4),
                dp(8)
        );

        backToSpotifyLists.setOnClickListener(
                v -> loadSpotifyLists()
        );

        contentLayout.addView(
                backToSpotifyLists,
                backButtonParams
        );

        /*
         * PORTADA DE LA PLAYLIST
         *
         * Las listas CSV no tienen portada propia.
         * Usamos la portada de la primera canción.
         */
        ImageView playlistCover =
                new ImageView(this);

        playlistCover.setScaleType(
                ImageView.ScaleType.CENTER_CROP
        );

        playlistCover.setImageResource(
                android.R.drawable.ic_menu_gallery
        );

        LinearLayout.LayoutParams playlistCoverParams =
                new LinearLayout.LayoutParams(
                        dp(204),
                        dp(204)
                );

        playlistCoverParams.gravity =
                Gravity.CENTER;

        playlistCoverParams.setMargins(
                0,
                dp(10),
                0,
                dp(12)
        );

        contentLayout.addView(
                playlistCover,
                playlistCoverParams
        );

        /*
         * Si la primera canción ya tiene portada,
         * la usamos directamente.
         *
         * Si no, buscamos una portada usando
         * artista + título.
         */
        if (playlist.tracks != null &&
                !playlist.tracks.isEmpty()) {

            ApiClient.SpotifyTrack firstTrack =
                    playlist.tracks.get(0);

            if (firstTrack != null) {

                String thumbnail =
                        firstTrack.thumbnail == null
                                ? ""
                                : firstTrack.thumbnail.trim();

                if (!thumbnail.isEmpty()) {

                    loadSpotifyThumbnailUrl(
                            thumbnail,
                            playlistCover
                    );

                } else {

                    executor.execute(() -> {

                        String artworkUrl =
                                findSpotifyArtwork(
                                        firstTrack.artist,
                                        firstTrack.title
                                );

                        if (artworkUrl == null ||
                                artworkUrl.trim().isEmpty()) {

                            return;
                        }

                        loadSpotifyThumbnailUrl(
                                artworkUrl,
                                playlistCover
                        );
                    });
                }
            }
        }

        TextView heading =
                new TextView(this);

        heading.setText(
                playlist.name
                        + "\n"
                        + playlist.tracks.size()
                        + " canciones"
        );

        heading.setTextSize(21);
        heading.setTypeface(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
        );
        heading.setTextColor(Color.WHITE);

        heading.setGravity(
                Gravity.CENTER
        );

        heading.setLineSpacing(
                0f,
                1.05f
        );

        LinearLayout.LayoutParams headingParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        headingParams.setMargins(
                dp(4),
                dp(8),
                dp(4),
                dp(12)
        );

        contentLayout.addView(
                heading,
                headingParams
        );

        Button playAll =
                spotifyModernButton(
                        "▶  Reproducir playlist completa",
                        true
                );



        playAll.setOnClickListener(
                v -> playSpotifyPlaylist(
                        playlist
                )
        );

        LinearLayout.LayoutParams mainButtonParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(46)
                );

        mainButtonParams.setMargins(
                dp(4),
                dp(3),
                dp(4),
                dp(5)
        );

        contentLayout.addView(
                playAll,
                mainButtonParams
        );

        Button downloadAll =
                spotifyModernButton(
                        "⬇  Descargar seleccionadas",
                        true
                );



        contentLayout.addView(
                downloadAll,
                mainButtonParams
        );



        LinearLayout selectionButtons =
                new LinearLayout(this);

        selectionButtons.setOrientation(
                LinearLayout.HORIZONTAL
        );

        selectionButtons.setGravity(
                Gravity.CENTER
        );

        LinearLayout.LayoutParams selectionButtonsParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        selectionButtonsParams.setMargins(
                0,
                dp(8),
                0,
                dp(8)
        );

        Button selectAll =
                spotifyModernButton("☑  Marcar todas", false);

        Button deselectAll =
                spotifyModernButton("☐  Desmarcar", false);

        Button downloadSelected =
                spotifyModernButton("⬇  Seleccionadas Offline", true);

        downloadSelected.setSingleLine(true);
        downloadSelected.setTextSize(12);

        LinearLayout.LayoutParams selectionButtonParams =
                new LinearLayout.LayoutParams(
                        0,
                        dp(42),
                        1f
                );

selectionButtonParams.setMargins(
        dp(2),
        0,
        dp(2),
        0
);

LinearLayout.LayoutParams downloadSelectedParams =
        new LinearLayout.LayoutParams(
                0,
                dp(44),
                1.35f
        );

downloadSelectedParams.setMargins(
        dp(2),
        0,
        dp(2),
        0
);

selectionButtons.addView(
        selectAll,
        selectionButtonParams
);

selectionButtons.addView(
        deselectAll,
        selectionButtonParams
);

selectionButtons.addView(
        downloadSelected,
        downloadSelectedParams
);

        contentLayout.addView(
                selectionButtons,
                selectionButtonsParams
        );

        selectAll.setOnClickListener(v -> {
            for (CheckBox check : spotifySelectionChecks) {
                check.setChecked(true);
            }
        });

        deselectAll.setOnClickListener(v -> {
            for (CheckBox check : spotifySelectionChecks) {
                check.setChecked(false);
            }
        });

        downloadSelected.setOnClickListener(
                v -> downloadSelectedSpotifyOffline()
        );

        List<DownloadSlot> slots =
                new ArrayList<>();

        spotifyPreparationStatusViews.clear();

        int spotifyTrackIndex = 0;

        for (
                ApiClient.SpotifyTrack track :
                playlist.tracks
        ) {

            final int currentSpotifyTrackIndex =
                    spotifyTrackIndex++;

            LinearLayout row =
                    new LinearLayout(this);

            row.setOrientation(
                    LinearLayout.VERTICAL
            );

            row.setPadding(
                    dp(11),
                    dp(11),
                    dp(11),
                    dp(11)
            );

            row.setBackground(
                    roundedBackground(
                            Color.rgb(25, 27, 32),
                            Color.rgb(48, 51, 59),
                            20
                    )
            );

            if (android.os.Build.VERSION.SDK_INT >= 21) {
                row.setElevation(
                        dp(2)
                );
            }

            LinearLayout.LayoutParams rowParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );

            rowParams.setMargins(
                    dp(6),
                    dp(6),
                    dp(6),
                    dp(6)
            );

            /*
             * CABECERA DE LA CANCIÓN
             */

            LinearLayout top =
                    new LinearLayout(this);

            top.setOrientation(
                    LinearLayout.HORIZONTAL
            );

            CheckBox select =
                    new CheckBox(this);

            select.setText("");
            select.setPadding(0, 0, 0, 0);

            select.setButtonTintList(
                    new android.content.res.ColorStateList(
                            new int[][]{
                                    new int[]{
                                            android.R.attr.state_checked
                                    },
                                    new int[]{}
                            },
                            new int[]{
                                    Color.rgb(90, 200, 140),
                                    Color.rgb(130, 130, 140)
                            }
                    )
            );

            LinearLayout.LayoutParams selectParams =
                    new LinearLayout.LayoutParams(
                            dp(34),
                            dp(76)
                    );

            top.addView(
                    select,
                    selectParams
            );

            ImageView cover =
                    new ImageView(this);

            cover.setScaleType(
                    ImageView.ScaleType.CENTER_CROP
            );

            LinearLayout.LayoutParams coverParams =
                    new LinearLayout.LayoutParams(
                            dp(78),
                            dp(78)
                    );

            coverParams.setMargins(
                    0,
                    0,
                    dp(13),
                    0
            );

            top.addView(
                    cover,
                    coverParams
            );

            LinearLayout info =
                    new LinearLayout(this);

            info.setOrientation(
                    LinearLayout.VERTICAL
            );

            info.setGravity(
                    Gravity.CENTER_VERTICAL
            );

            LinearLayout.LayoutParams infoParams =
                    new LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                    );

            TextView title =
                    new TextView(this);

            title.setText(
                    track.title
            );

            title.setTextSize(15);
            title.setTypeface(
                    android.graphics.Typeface.DEFAULT,
                    android.graphics.Typeface.BOLD
            );
            title.setTextColor(Color.WHITE);
            title.setMaxLines(2);

            title.setEllipsize(
                    android.text.TextUtils.TruncateAt.END
            );

            info.addView(title);

            String details =
                    track.artist == null
                            ? ""
                            : track.artist;

            if (
                    track.album != null &&
                    !track.album.isEmpty()
            ) {
                details +=
                        "  ·  💿 "
                                + track.album;
            }

            if (track.duration > 0) {
                details +=
                        "  ·  "
                                + formatTime(
                                track.duration * 1000L
                        );
            }

            TextView artist =
                    new TextView(this);

            artist.setText(details);
            artist.setTextSize(11);
            artist.setTextColor(
                    Color.rgb(158, 160, 170)
            );

            artist.setMaxLines(2);

            artist.setEllipsize(
                    android.text.TextUtils.TruncateAt.END
            );

            LinearLayout.LayoutParams artistParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );

            artistParams.setMargins(
                    0,
                    dp(7),
                    0,
                    0
            );

            info.addView(
                    artist,
                    artistParams
            );

            TextView obtainedStatus =
                    createObtainedStatusView();

            info.addView(
                    obtainedStatus
            );

            spotifyPreparationStatusViews.add(
                    obtainedStatus
            );

            top.addView(
                    info,
                    infoParams
            );

            row.addView(top);

            /*
             * BOTONES
             */

            LinearLayout buttons =
                    new LinearLayout(this);

            buttons.setOrientation(
                    LinearLayout.HORIZONTAL
            );

            buttons.setGravity(
                    Gravity.CENTER_VERTICAL
            );

            LinearLayout.LayoutParams buttonsParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );

            buttonsParams.setMargins(
                    0,
                    dp(11),
                    0,
                    0
            );

            Button play =
                    spotifyModernButton(
                            "▶  Escuchar",
                            true
                    );

            LinearLayout.LayoutParams playParams =
                    new LinearLayout.LayoutParams(
                            0,
                            dp(40),
                            1f
                    );

            playParams.setMargins(
                    0,
                    0,
                    dp(4),
                    0
            );

            play.setOnClickListener(
                    v -> playSpotifyPlaylistFromIndex(
                            playlist,
                            currentSpotifyTrackIndex
                    )
            );

            buttons.addView(
                    play,
                    playParams
            );

            Button download =
                    spotifyModernButton(
                            "⬇  Descargar",
                            false
                    );

            LinearLayout.LayoutParams downloadParams =
                    new LinearLayout.LayoutParams(
                            0,
                            dp(40),
                            1f
                    );

            downloadParams.setMargins(
                    dp(4),
                    0,
                    dp(4),
                    0
            );

            buttons.addView(
                    download,
                    downloadParams
            );

            Button offline =
                    spotifyModernButton(
                            "📱  Offline",
                            false
                    );

            LinearLayout.LayoutParams offlineParams =
                    new LinearLayout.LayoutParams(
                            0,
                            dp(40),
                            1f
                    );

            offlineParams.setMargins(
                    dp(4),
                    0,
                    0,
                    0
            );

            buttons.addView(
                    offline,
                    offlineParams
            );

            row.addView(
                    buttons,
                    buttonsParams
            );

            /*
             * DESCARGA NORMAL
             */

            ProgressBar progress =
                    new ProgressBar(
                            this,
                            null,
                            android.R.attr.progressBarStyleHorizontal
                    );

            progress.setMax(100);
            progress.setProgress(0);

            LinearLayout.LayoutParams progressParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(3)
                    );

            progressParams.setMargins(
                    0,
                    dp(9),
                    0,
                    0
            );

            row.addView(
                    progress,
                    progressParams
            );

            TextView status =
                    new TextView(this);

            status.setText(
                    "Listo para descargar"
            );

            status.setTextSize(10);
            status.setTextColor(
                    Color.rgb(132, 134, 144)
            );

            row.addView(status);

            DownloadSlot slot =
                    new DownloadSlot(
                            track,
                            download,
                            progress,
                            status
                    );

            slots.add(slot);

            spotifySelectionChecks.add(select);
            spotifySelectionTracks.add(track);

            download.setOnClickListener(
                    v -> downloadSpotifyTrack(
                            slot
                    )
            );

            /*
             * OFFLINE
             */

            ProgressBar offlineProgress =
                    new ProgressBar(
                            this,
                            null,
                            android.R.attr.progressBarStyleHorizontal
                    );

            offlineProgress.setMax(100);
            offlineProgress.setProgress(0);

            LinearLayout.LayoutParams offlineProgressParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(3)
                    );

            offlineProgressParams.setMargins(
                    0,
                    dp(5),
                    0,
                    0
            );

            row.addView(
                    offlineProgress,
                    offlineProgressParams
            );

            TextView offlineStatus =
                    new TextView(this);

            offlineStatus.setText(
                    "📱 Listo para guardar offline"
            );

            offlineStatus.setTextSize(10);
            offlineStatus.setTextColor(
                    Color.rgb(128, 130, 140)
            );

            row.addView(offlineStatus);

            DownloadSlot offlineSlot =
                    new DownloadSlot(
                            track,
                            offline,
                            offlineProgress,
                            offlineStatus
                    );

            spotifyOfflineSlots.add(offlineSlot);

            offline.setOnClickListener(
                    v -> startMobileDownloadSpotifyTrack(
                            track,
                            offlineSlot
                    )
            );

            /*
             * PORTADA
             */

            loadSpotifyThumbnail(
                    track,
                    cover
            );

            contentLayout.addView(
                    row,
                    rowParams
            );
        }

        downloadAll.setOnClickListener(
                v -> {
                    boolean found = false;

                    for (int i = 0; i < spotifySelectionChecks.size(); i++) {

                        if (spotifySelectionChecks.get(i).isChecked()) {

                            found = true;

                            if (i < slots.size()) {
                                downloadSpotifyTrack(
                                        slots.get(i)
                                );
                            }
                        }
                    }

                    if (!found) {
                        Toast.makeText(
                                this,
                                "⚠️ No hay canciones seleccionadas",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                }
        );
    }

    private void playSpotifyPlaylistFromIndex(
            ApiClient.SpotifyPlaylist playlist,
            int startIndex) {
        final long playbackGeneration =
                beginIndividualPlayback();


        
        final int playlistTotal =
                playlist != null &&
                playlist.tracks != null
                        ? playlist.tracks.size()
                        : 0;

        final long queueGeneration =
                beginQueuePreparation(
                        playlistTotal
                );

if (playlist == null ||
                playlist.tracks == null ||
                playlist.tracks.isEmpty() ||
                startIndex < 0 ||
                startIndex >= playlist.tracks.size()) {

            return;
        }

        Toast.makeText(
                this,
                "Preparando desde la canción "
                        + (startIndex + 1)
                        + "...",
                Toast.LENGTH_SHORT
        ).show();

        executor.execute(() -> {

            try {

                final int total =
                        playlist.tracks.size();

                /*
                 * =========================================================
                 * 1. RESOLVER PRIMERO LA CANCIÓN SELECCIONADA
                 * =========================================================
                 */

                ApiClient.SpotifyTrack firstTrack =
                        playlist.tracks.get(startIndex);

                if (
                        firstTrack == null ||
                        firstTrack.artist == null ||
                        firstTrack.title == null ||
                        (
                                firstTrack.artist.trim().isEmpty() &&
                                firstTrack.title.trim().isEmpty()
                        )
                ) {

                    handler.post(() ->
                            Toast.makeText(
                                    this,
                                    "No se pudo resolver la canción",
                                    Toast.LENGTH_SHORT
                            ).show()
                    );

                    return;
                }

                ApiClient.Song firstResolved =
                        api().resolveAlbumTrack(
                                firstTrack.artist,
                                firstTrack.title
                        );

                if (
                        firstResolved == null ||
                        firstResolved.id == null ||
                        firstResolved.id.trim().isEmpty()
                ) {

                    handler.post(() ->
                            Toast.makeText(
                                    this,
                                    "No se encontró el audio",
                                    Toast.LENGTH_SHORT
                            ).show()
                    );

                    return;
                }

                String firstUrl =
                        api().getPreviewUrl(
                                firstResolved.id
                        );

                if (
                        firstUrl == null ||
                        firstUrl.trim().isEmpty()
                ) {

                    handler.post(() ->
                            Toast.makeText(
                                    this,
                                    "No se pudo obtener el audio",
                                    Toast.LENGTH_SHORT
                            ).show()
                    );

                    return;
                }

                MediaMetadata.Builder firstMetadata =
                        new MediaMetadata.Builder()
                                .setTitle(
                                        firstTrack.title
                                );

                if (
                        firstTrack.artist != null &&
                        !firstTrack.artist.trim().isEmpty()
                ) {

                    firstMetadata.setArtist(
                            firstTrack.artist
                    );
                }

                if (
                        firstTrack.album != null &&
                        !firstTrack.album.trim().isEmpty()
                ) {

                    firstMetadata.setAlbumTitle(
                            firstTrack.album
                    );
                }

                String firstThumbnail =
                        firstTrack.thumbnail;

                if (
                        (firstThumbnail == null ||
                                firstThumbnail.trim().isEmpty()) &&
                        firstResolved.thumbnail != null &&
                        !firstResolved.thumbnail.trim().isEmpty()
                ) {

                    firstThumbnail =
                            firstResolved.thumbnail;
                }

                if (
                        firstThumbnail != null &&
                        !firstThumbnail.trim().isEmpty()
                ) {

                    firstMetadata.setArtworkUri(
                            Uri.parse(firstThumbnail)
                    );
                }

                MediaItem firstItem =
                        new MediaItem.Builder()
                                .setUri(firstUrl)
                                .setMediaMetadata(
                                        firstMetadata.build()
                                )
                                .build();

                List<MediaItem> firstList =
                        new ArrayList<>();

                firstList.add(firstItem);

                /*
                 * =========================================================
                 * 2. ARRANCAR EXACTAMENTE COMO REPRODUCIR TODO
                 * =========================================================
                 */

                handler.post(() -> {
                    if (!isCurrentIndividualPlayback(
                            playbackGeneration)) {
                        return;
                    }

                    sendQueueToPlayer(firstList);

                    updateQueuePreparationProgress(
                            queueGeneration,
                            1,
                            playlistTotal
                    );

                    markSpotifyTrackObtained(
                            queueGeneration,
                            startIndex
                    );
                });

                /*
                 * =========================================================
                 * 3. RESOLVER EL RESTO DESDE startIndex + 1
                 *
                 * Usamos exactamente el mismo sistema que Spotify TODO:
                 * varios resolvers en paralelo + mapa por índice +
                 * incorporación estrictamente consecutiva.
                 * =========================================================
                 */

                if (startIndex >= total - 1) {

                    handler.post(() ->
                            Toast.makeText(
                                    this,
                                    "Spotify preparado: 1 canción",
                                    Toast.LENGTH_SHORT
                            ).show()
                    );

                    return;
                }

                int remaining =
                        total - startIndex - 1;

                ExecutorService resolver =
                        Executors.newFixedThreadPool(
                                Math.min(
                                        3,
                                        Math.max(
                                                1,
                                                remaining
                                        )
                                )
                        );

                java.util.concurrent.ExecutorCompletionService<
                        java.util.AbstractMap.SimpleEntry<
                                Integer,
                                MediaItem
                        >
                        > completionService =
                        new java.util.concurrent.ExecutorCompletionService<>(
                                resolver
                        );

                int submitted = 0;

                for (
                        int index = startIndex + 1;
                        index < total;
                        index++
                ) {

                    final int trackIndex = index;

                    completionService.submit(() -> {

                        ApiClient.SpotifyTrack track =
                                playlist.tracks.get(
                                        trackIndex
                                );

                        try {

                            if (
                                    track == null ||
                                    track.artist == null ||
                                    track.title == null ||
                                    (
                                            track.artist.trim().isEmpty() &&
                                            track.title.trim().isEmpty()
                                    )
                            ) {

                                return new java.util.AbstractMap.SimpleEntry<>(
                                        trackIndex,
                                        null
                                );
                            }

                            ApiClient.Song resolved =
                                    api().resolveAlbumTrack(
                                            track.artist,
                                            track.title
                                    );

                            if (
                                    resolved == null ||
                                    resolved.id == null ||
                                    resolved.id.trim().isEmpty()
                            ) {

                                return new java.util.AbstractMap.SimpleEntry<>(
                                        trackIndex,
                                        null
                                );
                            }

                            String url =
                                    api().getPreviewUrl(
                                            resolved.id
                                    );

                            if (
                                    url == null ||
                                    url.trim().isEmpty()
                            ) {

                                return new java.util.AbstractMap.SimpleEntry<>(
                                        trackIndex,
                                        null
                                );
                            }

                            MediaMetadata.Builder metadata =
                                    new MediaMetadata.Builder()
                                            .setTitle(
                                                    track.title
                                            );

                            if (
                                    track.artist != null &&
                                    !track.artist.trim().isEmpty()
                            ) {

                                metadata.setArtist(
                                        track.artist
                                );
                            }

                            if (
                                    track.album != null &&
                                    !track.album.trim().isEmpty()
                            ) {

                                metadata.setAlbumTitle(
                                        track.album
                                );
                            }

                            String thumbnail =
                                    track.thumbnail;

                            if (
                                    (thumbnail == null ||
                                            thumbnail.trim().isEmpty()) &&
                                    resolved.thumbnail != null &&
                                    !resolved.thumbnail.trim().isEmpty()
                            ) {

                                thumbnail =
                                        resolved.thumbnail;
                            }

                            if (
                                    thumbnail != null &&
                                    !thumbnail.trim().isEmpty()
                            ) {

                                metadata.setArtworkUri(
                                        Uri.parse(thumbnail)
                                );
                            }

                            MediaItem item =
                                    new MediaItem.Builder()
                                            .setUri(url)
                                            .setMediaMetadata(
                                                    metadata.build()
                                            )
                                            .build();

                            return new java.util.AbstractMap.SimpleEntry<>(
                                    trackIndex,
                                    item
                            );

                        } catch (Exception error) {

                            System.err.println(
                                    "Spotify individual: no se pudo "
                                            + "resolver la canción "
                                            + (trackIndex + 1)
                                            + ": "
                                            + error
                            );

                            return new java.util.AbstractMap.SimpleEntry<>(
                                    trackIndex,
                                    null
                            );
                        }
                    });

                    submitted++;
                }

                /*
                 * =========================================================
                 * 4. RECIBIR Y AÑADIR SIEMPRE EN ORDEN
                 * =========================================================
                 */

                java.util.Map<Integer, MediaItem> ready =
                        new java.util.HashMap<>();

                int nextIndex =
                        startIndex + 1;

                int completed = 0;

                int addedCount = 1;

                while (completed < submitted) {

                    java.util.concurrent.Future<
                            java.util.AbstractMap.SimpleEntry<
                                    Integer,
                                    MediaItem
                            >
                            > future =
                            completionService.take();

                    java.util.AbstractMap.SimpleEntry<
                            Integer,
                            MediaItem
                            > result =
                            future.get();

                    completed++;

                    int resultIndex =
                            result.getKey();

                    MediaItem resultItem =
                            result.getValue();

                    ready.put(
                            resultIndex,
                            resultItem
                    );

                    while (
                            ready.containsKey(nextIndex)
                    ) {

                        MediaItem item =
                                ready.remove(
                                        nextIndex
                                );

                        nextIndex++;

                        if (item == null) {

                            continue;
                        }

                        List<MediaItem> one =
                                new ArrayList<>();

                        one.add(item);

                        int preparedTrackIndex =
                                nextIndex - 1;

                        addedCount++;

                        updateQueuePreparationProgress(
                                queueGeneration,
                                addedCount,
                                playlistTotal
                        );

                        markSpotifyTrackObtained(
                                queueGeneration,
                                preparedTrackIndex
                        );

                        handler.post(() -> {
                            if (!isCurrentIndividualPlayback(
                                    playbackGeneration)) {
                                return;
                            }

                            addItemsToPlayerQueue(one);
                        });
                    }
                }

                resolver.shutdown();

                final int finalAddedCount =
                        addedCount;

                handler.post(() ->
                        Toast.makeText(
                                this,
                                "Spotify preparado: "
                                        + finalAddedCount
                                        + " canciones",
                                Toast.LENGTH_SHORT
                        ).show()
                );

            } catch (Exception error) {

                handler.post(
                        () -> showError(error)
                );
            }
        });
    }

    private void playSpotifyTrack(
            ApiClient.SpotifyTrack track) {

        Toast.makeText(
                this,
                "Buscando audio: "
                        + track.title,
                Toast.LENGTH_SHORT
        ).show();

        executor.execute(() -> {

            try {

                ApiClient.Song resolved =
                        api().resolveAlbumTrack(
                                track.artist,
                                track.title
                        );

                String url =
                        api().getPreviewUrl(
                                resolved.id
                        );

                String thumbnail =
                        track.thumbnail;

                if (
                        (thumbnail == null ||
                                thumbnail.trim().isEmpty()) &&
                        resolved.thumbnail != null &&
                        !resolved.thumbnail.trim().isEmpty()
                ) {
                    thumbnail =
                            resolved.thumbnail;
                }

                final String finalThumbnail =
                        thumbnail;

                handler.post(
                        () -> playResolved(
                                url,
                                track.artist
                                        + " - "
                                        + track.title,
                                finalThumbnail
                        )
                );

            } catch (Exception e) {

                handler.post(
                        () -> showError(e)
                );
            }
        });
    }

    private void playSpotifyPlaylist(
            ApiClient.SpotifyPlaylist playlist) {

        final long playbackGeneration =
                beginIndividualPlayback();

        
        final int playlistTotal =
                playlist != null &&
                playlist.tracks != null
                        ? playlist.tracks.size()
                        : 0;

        final long queueGeneration =
                beginQueuePreparation(
                        playlistTotal
                );

Toast.makeText(
                this,
                "Preparando primera canción...",
                Toast.LENGTH_LONG
        ).show();

        executor.execute(() -> {

            try {

                final int count =
                        playlist.tracks.size();

                if (count == 0) {

                    handler.post(() -> {
                        if (!isCurrentIndividualPlayback(
                                playbackGeneration)) {
                            return;
                        }

                        Toast.makeText(
                                this,
                                "La playlist no tiene canciones",
                                Toast.LENGTH_SHORT
                        ).show();
                    });

                    return;
                }

                /*
                 * =========================================================
                 * 1. RESOLVER PRIMERO LA PRIMERA CANCIÓN
                 * =========================================================
                 */

                ApiClient.SpotifyTrack firstTrack =
                        playlist.tracks.get(0);

                if (
                        firstTrack.artist.isEmpty() &&
                        firstTrack.title.isEmpty()
                ) {

                    handler.post(() -> {
                        if (!isCurrentIndividualPlayback(
                                playbackGeneration)) {
                            return;
                        }

                        Toast.makeText(
                                this,
                                "No se pudo resolver la primera canción",
                                Toast.LENGTH_SHORT
                        ).show();
                    });

                    return;
                }

                ApiClient.Song firstResolved =
                        api().resolveAlbumTrack(
                                firstTrack.artist,
                                firstTrack.title
                        );

                String firstUrl =
                        api().getPreviewUrl(
                                firstResolved.id
                        );

                MediaMetadata.Builder firstMetadata =
                        new MediaMetadata.Builder()
                                .setTitle(
                                        firstTrack.title
                                );

                if (!firstTrack.artist.isEmpty()) {

                    firstMetadata.setArtist(
                            firstTrack.artist
                    );
                }

                if (
                        firstTrack.album != null &&
                        !firstTrack.album.isEmpty()
                ) {

                    firstMetadata.setAlbumTitle(
                            firstTrack.album
                    );
                }

                String firstThumbnail =
                        firstTrack.thumbnail;

                if (
                        (firstThumbnail == null ||
                                firstThumbnail.trim().isEmpty()) &&
                        firstResolved.thumbnail != null &&
                        !firstResolved.thumbnail.trim().isEmpty()
                ) {
                    firstThumbnail =
                            firstResolved.thumbnail;
                }

                if (
                        firstThumbnail != null &&
                        !firstThumbnail.trim().isEmpty()
                ) {
                    firstMetadata.setArtworkUri(
                            Uri.parse(firstThumbnail)
                    );
                }

                MediaItem firstItem =
                        new MediaItem.Builder()
                                .setUri(firstUrl)
                                .setMediaMetadata(
                                        firstMetadata.build()
                                )
                                .build();

                List<MediaItem> firstList =
                        new ArrayList<>();

                firstList.add(firstItem);

                /*
                 * =========================================================
                 * 2. ARRANCAR LA REPRODUCCIÓN
                 * =========================================================
                 */

                handler.post(() -> {

                    if (!isCurrentIndividualPlayback(
                            playbackGeneration)) {
                        return;
                    }

                    sendQueueToPlayer(firstList);

                    updateQueuePreparationProgress(
                            queueGeneration,
                            1,
                            playlistTotal
                    );

                    markSpotifyTrackObtained(
                            queueGeneration,
                            0
                    );
                });

                if (count == 1) {

                    handler.post(() -> {

                        if (!isCurrentIndividualPlayback(
                                playbackGeneration)) {
                            return;
                        }

                        Toast.makeText(
                                this,
                                "Spotify preparado: 1 canción",
                                Toast.LENGTH_SHORT
                        ).show();
                    });

                    return;
                }

                /*
                 * =========================================================
                 * 3. RESOLVER EL RESTO EN SEGUNDO PLANO
                 * =========================================================
                 */

                ExecutorService resolver =
                        Executors.newFixedThreadPool(
                                Math.min(
                                        6,
                                        Math.max(
                                                1,
                                                count - 1
                                        )
                                )
                        );

                java.util.concurrent.ExecutorCompletionService<
                        java.util.AbstractMap.SimpleEntry<
                                Integer,
                                MediaItem
                        >
                        > completionService =
                        new java.util.concurrent.ExecutorCompletionService<>(
                                resolver
                        );

                int submitted = 0;

                for (int index = 1; index < count; index++) {

                    final int trackIndex = index;

                    completionService.submit(() -> {

                        ApiClient.SpotifyTrack track =
                                playlist.tracks.get(trackIndex);

                        try {

                            if (
                                    track.artist.isEmpty() &&
                                    track.title.isEmpty()
                            ) {
                                return new java.util.AbstractMap.SimpleEntry<>(
                                        trackIndex,
                                        null
                                );
                            }

                            ApiClient.Song resolved =
                                    api().resolveAlbumTrack(
                                            track.artist,
                                            track.title
                                    );

                            String url =
                                    api().getPreviewUrl(
                                            resolved.id
                                    );

                            MediaMetadata.Builder metadata =
                                    new MediaMetadata.Builder()
                                            .setTitle(
                                                    track.title
                                            );

                            if (!track.artist.isEmpty()) {

                                metadata.setArtist(
                                        track.artist
                                );
                            }

                            if (
                                    track.album != null &&
                                    !track.album.isEmpty()
                            ) {

                                metadata.setAlbumTitle(
                                        track.album
                                );
                            }

                            String thumbnail =
                                    track.thumbnail;

                            if (
                                    (thumbnail == null ||
                                            thumbnail.trim().isEmpty()) &&
                                    resolved.thumbnail != null &&
                                    !resolved.thumbnail.trim().isEmpty()
                            ) {
                                thumbnail =
                                        resolved.thumbnail;
                            }

                            if (
                                    thumbnail != null &&
                                    !thumbnail.trim().isEmpty()
                            ) {
                                metadata.setArtworkUri(
                                        Uri.parse(thumbnail)
                                );
                            }

                            MediaItem item =
                                    new MediaItem.Builder()
                                            .setUri(url)
                                            .setMediaMetadata(
                                                    metadata.build()
                                            )
                                            .build();

                            return new java.util.AbstractMap.SimpleEntry<>(
                                    trackIndex,
                                    item
                            );

                        } catch (Exception error) {

                            System.err.println(
                                    "Spotify: no se pudo resolver "
                                            + "la canción "
                                            + (trackIndex + 1)
                                            + ": "
                                            + error
                            );

                            return new java.util.AbstractMap.SimpleEntry<>(
                                    trackIndex,
                                    null
                            );
                        }
                    });

                    submitted++;
                }

                /*
                 * =========================================================
                 * 4. RECIBIR RESULTADOS SEGÚN TERMINEN
                 * =========================================================
                 */

                java.util.Map<Integer, MediaItem> ready =
                        new java.util.HashMap<>();

                int nextIndex = 1;
                int completed = 0;
                int addedCount = 1;

                while (completed < submitted) {

                    java.util.concurrent.Future<
                            java.util.AbstractMap.SimpleEntry<
                                    Integer,
                                    MediaItem
                            >
                            > future =
                            completionService.take();

                    java.util.AbstractMap.SimpleEntry<
                            Integer,
                            MediaItem
                            > result =
                            future.get();

                    completed++;

                    int resultIndex =
                            result.getKey();

                    MediaItem resultItem =
                            result.getValue();

                    /*
                     * Si ya hemos empezado otra reproducción,
                     * no seguimos preparando esta playlist.
                     */
                    if (!isCurrentIndividualPlayback(
                            playbackGeneration)) {
                        break;
                    }

                    ready.put(
                            resultIndex,
                            resultItem
                    );

                    /*
                     * =====================================================
                     * Añadir solamente elementos consecutivos.
                     * =====================================================
                     */

                    while (
                            ready.containsKey(nextIndex)
                    ) {

                        MediaItem item =
                                ready.remove(nextIndex);

                        nextIndex++;

                        if (item == null) {

                            System.err.println(
                                    "Spotify: se omite la canción "
                                            + nextIndex
                                            + " porque no pudo resolverse"
                            );

                            continue;
                        }

                        List<MediaItem> one =
                                new ArrayList<>();

                        one.add(item);

                        int preparedTrackIndex =
                                nextIndex - 1;

                        addedCount++;

                        updateQueuePreparationProgress(
                                queueGeneration,
                                addedCount,
                                playlistTotal
                        );

                        markSpotifyTrackObtained(
                                queueGeneration,
                                preparedTrackIndex
                        );

                        handler.post(() -> {

                            if (!isCurrentIndividualPlayback(
                                    playbackGeneration)) {
                                return;
                            }

                            addItemsToPlayerQueue(one);
                        });
                    }
                }

                resolver.shutdown();

                final int finalAddedCount =
                        addedCount;

                handler.post(() -> {

                    if (!isCurrentIndividualPlayback(
                            playbackGeneration)) {
                        return;
                    }

                    Toast.makeText(
                            this,
                            "Spotify preparado: "
                                    + finalAddedCount
                                    + " canciones",
                            Toast.LENGTH_SHORT
                    ).show();
                });

            } catch (Exception e) {

                handler.post(() -> {

                    if (!isCurrentIndividualPlayback(
                            playbackGeneration)) {
                        return;
                    }

                    showError(e);
                });
            }
        });
    }

    private static class DownloadSlot {

        final String videoId;
        final String title;
        final ApiClient.SpotifyTrack track;
        final ApiClient.Song albumTrack;
        final Button button;
        final ProgressBar progress;
        final TextView status;

        final String albumGroup;
        final String albumTitle;
        final int albumTrackIndex;
        final int albumTrackTotal;

        DownloadSlot(
                String videoId,
                String title,
                Button button,
                ProgressBar progress,
                TextView status) {

            this.videoId = videoId;
            this.title = title;
            this.track = null;
            this.albumTrack = null;
            this.button = button;
            this.progress = progress;
            this.status = status;
            this.albumGroup = null;
            this.albumTitle = null;
            this.albumTrackIndex = 0;
            this.albumTrackTotal = 0;
        }

        DownloadSlot(
                ApiClient.Song albumTrack,
                Button button,
                ProgressBar progress,
                TextView status) {

            this.videoId = "";
            this.title = albumTrack.title;
            this.track = null;
            this.albumTrack = albumTrack;
            this.button = button;
            this.progress = progress;
            this.status = status;
            this.albumGroup = null;
            this.albumTitle = null;
            this.albumTrackIndex = 0;
            this.albumTrackTotal = 0;
        }

        DownloadSlot(
                ApiClient.SpotifyTrack track,
                Button button,
                ProgressBar progress,
                TextView status) {

            this.videoId = "";
            this.title = track.title;
            this.track = track;
            this.albumTrack = null;
            this.button = button;
            this.progress = progress;
            this.status = status;
            this.albumGroup = null;
            this.albumTitle = null;
            this.albumTrackIndex = 0;
            this.albumTrackTotal = 0;
        }

        DownloadSlot(
                String videoId,
                String title,
                Button button,
                ProgressBar progress,
                TextView status,
                String albumGroup,
                String albumTitle,
                int albumTrackIndex,
                int albumTrackTotal) {

            this.videoId = videoId;
            this.title = title;
            this.track = null;
            this.albumTrack = null;
            this.button = button;
            this.progress = progress;
            this.status = status;
            this.albumGroup = albumGroup;
            this.albumTitle = albumTitle;
            this.albumTrackIndex = albumTrackIndex;
            this.albumTrackTotal = albumTrackTotal;
        }
    }

    private void watchDownloadJob(
            String job,
            DownloadSlot slot) {

        final Handler pollHandler =
                new Handler(
                        Looper.getMainLooper()
                );

        final Runnable[] checker =
                new Runnable[1];

        checker[0] = () -> {

            executor.execute(() -> {

                try {

                    JSONObject data =
                            api().getDownloadStatus(
                                    job
                            );

                    String state =
                            data.optString(
                                    "status",
                                    ""
                            );

                    int progressValue =
                            data.optInt(
                                    "progress",
                                    0
                            );

                    String message =
                            data.optString(
                                    "message",
                                    ""
                            );

                    handler.post(() -> {

                        slot.progress.setProgress(
                                Math.max(
                                        0,
                                        Math.min(
                                                100,
                                                progressValue
                                        )
                                )
                        );

                        if ("queued".equals(state)) {

                            slot.status.setText(
                                    "🕐 Procesando cola..."
                            );

                        } else if ("running".equals(state)) {

                            if (
                                    message.contains(
                                            "Procesando etiquetas"
                                    )
                            ) {

                                slot.status.setText(
                                        "🖼️ Procesando etiquetas y portada..."
                                );

                            } else if (
                                    message.contains(
                                            "Descargando"
                                    )
                            ) {

                                slot.status.setText(
                                        "⬇️ " + message
                                );

                            } else {

                                slot.status.setText(
                                        "⬇️ Descargando... "
                                                + progressValue
                                                + "%"
                                );
                            }

                        } else if ("done".equals(state)) {

                            slot.progress.setProgress(100);

                            slot.status.setText(
                                    "✓ DESCARGADA"
                            );

                            slot.button.setText(
                                    "✓ Descargada"
                            );

                            slot.button.setEnabled(false);

                            handler.postDelayed(() -> {

                                slot.status.setText(
                                        "🎧 ENVIADA A NAVIDROME"
                                );

                                slot.button.setText(
                                        "✓ Completado"
                                );

                            }, 700);

                            return;

                        } else if ("error".equals(state)) {

                            slot.status.setText(
                                    "❌ "
                                            + (
                                            message.isEmpty()
                                                    ? "Error durante la descarga"
                                                    : message
                                    )
                            );

                            slot.button.setText(
                                    "↻ Reintentar"
                            );

                            slot.button.setEnabled(true);

                            return;
                        }

                        pollHandler.postDelayed(
                                checker[0],
                                1500
                        );
                    });

                } catch (Exception e) {

                    handler.post(() -> {

                        slot.status.setText(
                                "❌ Error consultando descarga"
                        );

                        slot.button.setText(
                                "↻ Reintentar"
                        );

                        slot.button.setEnabled(true);
                    });
                }
            });
        };

        pollHandler.post(checker[0]);
    }


    private void watchMobileDownloadJob(
            String job,
            DownloadSlot slot) {

        Log.d(
                "MUSIC_DOWNLOAD_DEBUG",
                "WATCH OFFLINE INICIADO: job=" + job
        );

        final PowerManager powerManager =
                (PowerManager) getSystemService(
                        POWER_SERVICE
                );

        final PowerManager.WakeLock wakeLock =
                powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "MusicDownloader:OfflineDownload"
                );

        wakeLock.setReferenceCounted(false);

        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }

        Log.d(
                "MUSIC_DOWNLOAD_DEBUG",
                "WAKELOCK ADQUIRIDO: job=" + job
        );

        final Handler pollHandler =
                new Handler(
                        Looper.getMainLooper()
                );

        final Runnable[] checker =
                new Runnable[1];

        checker[0] = () -> {

            Log.d(
                    "MUSIC_DOWNLOAD_DEBUG",
                    "POLL OFFLINE: consultando job=" + job
            );

            executor.execute(() -> {

                try {

                    JSONObject data =
                            api().getDownloadStatus(job);

                    Log.d(
                            "MUSIC_DOWNLOAD_DEBUG",
                            "POLL RESPUESTA: " + data.toString()
                    );

                    String state =
                            data.optString(
                                    "status",
                                    ""
                            );

                    int progress =
                            data.optInt(
                                    "progress",
                                    0
                            );

                    String message =
                            data.optString(
                                    "message",
                                    ""
                            );

                    Log.d(
                            "MUSIC_DOWNLOAD_DEBUG",
                            "POLL DATOS: state="
                                    + state
                                    + " progress="
                                    + progress
                                    + " message="
                                    + message
                    );

                    handler.post(() -> {

                        Log.d(
                                "MUSIC_DOWNLOAD_DEBUG",
                                "POLL UI: state="
                                        + state
                        );

                        int safeProgress =
                                Math.max(
                                        0,
                                        Math.min(
                                                100,
                                                progress
                                        )
                                );

                        slot.progress.setProgress(
                                safeProgress
                        );

                        String monitorStatus =
                                "📱 "
                                        + (
                                        message.isEmpty()
                                                ? "Preparando canción..."
                                                : message
                                );

                        updateMobileDownloadMonitor(
                                job,
                                safeProgress,
                                monitorStatus
                        );

                        if ("queued".equals(state)
                                || "running".equals(state)
                                || "downloading".equals(state)
                                || "processing".equals(state)) {

                            Log.d(
                                    "MUSIC_DOWNLOAD_DEBUG",
                                    "POLL: descarga todavía activa"
                            );

                            slot.status.setText(
                                    "📱 "
                                            + (
                                            message.isEmpty()
                                                    ? "Preparando canción..."
                                                    : message
                                    )
                            );

                            slot.button.setText(
                                    "⬇ Preparando..."
                            );

                            pollHandler.postDelayed(
                                    checker[0],
                                    1500
                            );

                        } else if ("done".equals(state)) {

                            Log.d(
                                    "MUSIC_DOWNLOAD_DEBUG",
                                    "POLL: ESTADO DONE -> saveMobileDownload()"
                            );

                            slot.progress.setProgress(
                                    99
                            );

                            slot.status.setText(
                                    "✓ DESCARGADA"
                            );

                            slot.button.setText(
                                    "✓ Descargada"
                            );

                            updateMobileDownloadMonitor(
                                    job,
                                    99,
                                    "✓ DESCARGADA"
                            );

                            saveMobileDownload(
                                    job,
                                    slot,
                                    data,
                                    wakeLock
                            );

                            Log.d(
                                    "MUSIC_DOWNLOAD_DEBUG",
                                    "POLL: saveMobileDownload() LLAMADO"
                            );

                        } else if ("cancelled".equals(state)) {

                            Log.d(
                                    "MUSIC_DOWNLOAD_DEBUG",
                                    "POLL: ESTADO CANCELLED: "
                                            + message
                            );

                            String cancelledStatus =
                                    "✕ "
                                            + (
                                            message.isEmpty()
                                                    ? "Descarga cancelada."
                                                    : message
                                    );

                            slot.status.setText(
                                    cancelledStatus
                            );

                            updateMobileDownloadMonitor(
                                    job,
                                    progress,
                                    cancelledStatus
                            );

                            slot.button.setText(
                                    "↻ Reintentar"
                            );

                            slot.button.setEnabled(
                                    true
                            );

                            if (wakeLock.isHeld()) {
                                wakeLock.release();
                            }

                            Log.d(
                                    "MUSIC_DOWNLOAD_DEBUG",
                                    "WAKELOCK LIBERADO: descarga cancelada job="
                                            + job
                            );

                        } else if ("error".equals(state)) {

                            Log.d(
                                    "MUSIC_DOWNLOAD_DEBUG",
                                    "POLL: ESTADO ERROR: "
                                            + message
                            );

                            String errorStatus =
                                    "❌ "
                                            + (
                                            message.isEmpty()
                                                    ? "Error durante la descarga offline"
                                                    : message
                                    );

                            slot.status.setText(
                                    errorStatus
                            );

                            updateMobileDownloadMonitor(
                                    job,
                                    progress,
                                    errorStatus
                            );

                            slot.button.setText(
                                    "↻ Reintentar"
                            );

                            slot.button.setEnabled(
                                    true
                            );

                            if (wakeLock.isHeld()) {
                                wakeLock.release();
                            }

                            Log.d(
                                    "MUSIC_DOWNLOAD_DEBUG",
                                    "WAKELOCK LIBERADO: job=" + job
                            );

                        } else {

                            Log.d(
                                    "MUSIC_DOWNLOAD_DEBUG",
                                    "POLL: ESTADO DESCONOCIDO: "
                                            + state
                            );

                            pollHandler.postDelayed(
                                    checker[0],
                                    1500
                            );
                        }
                    });

                } catch (Exception e) {

                    Log.e(
                            "MUSIC_DOWNLOAD_DEBUG",
                            "POLL ERROR consultando job="
                                    + job
                                    + " - reintentando...",
                            e
                    );

                    handler.post(() -> {

                        slot.status.setText(
                                "🔄 Reintentando consultar descarga..."
                        );

                        slot.button.setText(
                                "⬇ Preparando..."
                        );

                        slot.button.setEnabled(
                                false
                        );

                        pollHandler.postDelayed(
                                checker[0],
                                2000
                        );
                    });
                }
            });
        };

        pollHandler.post(
                checker[0]
        );
    }


    private void saveMobileDownload(
            String job,
            DownloadSlot slot,
            JSONObject data,
            PowerManager.WakeLock wakeLock) {

        final long saveStartTime =
                System.currentTimeMillis();

        Log.d(
                "MUSIC_DOWNLOAD_DEBUG",
                "TIMING 1: saveMobileDownload() llamado"
                        + " job=" + job
        );

        executor.execute(() -> {

            Log.d(
                    "MUSIC_DOWNLOAD_DEBUG",
                    "TIMING 2: executor iniciado"
                            + " job=" + job
                            + " +"
                            + (System.currentTimeMillis()
                                    - saveStartTime)
                            + " ms"
            );

            HttpURLConnection connection = null;

            try {

                if (offlineFolderUri == null) {
                    throw new Exception(
                            "No hay carpeta offline seleccionada."
                    );
                }

                Log.d(
                        "MUSIC_DOWNLOAD_DEBUG",
                        "TIMING 3: obteniendo DocumentFile"
                                + " job=" + job
                                + " +"
                                + (System.currentTimeMillis()
                                        - saveStartTime)
                                + " ms"
                );

                DocumentFile folder =
                        DocumentFile.fromTreeUri(
                                this,
                                offlineFolderUri
                        );

                Log.d(
                        "MUSIC_DOWNLOAD_DEBUG",
                        "TIMING 4: DocumentFile obtenido"
                                + " job=" + job
                                + " +"
                                + (System.currentTimeMillis()
                                        - saveStartTime)
                                + " ms"
                );

                if (folder == null
                        || !folder.isDirectory()
                        || !folder.canWrite()) {

                    throw new Exception(
                            "La carpeta offline no está disponible."
                    );
                }

                String format =
                        data.optString(
                                "format",
                                ""
                        );

                String filename =
                        data.optString(
                                "filename",
                                ""
                        );

                if (
                        !"opus".equalsIgnoreCase(format) &&
                        !"mp3".equalsIgnoreCase(format)
                ) {

                    if (
                            filename
                                    .toLowerCase()
                                    .endsWith(".opus")
                    ) {
                        format = "opus";
                    } else {
                        format = "mp3";
                    }

                } else {

                    format =
                            "opus".equalsIgnoreCase(format)
                                    ? "opus"
                                    : "mp3";
                }

                final String extension =
                        "opus".equals(format)
                                ? ".opus"
                                : ".mp3";

                final String mimeType =
                        "opus".equals(format)
                                ? "audio/ogg"
                                : "audio/mpeg";

                if (filename.isEmpty()) {
                    filename = slot.title + extension;
                }

                filename =
                        filename
                                .replace(
                                        "/",
                                        "_"
                                )
                                .replace(
                                        "\\",
                                        "_"
                                )
                                .trim();

                String lowerFilename =
                        filename.toLowerCase();

                if (
                        lowerFilename.endsWith(".mp3") ||
                        lowerFilename.endsWith(".opus")
                ) {

                    int dot =
                            filename.lastIndexOf(".");

                    if (dot >= 0) {

                        filename =
                                filename.substring(
                                        0,
                                        dot
                                )
                                + extension;
                    }

                } else {

                    filename += extension;
                }

                String finalFilename = filename;

                DocumentFile target =
                        folder.findFile(
                                finalFilename
                        );

                if (target != null) {

                    String base =
                            finalFilename.substring(
                                    0,
                                    finalFilename.length()
                                            - extension.length()
                            );

                    int counter = 2;

                    do {

                        finalFilename =
                                base
                                        + " ("
                                        + counter
                                        + ")"
                                        + extension;

                        target =
                                folder.findFile(
                                        finalFilename
                                );

                        counter++;

                    } while (target != null);
                }

                Log.d(
                        "MUSIC_DOWNLOAD_DEBUG",
                        "TIMING 5: creando archivo offline"
                                + " job=" + job
                                + " +"
                                + (System.currentTimeMillis()
                                        - saveStartTime)
                                + " ms"
                );

                Log.d(
                        "MUSIC_DOWNLOAD_DEBUG",
                        "FORMATO OFFLINE: "
                                + format
                                + " mime="
                                + mimeType
                                + " filename="
                                + finalFilename
                );

                target =
                        folder.createFile(
                                mimeType,
                                finalFilename
                        );

                Log.d(
                        "MUSIC_DOWNLOAD_DEBUG",
                        "TIMING 6: archivo offline creado"
                                + " job=" + job
                                + " +"
                                + (System.currentTimeMillis()
                                        - saveStartTime)
                                + " ms"
                );

                if (target == null) {
                    throw new Exception(
                            "No se pudo crear el archivo en la carpeta offline."
                    );
                }

                Log.d(
                        "MUSIC_DOWNLOAD_DEBUG",
                        "TIMING 7: preparando conexión HTTP"
                                + " job=" + job
                                + " +"
                                + (System.currentTimeMillis()
                                        - saveStartTime)
                                + " ms"
                );

                URL url =
                        new URL(
                                api().getMobileDownloadUrl(
                                        job
                                )
                        );

                connection =
                        (HttpURLConnection)
                                url.openConnection();

                connection.setRequestMethod(
                        "GET"
                );

                connection.setConnectTimeout(
                        15000
                );

                connection.setReadTimeout(
                        120000
                );

                connection.setRequestProperty(
                        "Accept",
                        mimeType
                );

                connection.setRequestProperty(
                        "Accept-Encoding",
                        "identity"
                );

                connection.setRequestProperty(
                        "Connection",
                        "close"
                );

                connection.setUseCaches(false);

                Log.d(
                        "MUSIC_DOWNLOAD_DEBUG",
                        "TRANSFER: iniciando descarga HTTP para job=" + job
                );

                Log.d(
                        "MUSIC_DOWNLOAD_DEBUG",
                        "TRANSFER: URL=" + url
                );

                int responseCode =
                        connection.getResponseCode();

                Log.d(
                        "MUSIC_DOWNLOAD_DEBUG",
                        "TIMING 8: respuesta HTTP recibida"
                                + " job=" + job
                                + " +"
                                + (System.currentTimeMillis()
                                        - saveStartTime)
                                + " ms"
                );

                Log.d(
                        "MUSIC_DOWNLOAD_DEBUG",
                        "TRANSFER: HTTP response=" + responseCode
                                + " job=" + job
                );

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new Exception(
                            "El servidor respondió HTTP "
                                    + responseCode
                    );
                }

                long totalBytes =
                        connection.getContentLengthLong();

                Log.d(
                        "MUSIC_DOWNLOAD_DEBUG",
                        "TRANSFER: tamaño esperado="
                                + totalBytes
                                + " bytes ("
                                + (
                                totalBytes > 0
                                        ? (totalBytes / 1024 / 1024)
                                                + " MB"
                                        : "desconocido"
                        )
                        + ") job=" + job
                );

                long downloadedBytes = 0;
                long lastLoggedBytes = 0;
                final long LOG_INTERVAL =
                        1024L * 1024L;

                try (
                        InputStream input =
                                connection.getInputStream();

                        OutputStream output =
                                getContentResolver()
                                        .openOutputStream(
                                                target.getUri()
                                        )
                ) {

                    if (output == null) {
                        throw new Exception(
                                "No se pudo abrir el archivo offline."
                        );
                    }

                    Log.d(
                            "MUSIC_DOWNLOAD_DEBUG",
                            "TIMING 9: transferencia realmente iniciada"
                                    + " job=" + job
                                    + " +"
                                    + (System.currentTimeMillis()
                                            - saveStartTime)
                                    + " ms"
                    );

                    Log.d(
                            "MUSIC_DOWNLOAD_DEBUG",
                            "TRANSFER: InputStream y OutputStream abiertos"
                                    + " job=" + job
                    );

                    byte[] buffer =
                            new byte[256 * 1024];

                    int count;

                    while (
                            (count =
                                    input.read(buffer))
                                    != -1
                    ) {

                        if (count == 0) {
                            continue;
                        }

                        output.write(
                                buffer,
                                0,
                                count
                        );

                        downloadedBytes += count;

                        if (downloadedBytes - lastLoggedBytes
                                >= LOG_INTERVAL) {

                            lastLoggedBytes =
                                    downloadedBytes;

                            Log.d(
                                    "MUSIC_DOWNLOAD_DEBUG",
                                    "TRANSFER: "
                                            + downloadedBytes
                                            + " / "
                                            + totalBytes
                                            + " bytes ("
                                            + (
                                            totalBytes > 0
                                                    ? (
                                                    downloadedBytes * 100L
                                                            / totalBytes
                                                    )
                                                    : -1
                                            )
                                            + "%) job=" + job
                            );
                        }

                        if (totalBytes > 0) {

                            int transferProgress =
                                    (int)
                                            (
                                                    downloadedBytes
                                                            * 100L
                                                            / totalBytes
                                            );

                            final int currentProgress =
                                    Math.min(
                                            100,
                                            transferProgress
                                    );

                            handler.post(() ->
                                    slot.progress.setProgress(
                                            currentProgress
                                    )
                            );
                        }
                    }

                    output.flush();

                    Log.d(
                            "MUSIC_DOWNLOAD_DEBUG",
                            "TRANSFER: output.flush() completado"
                                    + " bytes=" + downloadedBytes
                                    + " job=" + job
                    );
                }

                Log.d(
                        "MUSIC_DOWNLOAD_DEBUG",
                        "TRANSFER: streams cerrados"
                                + " bytes=" + downloadedBytes
                                + " job=" + job
                );

                if (totalBytes > 0
                        && downloadedBytes != totalBytes) {

                    throw new Exception(
                            "Transferencia incompleta: "
                                    + downloadedBytes
                                    + " de "
                                    + totalBytes
                                    + " bytes."
                    );
                }

                Log.d(
                        "MUSIC_DOWNLOAD_DEBUG",
                        "TRANSFER: COMPLETADA correctamente"
                                + " bytes=" + downloadedBytes
                                + " job=" + job
                );

                final long finalSize =
                        downloadedBytes;

                final String savedUri =
                        target.getUri().toString();

                final String savedFilename =
                        finalFilename;

                addOfflineTrack(
                        savedUri,
                        savedFilename,
                        finalSize,
                        slot.videoId,
                        slot.title,
                        getOfflineArtist(slot),
                        getOfflineAlbum(slot),
                        getOfflineDuration(slot),
                        getOfflineThumbnail(slot)
                );

                handler.post(() -> {

                    slot.progress.setProgress(
                            100
                    );

                    slot.status.setText(
                            "📱 ENVIADA AL MÓVIL"
                    );

                    slot.button.setText(
                            "✓ Offline"
                    );

                    slot.button.setEnabled(
                            false
                    );

                    finishMobileDownloadMonitor(
                            job,
                            "📱 ENVIADA AL MÓVIL"
                    );

                    Toast.makeText(
                            this,
                            "📱 Canción enviada al móvil",
                            Toast.LENGTH_SHORT
                    ).show();
                });

            } catch (Exception e) {

                Log.e(
                        "MUSIC_DOWNLOAD_DEBUG",
                        "TRANSFER ERROR: job=" + job
                                + " tipo="
                                + e.getClass().getName()
                                + " mensaje="
                                + e.getMessage(),
                        e
                );

                handler.post(() -> {

                    String saveErrorStatus =
                            "❌ "
                                    + (
                                    e.getMessage() == null
                                            ? "Error guardando la canción"
                                            : e.getMessage()
                            );

                    slot.status.setText(
                            saveErrorStatus
                    );

                    updateMobileDownloadMonitor(
                            job,
                            slot.progress.getProgress(),
                            saveErrorStatus
                    );

                    slot.button.setText(
                            "↻ Reintentar"
                    );

                    slot.button.setEnabled(
                            true
                    );
                });

            } finally {

                if (connection != null) {
                    connection.disconnect();
                }

                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();

                    Log.d(
                            "MUSIC_DOWNLOAD_DEBUG",
                            "WAKELOCK LIBERADO: transferencia finalizada job=" + job
                    );
                }
            }
        });
    }

    private String getOfflineFormat() {

        String format =
                getSharedPreferences(
                        "settings",
                        MODE_PRIVATE
                ).getString(
                        "offline_format",
                        "mp3"
                );

        if ("opus".equalsIgnoreCase(format)) {
            return "opus";
        }

        return "mp3";
    }

    private void saveOfflineFormat(
            String format) {

        if ("opus".equalsIgnoreCase(format)) {
            format = "opus";
        } else {
            format = "mp3";
        }

        getSharedPreferences(
                "settings",
                MODE_PRIVATE
        ).edit()
                .putString(
                        "offline_format",
                        format
                )
                .apply();
    }

    private void startMobileDownload(
            DownloadSlot slot) {

        Log.d(
                "MUSIC_DOWNLOAD_DEBUG",
                "ENTRÓ EN startMobileDownload: " + slot.title
        );

        if (offlineFolderUri == null) {

            Log.d(
                    "MUSIC_DOWNLOAD_DEBUG",
                    "OFFLINE FALLA: offlineFolderUri == null"
            );

            Toast.makeText(
                    this,
                    "📂 Selecciona primero la carpeta offline",
                    Toast.LENGTH_LONG
            ).show();

            chooseOfflineFolder();
            return;
        }

        Log.d(
                "MUSIC_DOWNLOAD_DEBUG",
                "offlineFolderUri OK: " + offlineFolderUri
        );

        Log.d(
                "MUSIC_DOWNLOAD_DEBUG",
                "videoId=" + slot.videoId
                        + " title=" + slot.title
        );

        slot.button.setEnabled(false);

        slot.button.setText(
                "⏳ Preparando..."
        );

        slot.status.setText(
                "📱 Preparando descarga offline..."
        );

        slot.progress.setProgress(0);

        final String offlineFormat =
                getOfflineFormat();


        executor.execute(() -> {

            try {

                Log.d(
                        "MUSIC_DOWNLOAD_DEBUG",
                        "ANTES DE api().downloadMobile: id="
                                + slot.videoId
                                + " title="
                                + slot.title
                );

                JSONObject result;

                if (
                        slot.albumGroup != null &&
                        !slot.albumGroup.isEmpty()
                ) {

                    result =
                            api().downloadMobile(
                                slot.videoId,
                                slot.title,
                                slot.albumGroup,
                                slot.albumTitle,
                                slot.albumTrackIndex,
                                slot.albumTrackTotal,
                                offlineFormat
                        );

                } else {

                    result =
                            api().downloadMobile(
                                slot.videoId,
                                slot.title,
                                null,
                                null,
                                0,
                                0,
                                offlineFormat
                        );
                }

                Log.d(
                        "MUSIC_DOWNLOAD_DEBUG",
                        "RESPUESTA api().downloadMobile: "
                                + result.toString()
                );

                String job =
                        result.optString(
                                "job",
                                ""
                        );

                Log.d(
                        "MUSIC_DOWNLOAD_DEBUG",
                        "JOB OFFLINE: " + job
                );

                if (job.isEmpty()) {

                    throw new Exception(
                            "El servidor no devolvió el trabajo offline."
                    );
                }

                registerMobileDownload(
                        job,
                        slot
                );

                handler.post(() -> {

                    slot.button.setText(
                            "⬇ Descargando..."
                    );

                    slot.status.setText(
                            "🕐 Preparando canción para el móvil..."
                    );

                    watchMobileDownloadJob(
                            job,
                            slot
                    );
                });

            } catch (Exception e) {

                Log.e(
                        "MUSIC_DOWNLOAD_DEBUG",
                        "ERROR EN startMobileDownload",
                        e
                );

                handler.post(() -> {

                    slot.button.setEnabled(true);

                    slot.button.setText(
                            "📱 Offline"
                    );

                    slot.status.setText(
                            "❌ " + e.getMessage()
                    );
                });
            }
        });
    }

    private void startMobileDownloadAlbumTrack(
            ApiClient.Song track,
            DownloadSlot slot) {

        startMobileDownloadAlbumTrack(
                track,
                slot,
                null,
                null,
                0,
                0
        );
    }

    private void startMobileDownloadAlbumTrack(
            ApiClient.Song track,
            DownloadSlot slot,
            String albumGroup,
            String albumTitle,
            int albumTrackIndex,
            int albumTrackTotal) {

        if (offlineFolderUri == null) {

            Toast.makeText(
                    this,
                    "📂 Selecciona primero la carpeta offline",
                    Toast.LENGTH_LONG
            ).show();

            chooseOfflineFolder();
            return;
        }

        slot.button.setEnabled(false);
        slot.button.setText("⏳ Buscando...");
        slot.status.setText("📱 Buscando audio para guardar offline...");
        slot.progress.setProgress(0);

        executor.execute(() -> {

            try {

                ApiClient.Song resolved =
                        api().resolveAlbumTrack(
                                track.artist,
                                track.title
                        );

                DownloadSlot resolvedSlot =
                        new DownloadSlot(
                                resolved.id,
                                resolved.title,
                                slot.button,
                                slot.progress,
                                slot.status,
                                albumGroup,
                                albumTitle,
                                albumTrackIndex,
                                albumTrackTotal
                        );

                handler.post(
                        () -> startMobileDownload(
                                resolvedSlot
                        )
                );

            } catch (Exception e) {

                handler.post(() -> {

                    slot.button.setEnabled(true);
                    slot.button.setText("📱 Offline");
                    slot.status.setText(
                            "❌ " + e.getMessage()
                    );
                });
            }
        });
    }

    private void startMobileDownloadSpotifyTrack(
            ApiClient.SpotifyTrack track,
            DownloadSlot slot) {

        if (offlineFolderUri == null) {

            Toast.makeText(
                    this,
                    "📂 Selecciona primero la carpeta offline",
                    Toast.LENGTH_LONG
            ).show();

            chooseOfflineFolder();
            return;
        }

        slot.button.setEnabled(false);
        slot.button.setText("⏳ Buscando...");
        slot.status.setText("📱 Buscando audio para guardar offline...");
        slot.progress.setProgress(0);

        executor.execute(() -> {

            try {

                ApiClient.Song resolved =
                        api().resolveAlbumTrack(
                                track.artist,
                                track.title
                        );

                DownloadSlot resolvedSlot =
                        new DownloadSlot(
                                resolved.id,
                                track.artist
                                        + " - "
                                        + track.title,
                                slot.button,
                                slot.progress,
                                slot.status
                        );

                handler.post(
                        () -> startMobileDownload(
                                resolvedSlot
                        )
                );

            } catch (Exception e) {

                handler.post(() -> {

                    slot.button.setEnabled(true);
                    slot.button.setText("📱 Offline");
                    slot.status.setText(
                            "❌ " + e.getMessage()
                    );
                });
            }
        });
    }

    private void startNormalDownload(
            DownloadSlot slot) {

        Log.d(
                "MUSIC_DOWNLOAD_DEBUG",
                "ENTRÓ EN startNormalDownload: " + slot.title
        );

        slot.button.setEnabled(false);

        slot.button.setText(
                "⏳ Enviando..."
        );

        slot.status.setText(
                "🕐 Enviando a descarga..."
        );

        slot.progress.setProgress(0);

        executor.execute(() -> {

            try {

                Log.d(
                        "MUSIC_DOWNLOAD_DEBUG",
                        "ANTES DE api().download: id="
                                + slot.videoId
                                + " title="
                                + slot.title
                );

                JSONObject result =
                        api().download(
                                slot.videoId,
                                slot.title
                        );

                Log.d(
                        "MUSIC_DOWNLOAD_DEBUG",
                        "RESPUESTA api().download: "
                                + result.toString()
                );

                String job =
                        result.optString(
                                "job",
                                ""
                        );

                if (job.isEmpty()) {

                    throw new Exception(
                            "El servidor no devolvió el trabajo de descarga."
                    );
                }

                handler.post(() -> {

                    slot.button.setText(
                            "⬇ Descargando..."
                    );

                    slot.status.setText(
                            "🕐 Procesando cola..."
                    );

                    watchDownloadJob(
                            job,
                            slot
                    );
                });

            } catch (Exception e) {

                handler.post(() -> {

                    slot.status.setText(
                            "❌ " + e.getMessage()
                    );

                    slot.button.setText(
                            "↻ Reintentar"
                    );

                    slot.button.setEnabled(true);
                });
            }
        });
    }

    private void downloadAlbumTrack(
            DownloadSlot slot) {

        slot.button.setEnabled(false);

        slot.button.setText(
                "⏳ Buscando..."
        );

        slot.status.setText(
                "🔎 Buscando audio..."
        );

        slot.progress.setProgress(0);

        executor.execute(() -> {

            try {

                ApiClient.Song resolved =
                        api().resolveAlbumTrack(
                                slot.albumTrack.artist,
                                slot.albumTrack.title
                        );

                String videoId =
                        resolved.id;

                if (
                        videoId == null ||
                        videoId.isEmpty()
                ) {

                    throw new Exception(
                            "No se encontró el audio de la canción."
                    );
                }

                JSONObject result =
                        api().download(
                                videoId,
                                slot.title
                        );

                String job =
                        result.optString(
                                "job",
                                ""
                        );

                if (job.isEmpty()) {

                    throw new Exception(
                            "El servidor no devolvió el trabajo de descarga."
                    );
                }

                handler.post(() -> {

                    slot.button.setText(
                            "⬇ Descargando..."
                    );

                    slot.status.setText(
                            "🕐 Procesando cola..."
                    );

                    watchDownloadJob(
                            job,
                            slot
                    );
                });

            } catch (Exception e) {

                handler.post(() -> {

                    slot.status.setText(
                            "❌ " + e.getMessage()
                    );

                    slot.button.setText(
                            "↻ Reintentar"
                    );

                    slot.button.setEnabled(true);
                });
            }
        });
    }

    private void downloadAlbumOffline(
            List<DownloadSlot> slots) {

        if (slots == null || slots.isEmpty()) {

            Toast.makeText(
                    this,
                    "⚠️ Este álbum no tiene canciones",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        if (offlineFolderUri == null) {

            Toast.makeText(
                    this,
                    "📂 Selecciona primero la carpeta offline",
                    Toast.LENGTH_LONG
            ).show();

            chooseOfflineFolder();
            return;
        }

        Toast.makeText(
                this,
                "📱 Guardando " + slots.size()
                        + " canciones offline...",
                Toast.LENGTH_LONG
        ).show();

        final String albumGroup =
                java.util.UUID.randomUUID().toString();

        String detectedAlbumTitle = "Álbum";

        if (
                slots.get(0).albumTrack != null &&
                slots.get(0).albumTrack.album != null &&
                !slots.get(0).albumTrack.album.isEmpty()
        ) {
            detectedAlbumTitle =
                    slots.get(0).albumTrack.album;
        }

        final String albumTitle =
                detectedAlbumTitle;

        final int albumTrackTotal =
                slots.size();

        executor.execute(() -> {

            for (int i = 0; i < slots.size(); i++) {

                DownloadSlot slot =
                        slots.get(i);

                final int albumTrackIndex =
                        i + 1;

                handler.post(() -> {

                    slot.button.setEnabled(false);

                    slot.button.setText(
                            "⏳ Preparando..."
                    );

                    slot.status.setText(
                            "📱 Preparando descarga offline..."
                    );

                    slot.progress.setProgress(0);
                });

                try {

                    ApiClient.Song track =
                            slot.albumTrack;

                    startMobileDownloadAlbumTrack(
                            track,
                            slot,
                            albumGroup,
                            albumTitle,
                            albumTrackIndex,
                            albumTrackTotal
                    );

                    Thread.sleep(300);

                } catch (Exception e) {

                    handler.post(() -> {

                        slot.button.setEnabled(true);

                        slot.button.setText(
                                "📱 Offline"
                        );

                        slot.status.setText(
                                "❌ " + e.getMessage()
                        );
                    });
                }
            }
        });
    }

    private void downloadAlbum(
            ApiClient.Album album,
            List<DownloadSlot> slots) {

        Toast.makeText(
                this,
                "Preparando descarga del álbum...",
                Toast.LENGTH_LONG
        ).show();

        if (album == null || slots == null || slots.isEmpty()) {
            return;
        }

        final String albumGroup =
                java.util.UUID.randomUUID().toString();

        final String albumTitle =
                album.title == null ||
                album.title.isEmpty()
                        ? "Álbum"
                        : album.title;

        final int albumTrackTotal =
                slots.size();

        executor.execute(() -> {

            for (int i = 0; i < slots.size(); i++) {

                final DownloadSlot slot =
                        slots.get(i);

                final int albumTrackIndex =
                        i + 1;

                handler.post(() -> {

                    slot.button.setEnabled(false);

                    slot.button.setText(
                            "⏳ Buscando..."
                    );

                    slot.status.setText(
                            "🔎 Buscando audio..."
                    );

                    slot.progress.setProgress(0);
                });

                try {

                    ApiClient.Song resolved =
                            api().resolveAlbumTrack(
                                    slot.albumTrack.artist,
                                    slot.albumTrack.title
                            );

                    String videoId =
                            resolved.id;

                    if (
                            videoId == null ||
                            videoId.isEmpty()
                    ) {

                        throw new Exception(
                                "No se encontró el audio."
                        );
                    }

                    JSONObject result =
                            api().download(
                                    videoId,
                                    slot.title,
                                    albumGroup,
                                    albumTitle,
                                    albumTrackIndex,
                                    albumTrackTotal
                            );

                    String job =
                            result.optString(
                                    "job",
                                    ""
                            );

                    if (job.isEmpty()) {

                        throw new Exception(
                                "El servidor no devolvió el trabajo."
                        );
                    }

                    handler.post(() -> {

                        slot.button.setText(
                                "⬇ Descargando..."
                        );

                        slot.status.setText(
                                "🕐 Procesando cola..."
                        );

                        watchDownloadJob(
                                job,
                                slot
                        );
                    });

                    Thread.sleep(300);

                } catch (Exception e) {

                    handler.post(() -> {

                        slot.status.setText(
                                "❌ " + e.getMessage()
                        );

                        slot.button.setText(
                                "↻ Reintentar"
                        );

                        slot.button.setEnabled(true);
                    });
                }
            }
        });
    }

    private void downloadSpotifyTrack(
            DownloadSlot slot) {

        slot.button.setEnabled(false);

        slot.button.setText(
                "⏳ Enviando..."
        );

        slot.status.setText(
                "🕐 Enviando a descarga..."
        );

        slot.progress.setProgress(0);

        executor.execute(() -> {

            try {

                JSONObject result =
                        api().spotifyDownload(
                                slot.track
                        );

                JSONArray queued =
                        result.optJSONArray(
                                "queued"
                        );

                if (
                        queued == null ||
                        queued.length() == 0
                ) {

                    throw new Exception(
                            "El servidor no devolvió el trabajo de descarga."
                    );
                }

                JSONObject item =
                        queued.getJSONObject(0);

                String job =
                        item.optString(
                                "job",
                                ""
                        );

                if (job.isEmpty()) {

                    throw new Exception(
                            "El servidor no devolvió el trabajo de descarga."
                    );
                }

                handler.post(() -> {

                    slot.button.setText(
                            "⬇ Descargando..."
                    );

                    slot.status.setText(
                            "🕐 Procesando cola..."
                    );

                    watchDownloadJob(
                            job,
                            slot
                    );
                });

            } catch (Exception e) {

                handler.post(() -> {

                    slot.status.setText(
                            "❌ " + e.getMessage()
                    );

                    slot.button.setText(
                            "↻ Reintentar"
                    );

                    slot.button.setEnabled(true);
                });
            }
        });
    }

    private void downloadSpotifyPlaylist(
            ApiClient.SpotifyPlaylist playlist,
            List<DownloadSlot> slots) {

        Toast.makeText(
                this,
                "Enviando playlist a descarga...",
                Toast.LENGTH_LONG
        ).show();

        for (DownloadSlot slot : slots) {

            slot.button.setEnabled(false);

            slot.button.setText(
                    "⏳ En cola..."
            );

            slot.status.setText(
                    "🕐 Esperando trabajo..."
            );

            slot.progress.setProgress(0);
        }

        executor.execute(() -> {

            try {

                JSONObject result =
                        api().spotifyDownloadPlaylist(
                                playlist
                        );

                JSONArray queued =
                        result.optJSONArray(
                                "queued"
                        );

                if (
                        queued == null ||
                        queued.length() == 0
                ) {

                    throw new Exception(
                            "El servidor no devolvió trabajos de descarga."
                    );
                }

                handler.post(() -> {

                    for (
                            int i = 0;
                            i < queued.length();
                            i++
                    ) {

                        try {

                            JSONObject item =
                                    queued.getJSONObject(i);

                            int position =
                                    item.optInt(
                                            "position",
                                            0
                                    );

                            String job =
                                    item.optString(
                                            "job",
                                            ""
                                    );

                            if (
                                    position < 1 ||
                                    position > slots.size() ||
                                    job.isEmpty()
                            ) {
                                continue;
                            }

                            DownloadSlot slot =
                                    slots.get(
                                            position - 1
                                    );

                            slot.button.setText(
                                    "⬇ Descargando..."
                            );

                            slot.status.setText(
                                    "🕐 Procesando cola..."
                            );

                            watchDownloadJob(
                                    job,
                                    slot
                            );

                        } catch (Exception ignored) {
                        }
                    }

                    Toast.makeText(
                            this,
                            queued.length()
                                    + " canciones enviadas a descarga",
                            Toast.LENGTH_SHORT
                    ).show();
                });

            } catch (Exception e) {

                handler.post(() -> {

                    for (DownloadSlot slot : slots) {

                        slot.status.setText(
                                "❌ " + e.getMessage()
                        );

                        slot.button.setText(
                                "↻ Reintentar"
                        );

                        slot.button.setEnabled(true);
                    }

                    showError(e);
                });
            }
        });
    }

    private void deleteSpotifyPlaylist(
            ApiClient.SpotifyPlaylist playlist) {

        executor.execute(() -> {

            try {

                api().deleteSpotifyList(
                        playlist.id
                );

                handler.post(() -> {

                    Toast.makeText(
                            this,
                            "Lista borrada",
                            Toast.LENGTH_SHORT
                    ).show();

                    loadSpotifyLists();
                });

            } catch (Exception e) {

                handler.post(
                        () -> showError(e)
                );
            }
        });
    }

    /*
     * ============================================================
     * REPRODUCTOR
     * ============================================================
     */

    private void togglePlayback() {

        if (mediaController == null) {
            return;
        }

        if (mediaController.isPlaying()) {

            mediaController.pause();

        } else {

            mediaController.play();
        }
    }

    /*
     * =========================================================
     * CONTADOR DE PREPARACIÓN DE COLA
     * =========================================================
     */

    private long beginQueuePreparation(int total) {

        final long generation =
                ++queuePreparationGeneration;

        handler.post(() -> {

            if (generation != queuePreparationGeneration) {
                return;
            }

            if (playerQueueProgress != null) {

                playerQueueProgress.setText(
                        "0/" + Math.max(0, total)
                );

                playerQueueProgress.setVisibility(
                        android.view.View.VISIBLE
                );
            }
        });

        return generation;
    }

    private boolean isCurrentQueuePreparation(
            long generation) {

        return generation ==
                queuePreparationGeneration;
    }

    /*
     * ============================================================
     * ESTADO VISUAL DE PISTAS OBTENIDAS
     * ============================================================
     */

    private TextView createObtainedStatusView() {

        TextView status =
                new TextView(this);

        status.setText("✓ OBTENIDA");
        status.setTextSize(11);
        status.setTypeface(
                null,
                android.graphics.Typeface.BOLD
        );
        status.setTextColor(
                Color.rgb(80, 220, 130)
        );
        status.setVisibility(
                android.view.View.GONE
        );

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        params.setMargins(
                0,
                dp(3),
                0,
                0
        );

        status.setLayoutParams(params);

        return status;
    }

    private void markAlbumTrackObtained(
            long generation,
            int trackIndex) {

        handler.post(() -> {

            if (!isCurrentQueuePreparation(generation)) {
                return;
            }

            if (
                    trackIndex < 0 ||
                    trackIndex >=
                            albumPreparationStatusViews.size()
            ) {
                return;
            }

            TextView status =
                    albumPreparationStatusViews.get(
                            trackIndex
                    );

            if (status != null) {
                status.setText("✓ OBTENIDA");
                status.setVisibility(
                        android.view.View.VISIBLE
                );
            }
        });
    }

    private void markSpotifyTrackObtained(
            long generation,
            int trackIndex) {

        handler.post(() -> {

            if (!isCurrentQueuePreparation(generation)) {
                return;
            }

            if (
                    trackIndex < 0 ||
                    trackIndex >=
                            spotifyPreparationStatusViews.size()
            ) {
                return;
            }

            TextView status =
                    spotifyPreparationStatusViews.get(
                            trackIndex
                    );

            if (status != null) {
                status.setText("✓ OBTENIDA");
                status.setVisibility(
                        android.view.View.VISIBLE
                );
            }
        });
    }

    private void updateQueuePreparationProgress(
            long generation,
            int loaded,
            int total) {

        handler.post(() -> {

            if (!isCurrentQueuePreparation(
                    generation)) {
                return;
            }

            if (playerQueueProgress != null) {

                int safeLoaded =
                        Math.max(0, loaded);

                int safeTotal =
                        Math.max(0, total);

                if (
                        safeTotal > 0 &&
                        safeLoaded >= safeTotal
                ) {

                    playerQueueProgress.setText(
                            safeLoaded
                                    + "/"
                                    + safeTotal
                                    + " resultados · ¡COMPLETADO!"
                    );

                    playerQueueProgress.setTextColor(
                            Color.rgb(80, 220, 130)
                    );

                } else {

                    playerQueueProgress.setText(
                            safeLoaded
                                    + "/"
                                    + safeTotal
                                    + " resultados"
                    );

                    playerQueueProgress.setTextColor(
                            Color.LTGRAY
                    );
                }

                playerQueueProgress.setVisibility(
                        android.view.View.VISIBLE
                );
            }
        });
    }

    private void hideQueuePreparationProgress() {

        ++queuePreparationGeneration;

        if (playerQueueProgress != null) {

            playerQueueProgress.setVisibility(
                    android.view.View.GONE
            );
        }
    }

    private void updatePlayerUi() {

        if (mediaController == null) {
            return;
        }

        MediaItem item =
                mediaController.getCurrentMediaItem();

        if (item != null) {

            /*
             * Mostrar el mini-player solamente cuando no haya sido
             * cerrado ni minimizado manualmente por el usuario.
             */
            if (!playerManuallyClosed &&
                    !playerMinimized) {

                player.setVisibility(
                        android.view.View.VISIBLE
                );

                if (restorePlayerButton != null) {
                    restorePlayerButton.setVisibility(
                            android.view.View.GONE
                    );
                }

            } else if (playerMinimized) {

                player.setVisibility(
                        android.view.View.GONE
                );

                if (restorePlayerButton != null) {
                    restorePlayerButton.setVisibility(
                            android.view.View.VISIBLE
                    );
                }
            }

            CharSequence title =
                    item.mediaMetadata.title;

            if (title != null) {

                playerTitle.setText(
                        title
                );
            }

            CharSequence artist =
                    item.mediaMetadata.artist;

            if (artist != null &&
                    !artist.toString().trim().isEmpty()) {

                playerArtist.setText(
                        artist
                );

            } else {

                playerArtist.setText(
                        "Artista desconocido"
                );
            }

            Uri artworkUri =
                    item.mediaMetadata.artworkUri;

            Log.d(
                    "PLAYER_ARTWORK",
                    "artworkUri=" +
                            (artworkUri == null
                                    ? "NULL"
                                    : artworkUri.toString())
            );

            String artwork =
                    artworkUri == null
                            ? ""
                            : artworkUri.toString();

            if (!artwork.equals(playerArtworkLoaded)) {

                playerArtworkLoaded = artwork;

                if (!artwork.isEmpty()) {

                    loadThumbnail(
                            artwork,
                            playerArtwork
                    );

                } else {

                    playerArtwork.setImageResource(
                            android.R.drawable.ic_media_play
                    );
                }
            }
        }

        long duration =
                mediaController.getDuration();

        long position =
                mediaController.getCurrentPosition();

        if (duration > 0) {

            playerSeekBar.setProgress(
                    (int) Math.max(
                            0,
                            Math.min(
                                    1000,
                                    position
                                            * 1000L
                                            / duration
                            )
                    )
            );

        } else {

            playerSeekBar.setProgress(0);
        }

        playerTime.setText(
                formatTime(position)
                        + " / "
                        + formatTime(duration)
        );

        playPauseButton.setText(
                mediaController.isPlaying()
                        ? "⏸"
                        : "▶"
        );
    }

    private String formatTime(
            long millis) {

        if (millis < 0) {
            millis = 0;
        }

        long seconds =
                millis / 1000;

        long minutes =
                seconds / 60;

        seconds %= 60;

        return String.format(
                java.util.Locale.US,
                "%d:%02d",
                minutes,
                seconds
        );
    }

    private void showError(
            Exception e) {

        String message =
                e.getMessage();

        if (
                message == null ||
                message.isEmpty()
        ) {

            message =
                    e.getClass()
                            .getSimpleName();
        }

        Toast.makeText(
                this,
                "Error: " + message,
                Toast.LENGTH_LONG
        ).show();
    }

    @Override
    protected void onDestroy() {

        playerHandler.removeCallbacks(
                progressUpdater
        );

        executor.shutdownNow();

        if (mediaController != null) {

            mediaController.release();

            mediaController = null;
        }

        super.onDestroy();
    }

    private void chooseOfflineFolder() {

        Intent intent =
                new Intent(
                        Intent.ACTION_OPEN_DOCUMENT_TREE
                );

        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        );

        startActivityForResult(
                intent,
                PICK_OFFLINE_FOLDER
        );
    }


@Override
protected void onActivityResult(
        int requestCode,
        int resultCode,
        Intent data) {

    super.onActivityResult(
            requestCode,
            resultCode,
            data
    );

    if (requestCode == PICK_PLAYLIST_FILE
            && resultCode == RESULT_OK
            && data != null) {

        Uri uri = data.getData();

        if (uri != null) {
            importSpotifyFile(uri);
        }

    } else if (requestCode == PICK_OFFLINE_FOLDER
            && resultCode == RESULT_OK
            && data != null) {

        Uri uri = data.getData();

        if (uri != null) {

            try {

                final int takeFlags =
                        data.getFlags()
                                & (
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        );

                getContentResolver()
                        .takePersistableUriPermission(
                                uri,
                                takeFlags
                        );

                offlineFolderUri = uri;

                getSharedPreferences(
                        "settings",
                        MODE_PRIVATE
                ).edit()
                        .putString(
                                "offline_folder_uri",
                                uri.toString()
                        )
                        .apply();

                Toast.makeText(
                        this,
                        "✓ Carpeta offline seleccionada",
                        Toast.LENGTH_SHORT
                ).show();

            } catch (Exception e) {

                Toast.makeText(
                        this,
                        "❌ No se pudo guardar la carpeta",
                        Toast.LENGTH_LONG
                ).show();
            }
        }
    }
}



private String getOfflineArtist(
        DownloadSlot slot) {

    if (slot.albumTrack != null) {
        return slot.albumTrack.artist == null
                ? ""
                : slot.albumTrack.artist;
    }

    if (slot.track != null) {
        return slot.track.artist == null
                ? ""
                : slot.track.artist;
    }

    return "";
}

private String getOfflineAlbum(
        DownloadSlot slot) {

    if (slot.albumTrack != null) {
        return slot.albumTrack.album == null
                ? ""
                : slot.albumTrack.album;
    }

    if (slot.track != null) {
        return slot.track.album == null
                ? ""
                : slot.track.album;
    }

    return "";
}

private int getOfflineDuration(
        DownloadSlot slot) {

    if (slot.albumTrack != null) {
        return slot.albumTrack.duration;
    }

    if (slot.track != null) {
        return slot.track.duration;
    }

    return 0;
}

private String getOfflineThumbnail(
        DownloadSlot slot) {

    if (slot.albumTrack != null) {
        return slot.albumTrack.thumbnail == null
                ? ""
                : slot.albumTrack.thumbnail;
    }

    if (slot.track != null) {
        return slot.track.thumbnail == null
                ? ""
                : slot.track.thumbnail;
    }

    return "";
}

private void addOfflineTrack(
        String uri,
        String filename,
        long size,
        String videoId,
        String title,
        String artist,
        String album,
        int duration,
        String thumbnail) {

    try {

        android.content.SharedPreferences prefs =
                getSharedPreferences(
                        "settings",
                        MODE_PRIVATE
                );

        String stored =
                prefs.getString(
                        "offline_library",
                        "[]"
                );

        JSONArray library =
                new JSONArray(stored);

        boolean exists = false;

        for (int i = 0; i < library.length(); i++) {

            JSONObject item =
                    library.getJSONObject(i);

            if (uri.equals(
                    item.optString("uri", "")
            )) {

                exists = true;
                break;
            }
        }

        if (!exists) {

            JSONObject item =
                    new JSONObject();

            item.put(
                    "uri",
                    uri
            );

            item.put(
                    "filename",
                    filename
            );

            item.put(
                    "size",
                    size
            );

            item.put(
                    "videoId",
                    videoId == null
                            ? ""
                            : videoId
            );

            item.put(
                    "title",
                    title == null
                            ? ""
                            : title
            );

            item.put(
                    "artist",
                    artist == null
                            ? ""
                            : artist
            );

            item.put(
                    "album",
                    album == null
                            ? ""
                            : album
            );

            item.put(
                    "duration",
                    duration
            );

            item.put(
                    "thumbnail",
                    thumbnail == null
                            ? ""
                            : thumbnail
            );

            library.put(item);

            prefs.edit()
                    .putString(
                            "offline_library",
                            library.toString()
                    )
                    .apply();
        }

    } catch (Exception e) {

        android.util.Log.e(
                "MusicDownloader",
                "Error guardando biblioteca offline",
                e
        );
    }
}

private JSONArray loadOfflineLibrary() {

    try {

        String stored =
                getSharedPreferences(
                        "settings",
                        MODE_PRIVATE
                )
                        .getString(
                                "offline_library",
                                "[]"
                        );

        return new JSONArray(stored);

    } catch (Exception e) {

        android.util.Log.e(
                "MusicDownloader",
                "Error cargando biblioteca offline",
                e
        );

        return new JSONArray();
    }
}

private JSONArray scanOfflineFolder() {

    JSONArray library =
            loadOfflineLibrary();

    if (offlineFolderUri == null) {
        return library;
    }

    try {

        DocumentFile folder =
                DocumentFile.fromTreeUri(
                        this,
                        offlineFolderUri
                );

        if (folder == null
                || !folder.isDirectory()) {
            return library;
        }

        java.util.HashSet<String> knownUris =
                new java.util.HashSet<>();

        for (int i = 0; i < library.length(); i++) {

            JSONObject item =
                    library.optJSONObject(i);

            if (item != null) {

                String uri =
                        item.optString(
                                "uri",
                                ""
                        );

                if (!uri.isEmpty()) {
                    knownUris.add(uri);
                }
            }
        }

        boolean changed = false;

        DocumentFile[] files =
                folder.listFiles();

        for (DocumentFile file : files) {

            if (file == null
                    || !file.isFile()) {
                continue;
            }

            String name =
                    file.getName();

            if (name == null
                    || !name.toLowerCase()
                    .endsWith(".mp3")) {
                continue;
            }

            String uri =
                    file.getUri().toString();

            if (knownUris.contains(uri)) {
                continue;
            }

            String title =
                    name.substring(
                            0,
                            name.length() - 4
                    );

            JSONObject item =
                    new JSONObject();

            item.put(
                    "uri",
                    uri
            );

            item.put(
                    "filename",
                    name
            );

            item.put(
                    "size",
                    file.length()
            );

            item.put(
                    "videoId",
                    ""
            );

            item.put(
                    "title",
                    title
            );

            item.put(
                    "artist",
                    ""
            );

            item.put(
                    "album",
                    ""
            );

            item.put(
                    "duration",
                    0
            );

            item.put(
                    "thumbnail",
                    ""
            );

            library.put(item);

            knownUris.add(uri);
            changed = true;
        }

        if (changed) {

            getSharedPreferences(
                    "settings",
                    MODE_PRIVATE
            )
                    .edit()
                    .putString(
                            "offline_library",
                            library.toString()
                    )
                    .apply();
        }

    } catch (Exception e) {

        android.util.Log.e(
                "MusicDownloader",
                "Error escaneando carpeta offline",
                e
        );
    }

    return library;
}

private void playOfflineLibraryFromIndex(
        JSONArray library,
        int startIndex) {
        final long playbackGeneration =
                beginIndividualPlayback();


    if (library == null ||
            library.length() == 0 ||
            startIndex < 0 ||
            startIndex >= library.length()) {

        return;
    }

    if (mediaController == null) {

        Toast.makeText(
                this,
                "Reproductor no conectado",
                Toast.LENGTH_SHORT
        ).show();

        return;
    }

    executor.execute(() -> {

        boolean firstStarted = false;
        int prepared = 0;
        int failed = 0;

        for (
                int i = startIndex;
                i < library.length();
                i++
        ) {

            try {

                JSONObject item =
                        library.optJSONObject(i);

                if (item == null) {
                    failed++;
                    continue;
                }

                String uriString =
                        item.optString(
                                "uri",
                                ""
                        );

                if (uriString.trim().isEmpty()) {
                    failed++;
                    continue;
                }

                Uri uri =
                        Uri.parse(uriString);

                boolean exists = false;

                try {

                    DocumentFile file =
                            DocumentFile.fromSingleUri(
                                    this,
                                    uri
                            );

                    exists =
                            file != null &&
                                    file.exists();

                } catch (Exception ignored) {
                }

                if (!exists) {
                    failed++;
                    continue;
                }

                String title =
                        item.optString(
                                "title",
                                "Canción"
                        );

                String artist =
                        item.optString(
                                "artist",
                                ""
                        );

                String album =
                        item.optString(
                                "album",
                                ""
                        );

                String thumbnail =
                        item.optString(
                                "thumbnail",
                                ""
                        ).trim();

                String videoId =
                        item.optString(
                                "videoId",
                                ""
                        ).trim();

                if (
                        thumbnail.isEmpty() &&
                        !videoId.isEmpty()
                ) {

                    thumbnail =
                            "https://i.ytimg.com/vi/"
                                    + videoId
                                    + "/hqdefault.jpg";
                }

                MediaMetadata.Builder metadata =
                        new MediaMetadata.Builder()
                                .setTitle(title);

                if (!artist.isEmpty()) {
                    metadata.setArtist(artist);
                }

                if (!album.isEmpty()) {
                    metadata.setAlbumTitle(album);
                }

                if (!thumbnail.isEmpty()) {

                    metadata.setArtworkUri(
                            Uri.parse(thumbnail)
                    );
                }

                MediaItem mediaItem =
                        new MediaItem.Builder()
                                .setUri(uri)
                                .setMediaMetadata(
                                        metadata.build()
                                )
                                .build();

                prepared++;

                if (!firstStarted) {

                    firstStarted = true;

                    List<MediaItem> first =
                            new ArrayList<>();

                    first.add(mediaItem);

                    handler.post(
                            () -> {
                                if (!isCurrentIndividualPlayback(
                                        playbackGeneration)) {
                                    return;
                                }

                                sendQueueToPlayer(first);
                            }
                    );

                } else {

                    List<MediaItem> next =
                            new ArrayList<>();

                    next.add(mediaItem);

                    handler.post(
                            () -> {
                                if (!isCurrentIndividualPlayback(
                                        playbackGeneration)) {
                                    return;
                                }

                                addItemsToPlayerQueue(next);
                            }
                    );
                }

            } catch (Exception error) {

                failed++;

                android.util.Log.e(
                        "MusicDownloader",
                        "Offline: no se pudo preparar "
                                + "la canción "
                                + (i + 1),
                        error
                );
            }
        }

        final int totalPrepared =
                prepared;

        final int totalFailed =
                failed;

        final boolean finalFirstStarted =
                firstStarted;

        handler.post(() -> {

            if (!finalFirstStarted) {

                Toast.makeText(
                        this,
                        "No hay canciones offline disponibles desde esa posición",
                        Toast.LENGTH_LONG
                ).show();

                return;
            }

            String message =
                    "Offline preparado desde la canción "
                            + (startIndex + 1)
                            + ": "
                            + totalPrepared
                            + " canciones";

            if (totalFailed > 0) {

                message +=
                        " · "
                                + totalFailed
                                + " omitidas";
            }

            Toast.makeText(
                    this,
                    message,
                    Toast.LENGTH_SHORT
            ).show();
        });
    });
}

private void playOfflineLibrary(
        JSONArray library) {

    if (mediaController == null) {

        Toast.makeText(
                this,
                "Reproductor no conectado",
                Toast.LENGTH_SHORT
        ).show();

        return;
    }

    executor.execute(() -> {

        try {

            List<MediaItem> items =
                    new ArrayList<>();

            for (int i = 0;
                    i < library.length();
                    i++) {

                JSONObject item =
                        library.optJSONObject(i);

                if (item == null) {
                    continue;
                }

                String uriString =
                        item.optString(
                                "uri",
                                ""
                        );

                if (uriString.isEmpty()) {
                    continue;
                }

                Uri uri =
                        Uri.parse(uriString);

                boolean exists = false;

                try {

                    DocumentFile file =
                            DocumentFile.fromSingleUri(
                                    this,
                                    uri
                            );

                    exists =
                            file != null
                                    && file.exists();

                } catch (Exception ignored) {
                }

                if (!exists) {
                    continue;
                }

                String title =
                        item.optString(
                                "title",
                                "Canción"
                        );

                String artist =
                        item.optString(
                                "artist",
                                ""
                        );

                String album =
                        item.optString(
                                "album",
                                ""
                        );

                String thumbnail =
                        item.optString(
                                "thumbnail",
                                ""
                        ).trim();

                String videoId =
                        item.optString(
                                "videoId",
                                ""
                        ).trim();

                /*
                 * Portada para Reproducir todo Offline:
                 * 1. Utilizar la portada guardada.
                 * 2. Si no existe, utilizar YouTube mediante videoId.
                 */

                if (thumbnail.isEmpty() &&
                        !videoId.isEmpty()) {

                    thumbnail =
                            "https://i.ytimg.com/vi/"
                                    + videoId
                                    + "/hqdefault.jpg";
                }

                MediaMetadata.Builder metadata =
                        new MediaMetadata.Builder()
                                .setTitle(title);

                if (!artist.isEmpty()) {
                    metadata.setArtist(artist);
                }

                if (!album.isEmpty()) {
                    metadata.setAlbumTitle(album);
                }

                if (!thumbnail.isEmpty()) {
                    metadata.setArtworkUri(
                            Uri.parse(thumbnail)
                    );
                }

                MediaItem mediaItem =
                        new MediaItem.Builder()
                                .setUri(uri)
                                .setMediaMetadata(
                                        metadata.build()
                                )
                                .build();

                items.add(mediaItem);
            }

            handler.post(() -> {

                if (items.isEmpty()) {

                    Toast.makeText(
                            this,
                            "No hay canciones offline disponibles",
                            Toast.LENGTH_LONG
                    ).show();

                    return;
                }

                sendQueueToPlayer(items);

                Toast.makeText(
                        this,
                        "▶ Reproduciendo "
                                + items.size()
                                + " canciones offline",
                        Toast.LENGTH_SHORT
                ).show();
            });

        } catch (Exception e) {

            handler.post(() ->
                    Toast.makeText(
                            this,
                            "❌ Error creando la cola offline: "
                                    + e.getMessage(),
                            Toast.LENGTH_LONG
                    ).show()
            );
        }
    });
}


private long getOfflineFileDuration(Uri uri) {

    MediaMetadataRetriever retriever =
            new MediaMetadataRetriever();

    try {

        retriever.setDataSource(
                this,
                uri
        );

        String durationMs =
                retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                );

        if (durationMs == null ||
                durationMs.trim().isEmpty()) {

            return 0;
        }

        long millis =
                Long.parseLong(
                        durationMs
                );

        return millis / 1000L;

    } catch (Exception e) {

        Log.w(
                "MusicDownloader",
                "No se pudo obtener duración offline: "
                        + uri,
                e
        );

        return 0;

    } finally {

        try {
            retriever.release();
        } catch (Exception ignored) {
        }
    }
}

private void showOfflineLibrary() {

    if (offlineTabLayout == null ||
            offlineTabScroll == null) {
        return;
    }

    switchContentTab(
            offlineTabLayout,
            offlineTabScroll,
            false
    );

    /*
     * Reconstruimos completamente la biblioteca para reflejar
     * inmediatamente cualquier alta o borrado de canciones offline.
     */
    offlineTabLayout.removeAllViews();

    /*
     * Reconstruimos también las casillas de selección.
     */
    offlineSelectionChecks.clear();

    TextView title =
            new TextView(this);

    title.setText(
            "📱 Biblioteca offline"
    );

    title.setTextSize(24);
    title.setTextColor(Color.WHITE);
    title.setPadding(
            dp(8),
            dp(12),
            dp(8),
            dp(16)
    );

    contentLayout.addView(title);

    JSONArray library =
            scanOfflineFolder();

    /*
     * Indica si hemos actualizado alguna duración
     * leyendo el archivo multimedia local.
     */
    boolean libraryChanged = false;

    /*
     * BOTONES DE SELECCIÓN
     */

    LinearLayout selectionButtons =
            new LinearLayout(this);

    selectionButtons.setOrientation(
            LinearLayout.HORIZONTAL
    );

    selectionButtons.setGravity(
            Gravity.CENTER
    );

    LinearLayout.LayoutParams selectionButtonsParams =
            new LinearLayout.LayoutParams(
                    -1,
                    -2
            );

    selectionButtonsParams.setMargins(
            dp(8),
            0,
            dp(8),
            dp(8)
    );

    Button selectAll =
            visualButton("☑ Marcar todas");

    Button deselectAll =
            visualButton("☐ Desmarcar");

    LinearLayout.LayoutParams selectionButtonParams =
            new LinearLayout.LayoutParams(
                    0,
                    dp(44),
                    1f
            );

    selectionButtonParams.setMargins(
            dp(2),
            0,
            dp(2),
            0
    );

    selectionButtons.addView(
            selectAll,
            selectionButtonParams
    );

    selectionButtons.addView(
            deselectAll,
            selectionButtonParams
    );

    contentLayout.addView(
            selectionButtons,
            selectionButtonsParams
    );

    /*
     * BOTÓN REPRODUCIR TODO
     */

    Button playAll =
            roundedButton();

    playAll.setText(
            "▶ Reproducir todo"
    );

    LinearLayout.LayoutParams playAllParams =
            new LinearLayout.LayoutParams(
                    -1,
                    dp(48)
            );

    playAllParams.setMargins(
            dp(8),
            0,
            dp(8),
            dp(10)
    );

    contentLayout.addView(
            playAll,
            playAllParams
    );

    /*
     * Marcar todas las canciones.
     */
    selectAll.setOnClickListener(
            v -> {
                for (CheckBox check :
                        offlineSelectionChecks) {

                    check.setChecked(true);
                }
            }
    );

    /*
     * Desmarcar todas las canciones.
     */
    deselectAll.setOnClickListener(
            v -> {
                for (CheckBox check :
                        offlineSelectionChecks) {

                    check.setChecked(false);
                }
            }
    );

    /*
     * Reproducir solamente las canciones seleccionadas.
     */
    playAll.setOnClickListener(
            v -> {

                try {

                    JSONArray selectedLibrary =
                            new JSONArray();

                    for (int i = 0;
                            i < library.length();
                            i++) {

                        if (i < offlineSelectionChecks.size()
                                && offlineSelectionChecks
                                .get(i)
                                .isChecked()) {

                            selectedLibrary.put(
                                    library.getJSONObject(i)
                            );
                        }
                    }

                    if (selectedLibrary.length() == 0) {

                        Toast.makeText(
                                this,
                                "⚠ Selecciona al menos una canción",
                                Toast.LENGTH_SHORT
                        ).show();

                        return;
                    }

                    playOfflineLibrary(
                            selectedLibrary
                    );

                } catch (Exception e) {

                    android.util.Log.e(
                            "MusicDownloader",
                            "Error preparando reproducción offline seleccionada",
                            e
                    );

                    Toast.makeText(
                            this,
                            "❌ Error al preparar la reproducción",
                            Toast.LENGTH_SHORT
                    ).show();
                }
            }
    );

    if (library.length() == 0) {

        TextView empty =
                new TextView(this);

        empty.setText(
                "No hay canciones guardadas offline.\n\n" +
                "Usa el botón 📱 Offline de una canción " +
                "para guardarla en el móvil."
        );

        empty.setTextSize(16);
        empty.setTextColor(
                Color.rgb(190, 190, 200)
        );

        empty.setPadding(
                dp(12),
                dp(20),
                dp(12),
                dp(20)
        );

        contentLayout.addView(empty);

        return;
    }

    for (int i = 0; i < library.length(); i++) {

        try {

            JSONObject item =
                    library.getJSONObject(i);

            String uriString =
                    item.optString(
                            "uri",
                            ""
                    );

            String songTitle =
                    item.optString(
                            "title",
                            "Canción"
                    );

            String artist =
                    item.optString(
                            "artist",
                            ""
                    );

            String album =
                    item.optString(
                            "album",
                            ""
                    );

            /*
             * La duración se guarda en segundos.
             * La convertimos a milisegundos para reutilizar
             * el mismo formatTime() que usa el resto de la app.
             */
            long duration =
                    item.optLong(
                            "duration",
                            0
                    );

            String thumbnail =
                    item.optString(
                            "thumbnail",
                            ""
                    );

            if (uriString.isEmpty()) {
                continue;
            }

            Uri uri =
                    Uri.parse(uriString);

            boolean exists = false;

            try {

                DocumentFile file =
                        DocumentFile.fromSingleUri(
                                this,
                                uri
                        );

                exists =
                        file != null
                                && file.exists();

            } catch (Exception ignored) {
            }

            /*
             * Las canciones antiguas pueden tener duration=0
             * porque fueron guardadas sin ese dato.
             *
             * Si el archivo existe, obtenemos la duración real
             * directamente del MP3 y la guardamos en la biblioteca.
             */
            if (exists && duration <= 0) {

                long detectedDuration =
                        getOfflineFileDuration(uri);

                if (detectedDuration > 0) {

                    duration =
                            detectedDuration;

                    item.put(
                            "duration",
                            duration
                    );

                    libraryChanged = true;
                }
            }

            LinearLayout card =
                    new LinearLayout(this);

            card.setOrientation(
                    LinearLayout.VERTICAL
            );

            card.setPadding(
                    dp(12),
                    dp(12),
                    dp(12),
                    dp(12)
            );

            card.setBackground(
                    roundedBackground(
                            Color.rgb(32, 32, 40),
                            Color.rgb(72, 72, 84),
                            16
                    )
            );

            card.setElevation(
                    dp(3)
            );

            LinearLayout.LayoutParams cardParams =
                    new LinearLayout.LayoutParams(
                            -1,
                            -2
                    );

            cardParams.setMargins(
                    dp(8),
                    dp(6),
                    dp(8),
                    dp(6)
            );

            contentLayout.addView(
                    card,
                    cardParams
            );

            /*
             * Fila superior:
             * casilla + portada + información de la canción
             */

            LinearLayout mainRow =
                    new LinearLayout(this);

            mainRow.setOrientation(
                    LinearLayout.HORIZONTAL
            );

            mainRow.setGravity(
                    android.view.Gravity.CENTER_VERTICAL
            );

            card.addView(
                    mainRow,
                    new LinearLayout.LayoutParams(
                            -1,
                            -2
                    )
            );

            CheckBox select =
                    new CheckBox(this);

            select.setText("");
            select.setPadding(0, 0, 0, 0);

            select.setChecked(true);

            select.setButtonTintList(
                    new android.content.res.ColorStateList(
                            new int[][]{
                                    new int[]{
                                            android.R.attr.state_checked
                                    },
                                    new int[]{}
                            },
                            new int[]{
                                    Color.rgb(90, 200, 140),
                                    Color.rgb(130, 130, 140)
                            }
                    )
            );

            LinearLayout.LayoutParams selectParams =
                    new LinearLayout.LayoutParams(
                            dp(42),
                            dp(82)
                    );

            selectParams.setMargins(
                    0,
                    0,
                    dp(4),
                    0
            );

            mainRow.addView(
                    select,
                    selectParams
            );

            offlineSelectionChecks.add(
                    select
            );

            ImageView cover =
                    new ImageView(this);

            cover.setScaleType(
                    ImageView.ScaleType.CENTER_CROP
            );

            cover.setImageResource(
                    android.R.drawable.ic_media_play
            );

            LinearLayout.LayoutParams coverParams =
                    new LinearLayout.LayoutParams(
                            dp(82),
                            dp(82)
                    );

            coverParams.setMargins(
                    0,
                    0,
                    dp(12),
                    0
            );

            mainRow.addView(
                    cover,
                    coverParams
            );

            LinearLayout infoColumn =
                    new LinearLayout(this);

            infoColumn.setOrientation(
                    LinearLayout.VERTICAL
            );

            infoColumn.setGravity(
                    android.view.Gravity.CENTER_VERTICAL
            );

            mainRow.addView(
                    infoColumn,
                    new LinearLayout.LayoutParams(
                            0,
                            -2,
                            1f
                    )
            );

            TextView song =
                    new TextView(this);

            song.setText(
                    exists
                            ? "🎵 " + songTitle
                            : "⚠️ " + songTitle
            );

            song.setTextSize(17);
            song.setTextColor(Color.WHITE);

            infoColumn.addView(song);

            String details = "";

            if (!artist.isEmpty()) {
                details = artist;
            }

            if (!album.isEmpty()) {

                if (!details.isEmpty()) {
                    details += " · ";
                }

                details += album;
            }

            if (duration > 0) {

                if (!details.isEmpty()) {
                    details += " · ";
                }

                details +=
                        "⏱ "
                        + formatTime(
                                duration * 1000L
                        );
            }

            if (!details.isEmpty()) {

                TextView info =
                        new TextView(this);

                info.setText(details);
                info.setTextSize(13);
                info.setTextColor(
                        Color.rgb(165, 165, 175)
                );

                info.setPadding(
                        0,
                        dp(5),
                        0,
                        0
                );

                infoColumn.addView(info);
            }

            /*
             * Cargar portada Offline:
             * 1. Portada guardada.
             * 2. Si falla, portada de YouTube mediante videoId.
             */

            String videoId =
                    item.optString(
                            "videoId",
                            ""
                    ).trim();

            loadOfflineThumbnail(
                    thumbnail,
                    videoId,
                    cover
            );

            LinearLayout buttons =
                    new LinearLayout(this);

            buttons.setOrientation(
                    LinearLayout.HORIZONTAL
            );

            LinearLayout.LayoutParams buttonsParams =
                    new LinearLayout.LayoutParams(
                            -1,
                            -2
                    );

            buttonsParams.setMargins(
                    0,
                    dp(10),
                    0,
                    0
            );

            card.addView(
                    buttons,
                    buttonsParams
            );

            Button play =
                    roundedButton();

            play.setText(
                    exists
                            ? "▶ Reproducir"
                            : "⚠ Archivo no disponible"
            );

            play.setEnabled(exists);

            Button delete =
                    roundedButton();

            delete.setText(
                    "🗑 Eliminar"
            );

            LinearLayout.LayoutParams playParams =
                    new LinearLayout.LayoutParams(
                            0,
                            dp(46),
                            1f
                    );

            playParams.setMargins(
                    0,
                    0,
                    dp(6),
                    0
            );

            buttons.addView(
                    play,
                    playParams
            );

            LinearLayout.LayoutParams deleteParams =
                    new LinearLayout.LayoutParams(
                            0,
                            dp(46),
                            1f
                    );

            buttons.addView(
                    delete,
                    deleteParams
            );

            final String finalUri =
                    uriString;

            final String finalTitle =
                    songTitle;

            String resolvedThumbnail =
                    thumbnail;

            if ((resolvedThumbnail == null ||
                    resolvedThumbnail.trim().isEmpty()) &&
                    !videoId.isEmpty()) {

                resolvedThumbnail =
                        "https://i.ytimg.com/vi/"
                                + videoId
                                + "/hqdefault.jpg";
            }

            final String finalThumbnail =
                    resolvedThumbnail;

            final int finalItemIndex =
                    i;

            play.setOnClickListener(
                    v -> {

                        playOfflineLibraryFromIndex(
                                library,
                                finalItemIndex
                        );

                        Toast.makeText(
                                this,
                                "▶ Reproduciendo desde el móvil",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
            );

            delete.setOnClickListener(
                    v -> deleteOfflineTrack(
                            finalItemIndex,
                            finalUri
                    )
            );

        } catch (Exception e) {

            android.util.Log.e(
                    "MusicDownloader",
                    "Error mostrando canción offline",
                    e
            );
        }
    }

    /*
     * Guardamos una sola vez las duraciones detectadas,
     * evitando escribir las preferencias durante cada canción.
     */
    if (libraryChanged) {

        try {

            getSharedPreferences(
                    "MusicDownloader",
                    MODE_PRIVATE
            )
                    .edit()
                    .putString(
                            "offline_library",
                            library.toString()
                    )
                    .apply();

            Log.d(
                    "MusicDownloader",
                    "Duraciones offline actualizadas"
            );

        } catch (Exception e) {

            Log.e(
                    "MusicDownloader",
                    "Error guardando duraciones offline",
                    e
            );
        }
    }
}


private static class ActiveDownloadItem {

    String job;
    String id;
    String title;
    String type;
    String status;
    String message;
    int progress;

    String albumGroup;
    String albumTitle;
    int albumTrackIndex;
    int albumTrackTotal;

    ActiveDownloadItem(
            String job,
            String id,
            String title,
            String type,
            String status,
            String message,
            int progress,
            String albumGroup,
            String albumTitle,
            int albumTrackIndex,
            int albumTrackTotal) {

        this.job = job;
        this.id = id;
        this.title = title;
        this.type = type;
        this.status = status;
        this.message = message;
        this.progress = progress;

        this.albumGroup = albumGroup;
        this.albumTitle = albumTitle;
        this.albumTrackIndex = albumTrackIndex;
        this.albumTrackTotal = albumTrackTotal;
    }
}

private void showDownloads() {

    if (downloadsTabLayout == null ||
            downloadsTabScroll == null) {

        return;
    }

    switchContentTab(
            downloadsTabLayout,
            downloadsTabScroll,
            false
    );

    /*
     * La pestaña es persistente.
     * El contenido se reconstruye al actualizar para que
     * NAVIDROME y OFFLINE siempre reflejen el estado real
     * del servidor.
     */

    downloadsTabLayout.removeAllViews();

    // Mover la MISMA tarjeta de servidor al principio de Descargas.
    if (serverCard != null) {
        android.view.ViewParent parent = serverCard.getParent();

        if (parent instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) parent).removeView(serverCard);
        }

        downloadsTabLayout.addView(
                serverCard,
                0
        );

        serverCard.setVisibility(
                android.view.View.VISIBLE
        );
    }

    TextView title =
            new TextView(this);

    title.setText(
            "🔗 Conexiones"
    );

    title.setTextSize(24);
    title.setTextColor(
            Color.WHITE
    );

    title.setTypeface(
            null,
            android.graphics.Typeface.BOLD
    );

    title.setPadding(
            dp(10),
            dp(14),
            dp(10),
            dp(10)
    );

    downloadsTabLayout.addView(
            title
    );

    // =========================================================
    // SELECTOR DE FORMATO OFFLINE
    // =========================================================

    final String[] offlineFormat =
            {getOfflineFormat()};

    LinearLayout formatCard =
            new LinearLayout(this);

    formatCard.setOrientation(
            LinearLayout.VERTICAL
    );

    formatCard.setPadding(
            dp(16),
            dp(14),
            dp(16),
            dp(14)
    );

    android.graphics.drawable.GradientDrawable cardBackground =
            new android.graphics.drawable.GradientDrawable();

    cardBackground.setColor(
            Color.rgb(30, 30, 34)
    );

    cardBackground.setCornerRadius(
            dp(18)
    );

    formatCard.setBackground(
            cardBackground
    );

    if (android.os.Build.VERSION.SDK_INT >= 21) {
        formatCard.setElevation(
                dp(5)
        );
    }

    LinearLayout.LayoutParams cardParams =
            new LinearLayout.LayoutParams(
                    -1,
                    -2
            );

    cardParams.setMargins(
            dp(10),
            dp(4),
            dp(10),
            dp(12)
    );

    downloadsTabLayout.addView(
            formatCard,
            cardParams
    );

    TextView formatTitle =
            new TextView(this);

    formatTitle.setText(
            "🎧 Formato de descarga Offline (móvil)"
    );

    formatTitle.setTextSize(17);
    formatTitle.setTextColor(
            Color.WHITE
    );

    formatTitle.setTypeface(
            null,
            android.graphics.Typeface.BOLD
    );

    formatCard.addView(
            formatTitle
    );

    TextView formatSubtitle =
            new TextView(this);

    formatSubtitle.setText(
            "Se aplicará a Canciones, Álbumes y Spotify. Navidrome seguirá usando MP3."
    );

    formatSubtitle.setTextSize(13);
    formatSubtitle.setTextColor(
            Color.rgb(175, 175, 180)
    );

    formatSubtitle.setPadding(
            0,
            dp(4),
            0,
            dp(12)
    );

    formatCard.addView(
            formatSubtitle
    );

    LinearLayout formatButtons =
            new LinearLayout(this);

    formatButtons.setOrientation(
            LinearLayout.HORIZONTAL
    );

    formatButtons.setGravity(
            android.view.Gravity.CENTER
    );

    formatCard.addView(
            formatButtons,
            new LinearLayout.LayoutParams(
                    -1,
                    -2
            )
    );

    Button mp3Button =
            new Button(this);

    mp3Button.setTextSize(14);
    mp3Button.setAllCaps(false);
    mp3Button.setTextColor(
            Color.WHITE
    );

    mp3Button.setStateListAnimator(null);

    if (android.os.Build.VERSION.SDK_INT >= 21) {
        mp3Button.setElevation(
                dp(3)
        );
    }

    LinearLayout.LayoutParams formatButtonParams =
            new LinearLayout.LayoutParams(
                    0,
                    dp(50),
                    1f
            );

    formatButtonParams.setMargins(
            0,
            0,
            dp(6),
            0
    );

    formatButtons.addView(
            mp3Button,
            formatButtonParams
    );

    Button opusButton =
            new Button(this);

    opusButton.setTextSize(14);
    opusButton.setAllCaps(false);
    opusButton.setTextColor(
            Color.WHITE
    );

    opusButton.setStateListAnimator(null);

    if (android.os.Build.VERSION.SDK_INT >= 21) {
        opusButton.setElevation(
                dp(3)
        );
    }

    LinearLayout.LayoutParams opusButtonParams =
            new LinearLayout.LayoutParams(
                    0,
                    dp(50),
                    1f
            );

    opusButtonParams.setMargins(
            dp(6),
            0,
            0,
            0
    );

    formatButtons.addView(
            opusButton,
            opusButtonParams
    );

    Runnable refreshFormatButtons =
            () -> {

                boolean opusSelected =
                        "opus".equals(
                                offlineFormat[0]
                        );

                mp3Button.setText(
                        opusSelected
                                ? "🎵 MP3"
                                : "✓  🎵 MP3"
                );

                opusButton.setText(
                        opusSelected
                                ? "✓  ⚡ Opus"
                                : "⚡ Opus"
                );

                mp3Button.setAlpha(
                        opusSelected
                                ? 0.55f
                                : 1.0f
                );

                opusButton.setAlpha(
                        opusSelected
                                ? 1.0f
                                : 0.55f
                );

                android.graphics.drawable.GradientDrawable mp3Background =
                        new android.graphics.drawable.GradientDrawable();

                mp3Background.setColor(
                        opusSelected
                                ? Color.rgb(45, 45, 50)
                                : Color.rgb(65, 65, 72)
                );

                mp3Background.setCornerRadius(
                        dp(14)
                );

                mp3Button.setBackground(
                        mp3Background
                );

                android.graphics.drawable.GradientDrawable opusBackground =
                        new android.graphics.drawable.GradientDrawable();

                opusBackground.setColor(
                        opusSelected
                                ? Color.rgb(65, 65, 72)
                                : Color.rgb(45, 45, 50)
                );

                opusBackground.setCornerRadius(
                        dp(14)
                );

                opusButton.setBackground(
                        opusBackground
                );
            };

    mp3Button.setOnClickListener(
            v -> {

                offlineFormat[0] = "mp3";

                saveOfflineFormat(
                        "mp3"
                );

                refreshFormatButtons.run();
            }
    );

    opusButton.setOnClickListener(
            v -> {

                offlineFormat[0] = "opus";

                saveOfflineFormat(
                        "opus"
                );

                refreshFormatButtons.run();
            }
    );

    refreshFormatButtons.run();

    TextView activeTitle =
            new TextView(this);

    activeTitle.setText(
            "⬇ DESCARGAS ACTIVAS"
    );

    activeTitle.setTextSize(17);
    activeTitle.setTextColor(
            Color.WHITE
    );

    activeTitle.setTypeface(
            null,
            android.graphics.Typeface.BOLD
    );

    activeTitle.setPadding(
            dp(10),
            dp(8),
            dp(10),
            dp(6)
    );

    downloadsTabLayout.addView(
            activeTitle
    );

    downloadsActiveList =
            new LinearLayout(this);

    downloadsActiveList.setOrientation(
            LinearLayout.VERTICAL
    );

    downloadsTabLayout.addView(
            downloadsActiveList,
            new LinearLayout.LayoutParams(
                    -1,
                    -2
            )
    );

    LinearLayout historyList =
            new LinearLayout(this);

    historyList.setOrientation(
            LinearLayout.VERTICAL
    );

    TextView historyTitle =
            new TextView(this);

    historyTitle.setText(
            "📋 HISTORIAL"
    );

    historyTitle.setTextSize(17);
    historyTitle.setTextColor(
            Color.WHITE
    );

    historyTitle.setTypeface(
            null,
            android.graphics.Typeface.BOLD
    );

    historyTitle.setPadding(
            dp(10),
            dp(16),
            dp(10),
            dp(6)
    );

    downloadsTabLayout.addView(
            historyTitle
    );

    LinearLayout actions =
            new LinearLayout(this);

    actions.setOrientation(
            LinearLayout.HORIZONTAL
    );

    downloadsTabLayout.addView(
            actions,
            new LinearLayout.LayoutParams(
                    -1,
                    dp(52)
            )
    );

    Button refresh =
            roundedButton();

    refresh.setText(
            "🔄 Actualizar"
    );

    Button reset =
            roundedButton();

    reset.setText(
            "🗑️ Resetear"
    );

    LinearLayout.LayoutParams actionParams =
            new LinearLayout.LayoutParams(
                    0,
                    dp(46),
                    1f
            );

    actionParams.setMargins(
            dp(4),
            0,
            dp(4),
            0
    );

    actions.addView(
            refresh,
            new LinearLayout.LayoutParams(
                    actionParams
            )
    );

    actions.addView(
            reset,
            new LinearLayout.LayoutParams(
                    actionParams
            )
    );

    downloadsTabLayout.addView(
            historyList,
            new LinearLayout.LayoutParams(
                    -1,
                    -2
            )
    );

    TextView info =
            new TextView(this);

    info.setText(
            "Las descargas activas aparecen aquí tanto si van a "
                    + "🎧 Navidrome como si son 📱 Offline.\n"
                    + "Cuando terminan, pasan al historial."
    );

    info.setTextSize(13);
    info.setTextColor(
            Color.rgb(
                    170,
                    170,
                    180
            )
    );

    info.setPadding(
            dp(10),
            dp(10),
            dp(10),
            dp(12)
    );

    downloadsTabLayout.addView(
            info,
            2
    );

    Runnable loadDownloads =
            () -> {

                downloadsActiveList.removeAllViews();
                historyList.removeAllViews();

                TextView activeLoading =
                        new TextView(this);

                activeLoading.setText(
                        "⏳ Comprobando descargas activas..."
                );

                activeLoading.setTextSize(14);
                activeLoading.setTextColor(
                        Color.LTGRAY
                );

                activeLoading.setPadding(
                        dp(12),
                        dp(16),
                        dp(12),
                        dp(16)
                );

                downloadsActiveList.addView(
                        activeLoading
                );

                TextView historyLoading =
                        new TextView(this);

                historyLoading.setText(
                        "⏳ Cargando historial..."
                );

                historyLoading.setTextSize(14);
                historyLoading.setTextColor(
                        Color.LTGRAY
                );

                historyLoading.setPadding(
                        dp(12),
                        dp(16),
                        dp(12),
                        dp(16)
                );

                historyList.addView(
                        historyLoading
                );

                executor.execute(() -> {

                    try {

                        JSONObject data =
                                api().getQueue();

                        JSONArray queue =
                                data.optJSONArray(
                                        "queue"
                                );

                        JSONArray history =
                                data.optJSONArray(
                                        "history"
                                );

                        java.util.List<ActiveDownloadItem> activeItems =
                                new java.util.ArrayList<>();

                        java.util.HashSet<String> activeJobs =
                                new java.util.HashSet<>();

                        if (queue != null) {

                            for (
                                    int i = 0;
                                    i < queue.length();
                                    i++
                            ) {

                                JSONObject item =
                                        queue.optJSONObject(i);

                                if (item == null) {
                                    continue;
                                }

                                String job =
                                        item.optString(
                                                "job",
                                                ""
                                        );

                                if (job.isEmpty()) {
                                    continue;
                                }

                                String status =
                                        item.optString(
                                                "status",
                                                ""
                                        );

                                String statusLower =
                                        status.toLowerCase(
                                                java.util.Locale.ROOT
                                        );

                                if (
                                        !statusLower.equals("queued")
                                                && !statusLower.equals("running")
                                                && !statusLower.equals("paused")
                                                && !statusLower.equals("downloading")
                                                && !statusLower.equals("processing")
                                ) {
                                    continue;
                                }

                                String type =
                                        item.optString(
                                                "type",
                                                "navidrome"
                                        );

                                if (type.isEmpty()) {
                                    type = "navidrome";
                                }

                                String albumGroup =
                                        item.optString(
                                                "album_group",
                                                ""
                                        );

                                String albumTitle =
                                        item.optString(
                                                "album_title",
                                                ""
                                        );

                                int albumTrackIndex =
                                        item.optInt(
                                                "album_track_index",
                                                0
                                        );

                                int albumTrackTotal =
                                        item.optInt(
                                                "album_track_total",
                                                0
                                        );

                                activeItems.add(
                                        new ActiveDownloadItem(
                                                job,
                                                item.optString(
                                                        "id",
                                                        ""
                                                ),
                                                item.optString(
                                                        "title",
                                                        "Descarga"
                                                ),
                                                type,
                                                status,
                                                item.optString(
                                                        "message",
                                                        ""
                                                ),
                                                Math.max(
                                                        0,
                                                        Math.min(
                                                                100,
                                                                item.optInt(
                                                                        "progress",
                                                                        0
                                                                )
                                                        )
                                                ),
                                                albumGroup,
                                                albumTitle,
                                                albumTrackIndex,
                                                albumTrackTotal
                                        )
                                );

                                activeJobs.add(
                                        job
                                );
                            }
                        }

                        handler.post(() -> {

                            downloadsActiveList.removeAllViews();

                            if (activeItems.isEmpty()) {

                                TextView empty =
                                        new TextView(this);

                                empty.setText(
                                        "✓ No hay descargas activas."
                                );

                                empty.setTextSize(15);
                                empty.setTextColor(
                                        Color.rgb(
                                                180,
                                                180,
                                                190
                                        )
                                );

                                empty.setPadding(
                                        dp(12),
                                        dp(16),
                                        dp(12),
                                        dp(20)
                                );

                                downloadsActiveList.addView(
                                        empty
                                );

                            } else {

                                /*
                                 * Los trabajos de un álbum son
                                 * independientes en el servidor,
                                 * pero aquí se muestran como una
                                 * única tarjeta.
                                 *
                                 * Las descargas individuales siguen
                                 * usando su tarjeta normal.
                                 */
                                java.util.LinkedHashMap<String, java.util.List<ActiveDownloadItem>>
                                        albumGroups =
                                        new java.util.LinkedHashMap<>();

                                java.util.List<ActiveDownloadItem>
                                        singles =
                                        new java.util.ArrayList<>();

                                for (
                                        ActiveDownloadItem item :
                                        activeItems
                                ) {

                                    if (
                                            item.albumGroup != null
                                                    && !item.albumGroup.isEmpty()
                                    ) {

                                        java.util.List<ActiveDownloadItem>
                                                group =
                                                        albumGroups.get(
                                                                item.albumGroup
                                                        );

                                        if (group == null) {

                                            group =
                                                    new java.util.ArrayList<>();

                                            albumGroups.put(
                                                    item.albumGroup,
                                                    group
                                            );
                                        }

                                        group.add(
                                                item
                                        );

                                    } else {

                                        singles.add(
                                                item
                                        );
                                    }
                                }

                                for (
                                        java.util.Map.Entry<String, java.util.List<ActiveDownloadItem>>
                                                entry :
                                                albumGroups.entrySet()
                                ) {

                                    java.util.List<ActiveDownloadItem>
                                            group =
                                                    entry.getValue();

                                    java.util.Collections.sort(
                                            group,
                                            (a, b) ->
                                                    Integer.compare(
                                                            a.albumTrackIndex,
                                                            b.albumTrackIndex
                                                    )
                                    );

                                    addUnifiedActiveAlbumCard(
                                            downloadsActiveList,
                                            group
                                    );
                                }

                                for (
                                        ActiveDownloadItem item :
                                        singles
                                ) {

                                    addUnifiedActiveDownloadCard(
                                            downloadsActiveList,
                                            item
                                    );
                                }
                            }

                            historyList.removeAllViews();

                            if (history == null ||
                                    history.length() == 0) {

                                TextView emptyHistory =
                                        new TextView(this);

                                emptyHistory.setText(
                                        "No hay descargas en el historial."
                                );

                                emptyHistory.setTextSize(15);
                                emptyHistory.setTextColor(
                                        Color.rgb(
                                                180,
                                                180,
                                                190
                                        )
                                );

                                emptyHistory.setPadding(
                                        dp(12),
                                        dp(16),
                                        dp(12),
                                        dp(20)
                                );

                                historyList.addView(
                                        emptyHistory
                                );

                            } else {

                                int shown = 0;

                                /*
                                 * El servidor devuelve history[-50:].
                                 * Lo recorremos al revés para mostrar
                                 * primero lo más reciente.
                                 */
                                for (
                                        int i = history.length() - 1;
                                        i >= 0;
                                        i--
                                ) {

                                    JSONObject item =
                                            history.optJSONObject(i);

                                    if (item == null) {
                                        continue;
                                    }

                                    String job =
                                            item.optString(
                                                    "job",
                                                    ""
                                            );

                                    /*
                                     * Una descarga activa nunca se
                                     * muestra también en historial.
                                     */
                                    if (
                                            !job.isEmpty()
                                                    && activeJobs.contains(job)
                                    ) {
                                        continue;
                                    }

                                    addUnifiedHistoryCard(
                                            historyList,
                                            item
                                    );

                                    shown++;

                                    if (shown >= 50) {
                                        break;
                                    }
                                }

                                if (shown == 0) {

                                    TextView emptyHistory =
                                            new TextView(this);

                                    emptyHistory.setText(
                                            "No hay registros finalizados."
                                    );

                                    emptyHistory.setTextSize(15);
                                    emptyHistory.setTextColor(
                                            Color.rgb(
                                                    180,
                                                    180,
                                                    190
                                            )
                                    );

                                    emptyHistory.setPadding(
                                            dp(12),
                                            dp(16),
                                            dp(12),
                                            dp(20)
                                    );

                                    historyList.addView(
                                            emptyHistory
                                    );
                                }
                            }
                        });

                    } catch (Exception e) {

                        handler.post(() -> {

                            downloadsActiveList.removeAllViews();
                            historyList.removeAllViews();

                            TextView error =
                                    new TextView(this);

                            error.setText(
                                    "❌ No se pudieron cargar las descargas.\n"
                                            + e.getMessage()
                            );

                            error.setTextSize(14);
                            error.setTextColor(
                                    Color.rgb(
                                            255,
                                            150,
                                            150
                                    )
                            );

                            error.setPadding(
                                    dp(12),
                                    dp(16),
                                    dp(12),
                                    dp(16)
                            );

                            downloadsActiveList.addView(
                                    error
                            );
                        });
                    }
                });
            };

    refresh.setOnClickListener(
            v -> loadDownloads.run()
    );

    reset.setOnClickListener(
            v -> {

                new android.app.AlertDialog.Builder(this)
                        .setTitle(
                                "Resetear historial"
                        )
                        .setMessage(
                                "¿Quieres borrar todo el historial?\n\n"
                                        + "Las canciones descargadas NO se eliminarán."
                        )
                        .setNegativeButton(
                                "Cancelar",
                                null
                        )
                        .setPositiveButton(
                                "Resetear",
                                (dialog, which) -> {

                                    executor.execute(() -> {

                                        try {

                                            JSONObject result =
                                                    api().resetMobileDownloads();

                                            handler.post(() -> {

                                                Toast.makeText(
                                                        this,
                                                        result.optString(
                                                                "message",
                                                                "Historial reseteado."
                                                        ),
                                                        Toast.LENGTH_LONG
                                                ).show();

                                                loadDownloads.run();
                                            });

                                        } catch (Exception e) {

                                            handler.post(() -> {

                                                Toast.makeText(
                                                        this,
                                                        "❌ Error: "
                                                                + e.getMessage(),
                                                        Toast.LENGTH_LONG
                                                ).show();
                                            });
                                        }
                                    });
                                }
                        )
                        .show();
            }
    );

    loadDownloads.run();
}




private void addUnifiedActiveDownloadCard(
        LinearLayout parent,
        ActiveDownloadItem item) {

    LinearLayout card =
            new LinearLayout(this);

    card.setOrientation(
            LinearLayout.VERTICAL
    );

    card.setPadding(
            dp(14),
            dp(12),
            dp(14),
            dp(12)
    );

    GradientDrawable cardBackground =
            new GradientDrawable();

    cardBackground.setColor(
            Color.rgb(
                    28,
                    28,
                    34
            )
    );

    cardBackground.setCornerRadius(
            dp(16)
    );

    cardBackground.setStroke(
            dp(1),
            Color.rgb(
                    65,
                    65,
                    75
            )
    );

    card.setBackground(
            cardBackground
    );

    LinearLayout.LayoutParams cardParams =
            new LinearLayout.LayoutParams(
                    -1,
                    -2
            );

    cardParams.setMargins(
            dp(8),
            dp(6),
            dp(8),
            dp(6)
    );

    parent.addView(
            card,
            cardParams
    );

    TextView typeView =
            new TextView(this);

    boolean mobile =
            "mobile".equalsIgnoreCase(
                    item.type
            );

    typeView.setText(
            mobile
                    ? "📱 OFFLINE"
                    : "🎧 NAVIDROME"
    );

    typeView.setTextSize(12);
    typeView.setTextColor(
            mobile
                    ? Color.rgb(
                            130,
                            205,
                            255
                    )
                    : Color.rgb(
                            180,
                            150,
                            255
                    )
    );

    typeView.setTypeface(
            null,
            android.graphics.Typeface.BOLD
    );

    card.addView(
            typeView
    );

    TextView titleView =
            new TextView(this);

    titleView.setText(
            "🎵 " + item.title
    );

    titleView.setTextSize(17);
    titleView.setTextColor(
            Color.WHITE
    );

    titleView.setTypeface(
            null,
            android.graphics.Typeface.BOLD
    );

    titleView.setPadding(
            0,
            dp(5),
            0,
            dp(4)
    );

    card.addView(
            titleView
    );

    TextView statusView =
            new TextView(this);

    String initialStatus =
            item.message == null ||
                    item.message.isEmpty()
                    ? item.status
                    : item.message;

    statusView.setText(
            initialStatus == null ||
                    initialStatus.isEmpty()
                    ? "Descargando..."
                    : initialStatus
    );

    statusView.setTextSize(13);
    statusView.setTextColor(
            Color.rgb(
                    185,
                    185,
                    195
            )
    );

    card.addView(
            statusView
    );

    ProgressBar progressBar =
            new ProgressBar(
                    this,
                    null,
                    android.R.attr.progressBarStyleHorizontal
            );

    progressBar.setMax(
            100
    );

    progressBar.setProgress(
            item.progress
    );

    LinearLayout.LayoutParams progressParams =
            new LinearLayout.LayoutParams(
                    -1,
                    dp(8)
            );

    progressParams.setMargins(
            0,
            dp(9),
            0,
            dp(4)
    );

    card.addView(
            progressBar,
            progressParams
    );

    TextView progressView =
            new TextView(this);

    progressView.setText(
            item.progress + "%"
    );

    progressView.setTextSize(12);
    progressView.setTextColor(
            Color.rgb(
                    160,
                    160,
                    170
            )
    );

    progressView.setGravity(
            android.view.Gravity.RIGHT
    );

    card.addView(
            progressView
    );

    LinearLayout controls =
            new LinearLayout(this);

    controls.setOrientation(
            LinearLayout.HORIZONTAL
    );

    controls.setPadding(
            0,
            dp(8),
            0,
            0
    );

    card.addView(
            controls,
            new LinearLayout.LayoutParams(
                    -1,
                    dp(50)
            )
    );

    Button pause =
            roundedButton();

    pause.setText(
            "⏸ Pausar"
    );

    Button resume =
            roundedButton();

    resume.setText(
            "▶ Reanudar"
    );

    Button cancel =
            roundedButton();

    cancel.setText(
            "✕ Cancelar"
    );

    LinearLayout.LayoutParams controlParams =
            new LinearLayout.LayoutParams(
                    0,
                    dp(44),
                    1f
            );

    controlParams.setMargins(
            dp(3),
            0,
            dp(3),
            0
    );

    controls.addView(
            pause,
            new LinearLayout.LayoutParams(
                    controlParams
            )
    );

    controls.addView(
            resume,
            new LinearLayout.LayoutParams(
                    controlParams
            )
    );

    controls.addView(
            cancel,
            new LinearLayout.LayoutParams(
                    controlParams
            )
    );

    String currentState =
            item.status == null
                    ? ""
                    : item.status.toLowerCase(
                            java.util.Locale.ROOT
                    );

    boolean paused =
            "paused".equals(
                    currentState
            );

    pause.setEnabled(
            !paused
    );

    resume.setEnabled(
            paused
    );

    cancel.setEnabled(
            true
    );

    pause.setOnClickListener(
            v -> {

                pause.setEnabled(false);

                executor.execute(() -> {

                    try {

                        JSONObject result =
                                api().pauseDownload(
                                        item.job
                                );

                        handler.post(() -> {

                            Toast.makeText(
                                    this,
                                    result.optString(
                                            "message",
                                            "Descarga pausada."
                                    ),
                                    Toast.LENGTH_SHORT
                            ).show();

                            showDownloads();
                        });

                    } catch (Exception e) {

                        handler.post(() -> {

                            pause.setEnabled(true);

                            Toast.makeText(
                                    this,
                                    "❌ "
                                            + e.getMessage(),
                                    Toast.LENGTH_LONG
                            ).show();
                        });
                    }
                });
            }
    );

    resume.setOnClickListener(
            v -> {

                resume.setEnabled(false);

                executor.execute(() -> {

                    try {

                        JSONObject result =
                                api().resumeDownload(
                                        item.job
                                );

                        handler.post(() -> {

                            Toast.makeText(
                                    this,
                                    result.optString(
                                            "message",
                                            "Descarga reanudada."
                                    ),
                                    Toast.LENGTH_SHORT
                            ).show();

                            showDownloads();
                        });

                    } catch (Exception e) {

                        handler.post(() -> {

                            resume.setEnabled(true);

                            Toast.makeText(
                                    this,
                                    "❌ "
                                            + e.getMessage(),
                                    Toast.LENGTH_LONG
                            ).show();
                        });
                    }
                });
            }
    );

    cancel.setOnClickListener(
            v -> {

                new android.app.AlertDialog.Builder(this)
                        .setTitle(
                                "Cancelar descarga"
                        )
                        .setMessage(
                                "¿Quieres cancelar esta descarga?"
                        )
                        .setNegativeButton(
                                "No",
                                null
                        )
                        .setPositiveButton(
                                "Cancelar",
                                (dialog, which) -> {

                                    cancel.setEnabled(
                                            false
                                    );

                                    cancel.setText(
                                            "⏳ Cancelando..."
                                    );

                                    executor.execute(() -> {

                                        try {

                                            JSONObject result =
                                                    api().cancelDownload(
                                                            item.job
                                                    );

                                            boolean ok =
                                                    result.optBoolean(
                                                            "ok",
                                                            false
                                                    );

                                            handler.post(() -> {

                                                if (ok) {

                                                    /*
                                                     * La cancelación ya ha sido
                                                     * confirmada por el servidor.
                                                     * Quitamos inmediatamente la
                                                     * tarjeta de DESCARGAS ACTIVAS.
                                                     */
                                                    android.view.ViewParent parentView =
                                                            card.getParent();

                                                    if (parentView instanceof android.view.ViewGroup) {

                                                        ((android.view.ViewGroup) parentView)
                                                                .removeView(card);
                                                    }

                                                    Toast.makeText(
                                                            this,
                                                            result.optString(
                                                                    "message",
                                                                    "Descarga cancelada."
                                                            ),
                                                            Toast.LENGTH_SHORT
                                                    ).show();

                                                    /*
                                                     * Recargamos la pestaña para
                                                     * que el registro aparezca
                                                     * en HISTORIAL.
                                                     */
                                                    showDownloads();

                                                } else {

                                                    cancel.setEnabled(
                                                            true
                                                    );

                                                    cancel.setText(
                                                            "✕ Cancelar"
                                                    );

                                                    Toast.makeText(
                                                            this,
                                                            result.optString(
                                                                    "message",
                                                                    "No se pudo cancelar la descarga."
                                                            ),
                                                            Toast.LENGTH_LONG
                                                    ).show();
                                                }
                                            });

                                        } catch (Exception e) {

                                            handler.post(() -> {

                                                cancel.setEnabled(
                                                        true
                                                );

                                                cancel.setText(
                                                        "✕ Cancelar"
                                                );

                                                Toast.makeText(
                                                        this,
                                                        "❌ "
                                                                + e.getMessage(),
                                                        Toast.LENGTH_LONG
                                                ).show();
                                            });
                                        }
                                    });
                                }
                        )
                        .show();
            }
    );
}



private void addUnifiedActiveAlbumCard(
        LinearLayout parent,
        java.util.List<ActiveDownloadItem> items) {

    if (items == null || items.isEmpty()) {
        return;
    }

    ActiveDownloadItem first =
            items.get(0);

    LinearLayout card =
            new LinearLayout(this);

    card.setOrientation(
            LinearLayout.VERTICAL
    );

    card.setPadding(
            dp(14),
            dp(12),
            dp(14),
            dp(12)
    );

    GradientDrawable cardBackground =
            new GradientDrawable();

    cardBackground.setColor(
            Color.rgb(
                    28,
                    28,
                    34
            )
    );

    cardBackground.setCornerRadius(
            dp(16)
    );

    cardBackground.setStroke(
            dp(1),
            Color.rgb(
                    65,
                    65,
                    75
            )
    );

    card.setBackground(
            cardBackground
    );

    LinearLayout.LayoutParams cardParams =
            new LinearLayout.LayoutParams(
                    -1,
                    -2
            );

    cardParams.setMargins(
            dp(8),
            dp(6),
            dp(8),
            dp(6)
    );

    parent.addView(
            card,
            cardParams
    );

    TextView albumHeader =
            new TextView(this);

    String albumName =
            first.albumTitle == null ||
                    first.albumTitle.isEmpty()
                    ? "Álbum"
                    : first.albumTitle;

    albumHeader.setText(
            "📀 ÁLBUM — " + albumName
    );

    albumHeader.setTextSize(18);
    albumHeader.setTextColor(
            Color.WHITE
    );

    albumHeader.setTypeface(
            null,
            android.graphics.Typeface.BOLD
    );

    albumHeader.setPadding(
            0,
            0,
            0,
            dp(4)
    );

    card.addView(
            albumHeader
    );

    TextView typeView =
            new TextView(this);

    boolean mobile =
            "mobile".equalsIgnoreCase(
                    first.type
            );

    typeView.setText(
            mobile
                    ? "📱 OFFLINE"
                    : "🎧 NAVIDROME"
    );

    typeView.setTextSize(12);

    typeView.setTextColor(
            mobile
                    ? Color.rgb(
                            130,
                            205,
                            255
                    )
                    : Color.rgb(
                            180,
                            150,
                            255
                    )
    );

    typeView.setTypeface(
            null,
            android.graphics.Typeface.BOLD
    );

    card.addView(
            typeView
    );

    int total =
            items.size();

    int completed = 0;
    int totalProgress = 0;

    for (
            ActiveDownloadItem item :
            items
    ) {

        if (
                "done".equalsIgnoreCase(
                        item.status
                )
        ) {
            completed++;
        }

        totalProgress +=
                Math.max(
                        0,
                        Math.min(
                                100,
                                item.progress
                        )
                );
    }

    int averageProgress =
            total > 0
                    ? Math.max(
                            0,
                            Math.min(
                                    100,
                                    Math.round(
                                            totalProgress /
                                                    (float) total
                                    )
                            )
                    )
                    : 0;

    for (
            ActiveDownloadItem item :
            items
    ) {

        LinearLayout trackRow =
                new LinearLayout(this);

        trackRow.setOrientation(
                LinearLayout.HORIZONTAL
        );

        trackRow.setGravity(
                android.view.Gravity.CENTER_VERTICAL
        );

        trackRow.setPadding(
                0,
                dp(5),
                0,
                dp(5)
        );

        TextView trackText =
                new TextView(this);

        String state =
                item.status == null
                        ? ""
                        : item.status.toLowerCase(
                                java.util.Locale.ROOT
                        );

        String icon;

        if ("done".equals(state)) {
            icon = "✓";
        } else if ("paused".equals(state)) {
            icon = "⏸";
        } else if (
                "error".equals(state) ||
                "cancelled".equals(state)
        ) {
            icon = "✕";
        } else if (item.progress > 0) {
            icon = "⏳";
        } else {
            icon = "○";
        }

        String trackTitle =
                item.title == null ||
                        item.title.isEmpty()
                        ? "Canción"
                        : item.title;

        String prefix =
                item.albumTrackIndex > 0
                        ? String.format(
                                java.util.Locale.ROOT,
                                "%02d - ",
                                item.albumTrackIndex
                        )
                        : "";

        trackText.setText(
                icon
                        + " "
                        + prefix
                        + trackTitle
        );

        trackText.setTextSize(14);
        trackText.setTextColor(
                Color.rgb(
                        235,
                        235,
                        240
                )
        );

        trackText.setMaxLines(
                2
        );

        trackRow.addView(
                trackText,
                new LinearLayout.LayoutParams(
                        0,
                        -2,
                        1f
                )
        );

        if (
                item.progress > 0 &&
                item.progress < 100 &&
                !state.equals("paused")
        ) {

            TextView percent =
                    new TextView(this);

            percent.setText(
                    item.progress + "%"
            );

            percent.setTextSize(12);
            percent.setTextColor(
                    Color.LTGRAY
            );

            percent.setPadding(
                    dp(8),
                    0,
                    0,
                    0
            );

            trackRow.addView(
                    percent,
                    new LinearLayout.LayoutParams(
                            -2,
                            -2
                    )
            );
        }

        card.addView(
                trackRow
        );
    }

    TextView completedView =
            new TextView(this);

    completedView.setText(
            completed
                    + " / "
                    + total
                    + " completadas"
    );

    completedView.setTextSize(14);
    completedView.setTextColor(
            Color.rgb(
                    190,
                    190,
                    200
            )
    );

    completedView.setPadding(
            0,
            dp(8),
            0,
            dp(4)
    );

    card.addView(
            completedView
    );

    ProgressBar progressBar =
            new ProgressBar(
                    this,
                    null,
                    android.R.attr.progressBarStyleHorizontal
            );

    progressBar.setMax(
            100
    );

    progressBar.setProgress(
            averageProgress
    );

    LinearLayout.LayoutParams progressParams =
            new LinearLayout.LayoutParams(
                    -1,
                    dp(8)
            );

    progressParams.setMargins(
            0,
            dp(4),
            0,
            dp(4)
    );

    card.addView(
            progressBar,
            progressParams
    );

    TextView progressText =
            new TextView(this);

    progressText.setText(
            averageProgress
                    + "%"
    );

    progressText.setTextSize(12);
    progressText.setTextColor(
            Color.LTGRAY
    );

    progressText.setGravity(
            android.view.Gravity.RIGHT
    );

    card.addView(
            progressText
    );

    LinearLayout controls =
            new LinearLayout(this);

    controls.setOrientation(
            LinearLayout.HORIZONTAL
    );

    controls.setPadding(
            0,
            dp(8),
            0,
            0
    );

    card.addView(
            controls,
            new LinearLayout.LayoutParams(
                    -1,
                    dp(50)
            )
    );

    Button pause =
            roundedButton();

    pause.setText(
            "⏸ Pausar"
    );

    Button resume =
            roundedButton();

    resume.setText(
            "▶ Reanudar"
    );

    Button cancel =
            roundedButton();

    cancel.setText(
            "✕ Cancelar"
    );

    LinearLayout.LayoutParams controlParams =
            new LinearLayout.LayoutParams(
                    0,
                    dp(44),
                    1f
            );

    controlParams.setMargins(
            dp(3),
            0,
            dp(3),
            0
    );

    controls.addView(
            pause,
            new LinearLayout.LayoutParams(
                    controlParams
            )
    );

    controls.addView(
            resume,
            new LinearLayout.LayoutParams(
                    controlParams
            )
    );

    controls.addView(
            cancel,
            new LinearLayout.LayoutParams(
                    controlParams
            )
    );

    boolean anyPaused = false;
    boolean anyRunning = false;

    for (
            ActiveDownloadItem item :
            items
    ) {

        String state =
                item.status == null
                        ? ""
                        : item.status.toLowerCase(
                                java.util.Locale.ROOT
                        );

        if ("paused".equals(state)) {
            anyPaused = true;
        } else if (
                "queued".equals(state) ||
                "running".equals(state) ||
                "downloading".equals(state) ||
                "processing".equals(state)
        ) {
            anyRunning = true;
        }
    }

    pause.setEnabled(
            anyRunning
    );

    resume.setEnabled(
            anyPaused
    );

    cancel.setEnabled(
            true
    );

    pause.setOnClickListener(
            v -> {

                pause.setEnabled(
                        false
                );

                executor.execute(() -> {

                    int success = 0;

                    try {

                        for (
                                ActiveDownloadItem item :
                                items
                        ) {

                            try {

                                JSONObject result =
                                        api().pauseDownload(
                                                item.job
                                        );

                                if (
                                        result.optBoolean(
                                                "ok",
                                                false
                                        )
                                ) {
                                    success++;
                                }

                            } catch (Exception ignored) {
                            }
                        }

                        final int pausedCount =
                                success;

                        handler.post(() -> {

                            Toast.makeText(
                                    this,
                                    "⏸ "
                                            + pausedCount
                                            + " descarga(s) pausadas.",
                                    Toast.LENGTH_SHORT
                            ).show();

                            showDownloads();
                        });

                    } catch (Exception e) {

                        handler.post(() -> {

                            pause.setEnabled(
                                    true
                            );

                            Toast.makeText(
                                    this,
                                    "❌ "
                                            + e.getMessage(),
                                    Toast.LENGTH_LONG
                            ).show();
                        });
                    }
                });
            }
    );

    resume.setOnClickListener(
            v -> {

                resume.setEnabled(
                        false
                );

                executor.execute(() -> {

                    int success = 0;

                    try {

                        for (
                                ActiveDownloadItem item :
                                items
                        ) {

                            try {

                                JSONObject result =
                                        api().resumeDownload(
                                                item.job
                                        );

                                if (
                                        result.optBoolean(
                                                "ok",
                                                false
                                        )
                                ) {
                                    success++;
                                }

                            } catch (Exception ignored) {
                            }
                        }

                        final int resumedCount =
                                success;

                        handler.post(() -> {

                            Toast.makeText(
                                    this,
                                    "▶ "
                                            + resumedCount
                                            + " descarga(s) reanudadas.",
                                    Toast.LENGTH_SHORT
                            ).show();

                            showDownloads();
                        });

                    } catch (Exception e) {

                        handler.post(() -> {

                            resume.setEnabled(
                                    true
                            );

                            Toast.makeText(
                                    this,
                                    "❌ "
                                            + e.getMessage(),
                                    Toast.LENGTH_LONG
                            ).show();
                        });
                    }
                });
            }
    );

    cancel.setOnClickListener(
            v -> {

                new android.app.AlertDialog.Builder(this)
                        .setTitle(
                                "Cancelar álbum"
                        )
                        .setMessage(
                                "¿Quieres cancelar todas las canciones de este álbum?"
                        )
                        .setNegativeButton(
                                "No",
                                null
                        )
                        .setPositiveButton(
                                "Cancelar",
                                (dialog, which) -> {

                                    cancel.setEnabled(
                                            false
                                    );

                                    cancel.setText(
                                            "⏳ Cancelando..."
                                    );

                                    executor.execute(() -> {

                                        int success = 0;

                                        for (
                                                ActiveDownloadItem item :
                                                items
                                        ) {

                                            try {

                                                JSONObject result =
                                                        api().cancelDownload(
                                                                item.job
                                                        );

                                                if (
                                                        result.optBoolean(
                                                                "ok",
                                                                false
                                                        )
                                                ) {
                                                    success++;
                                                }

                                            } catch (Exception ignored) {
                                            }
                                        }

                                        final int cancelledCount =
                                                success;

                                        handler.post(() -> {

                                            Toast.makeText(
                                                    this,
                                                    "✕ "
                                                            + cancelledCount
                                                            + " descarga(s) canceladas.",
                                                    Toast.LENGTH_SHORT
                                            ).show();

                                            showDownloads();
                                        });
                                    });
                                }
                        )
                        .show();
            }
    );
}


private void addUnifiedHistoryCard(
        LinearLayout parent,
        JSONObject item) {

    String titleText =
            item.optString(
                    "title",
                    item.optString(
                            "id",
                            "Descarga"
                    )
            );

    String historyType =
            item.optString(
                    "type",
                    "navidrome"
            );

    String historyVideoId =
            item.optString(
                    "id",
                    ""
            );

    String status =
            item.optString(
                    "status",
                    "done"
            );

    String time =
            item.optString(
                    "time",
                    ""
            );

    boolean mobile =
            "mobile".equalsIgnoreCase(
                    historyType
            );

    LinearLayout card =
            new LinearLayout(this);

    card.setOrientation(
            LinearLayout.VERTICAL
    );

    card.setPadding(
            dp(14),
            dp(12),
            dp(14),
            dp(12)
    );

    GradientDrawable cardBackground =
            new GradientDrawable();

    cardBackground.setColor(
            Color.rgb(
                    25,
                    25,
                    30
            )
    );

    cardBackground.setCornerRadius(
            dp(16)
    );

    cardBackground.setStroke(
            dp(1),
            Color.rgb(
                    55,
                    55,
                    65
            )
    );

    card.setBackground(
            cardBackground
    );

    LinearLayout.LayoutParams cardParams =
            new LinearLayout.LayoutParams(
                    -1,
                    -2
            );

    cardParams.setMargins(
            dp(8),
            dp(5),
            dp(8),
            dp(5)
    );

    parent.addView(
            card,
            cardParams
    );

    TextView typeView =
            new TextView(this);

    typeView.setText(
            mobile
                    ? "📱 OFFLINE"
                    : "🎧 NAVIDROME"
    );

    typeView.setTextSize(12);
    typeView.setTextColor(
            mobile
                    ? Color.rgb(
                            130,
                            205,
                            255
                    )
                    : Color.rgb(
                            180,
                            150,
                            255
                    )
    );

    typeView.setTypeface(
            null,
            android.graphics.Typeface.BOLD
    );

    card.addView(
            typeView
    );

    TextView song =
            new TextView(this);

    song.setText(
            "🎵 " + titleText
    );

    song.setTextSize(16);
    song.setTextColor(
            Color.WHITE
    );

    song.setTypeface(
            null,
            android.graphics.Typeface.BOLD
    );

    song.setPadding(
            0,
            dp(5),
            0,
            dp(4)
    );

    card.addView(
            song
    );

    TextView state =
            new TextView(this);

    String stateText;

    if ("done".equalsIgnoreCase(status)) {

        stateText =
                "✓ Completado";

    } else if (
            "cancelled".equalsIgnoreCase(
                    status
            )
    ) {

        stateText =
                "✕ Cancelado";

    } else if (
            "error".equalsIgnoreCase(
                    status
            )
    ) {

        stateText =
                "❌ Error";

    } else {

        stateText =
                status;
    }

    state.setText(
            stateText
                    + (
                    time.isEmpty()
                            ? ""
                            : " · " + time
            )
    );

    state.setTextSize(13);
    state.setTextColor(
            Color.rgb(
                    175,
                    175,
                    185
            )
    );

    card.addView(
            state
    );

    LinearLayout buttons =
            new LinearLayout(this);

    buttons.setOrientation(
            LinearLayout.HORIZONTAL
    );

    buttons.setPadding(
            0,
            dp(9),
            0,
            0
    );

    card.addView(
            buttons,
            new LinearLayout.LayoutParams(
                    -1,
                    dp(50)
            )
    );

    Button retry =
            roundedButton();

    retry.setText(
            "↻ Volver a descargar"
    );

    Button delete =
            roundedButton();

    delete.setText(
            "🗑️ Borrar"
    );

    LinearLayout.LayoutParams buttonParams =
            new LinearLayout.LayoutParams(
                    0,
                    dp(44),
                    1f
            );

    buttonParams.setMargins(
            dp(3),
            0,
            dp(3),
            0
    );

    buttons.addView(
            retry,
            new LinearLayout.LayoutParams(
                    buttonParams
            )
    );

    buttons.addView(
            delete,
            new LinearLayout.LayoutParams(
                    buttonParams
            )
    );

    retry.setOnClickListener(
            v -> {

                if (historyVideoId.isEmpty()) {

                    Toast.makeText(
                            this,
                            "No se puede descargar de nuevo: falta el ID.",
                            Toast.LENGTH_LONG
                    ).show();

                    return;
                }

                retry.setEnabled(
                        false
                );

                retry.setText(
                        "⏳ Preparando..."
                );

                ProgressBar retryProgress =
                        new ProgressBar(
                                this,
                                null,
                                android.R.attr.progressBarStyleHorizontal
                        );

                retryProgress.setMax(
                        100
                );

                retryProgress.setProgress(
                        0
                );

                LinearLayout.LayoutParams retryProgressParams =
                        new LinearLayout.LayoutParams(
                                -1,
                                dp(8)
                        );

                retryProgressParams.setMargins(
                        0,
                        dp(8),
                        0,
                        dp(4)
                );

                TextView retryStatus =
                        new TextView(this);

                retryStatus.setText(
                        "⏳ Preparando nueva descarga..."
                );

                retryStatus.setTextSize(
                        13
                );

                retryStatus.setTextColor(
                        Color.rgb(
                                180,
                                180,
                                190
                        )
                );

                card.addView(
                        retryProgress,
                        retryProgressParams
                );

                card.addView(
                        retryStatus
                );

                DownloadSlot retrySlot =
                        new DownloadSlot(
                                historyVideoId,
                                titleText,
                                retry,
                                retryProgress,
                                retryStatus
                        );

                retrySlot.button.setEnabled(
                        false
                );

                if (mobile) {

                    startMobileDownload(
                            retrySlot
                    );

                } else {

                    startNormalDownload(
                            retrySlot
                    );
                }
            }
    );

    delete.setOnClickListener(
            v -> {

                new android.app.AlertDialog.Builder(this)
                        .setTitle(
                                "Borrar registro"
                        )
                        .setMessage(
                                "¿Quieres borrar este registro del historial?\n\n"
                                        + "La canción descargada NO se eliminará."
                        )
                        .setNegativeButton(
                                "Cancelar",
                                null
                        )
                        .setPositiveButton(
                                "Borrar",
                                (dialog, which) -> {

                                    delete.setEnabled(
                                            false
                                    );

                                    executor.execute(() -> {

                                        try {

                                            JSONObject result =
                                                    api().deleteDownloadHistory(
                                                            item.optString(
                                                                    "job",
                                                                    ""
                                                            )
                                                    );

                                            handler.post(() -> {

                                                Toast.makeText(
                                                        this,
                                                        result.optString(
                                                                "message",
                                                                "Registro eliminado."
                                                        ),
                                                        Toast.LENGTH_SHORT
                                                ).show();

                                                showDownloads();
                                            });

                                        } catch (Exception e) {

                                            handler.post(() -> {

                                                delete.setEnabled(
                                                        true
                                                );

                                                Toast.makeText(
                                                        this,
                                                        "❌ "
                                                                + e.getMessage(),
                                                        Toast.LENGTH_LONG
                                                ).show();
                                            });
                                        }
                                    });
                                }
                        )
                        .show();
            }
    );
}


private void deleteOfflineTrack(
        int index,
        String uriString) {

    try {

        Uri uri =
                Uri.parse(uriString);

        DocumentFile file =
                DocumentFile.fromSingleUri(
                        this,
                        uri
                );

        if (file != null
                && file.exists()) {

            file.delete();
        }

        JSONArray library =
                loadOfflineLibrary();

        JSONArray updated =
                new JSONArray();

        for (int i = 0; i < library.length(); i++) {

            if (i != index) {
                updated.put(
                        library.getJSONObject(i)
                );
            }
        }

        getSharedPreferences(
                "settings",
                MODE_PRIVATE
        )
                .edit()
                .putString(
                        "offline_library",
                        updated.toString()
                )
                .apply();

        Toast.makeText(
                this,
                "🗑 Canción eliminada",
                Toast.LENGTH_SHORT
        ).show();

        showOfflineLibrary();

    } catch (Exception e) {

        Toast.makeText(
                this,
                "❌ No se pudo eliminar: "
                        + e.getMessage(),
                Toast.LENGTH_LONG
        ).show();
    }
}


private void importSpotifyFile(Uri uri) {

    new Thread(() -> {

        try {

            String filename =
                    "playlist.txt";

            android.database.Cursor cursor =
                    getContentResolver().query(
                            uri,
                            new String[]{
                                    android.provider.OpenableColumns.DISPLAY_NAME
                            },
                            null,
                            null,
                            null
                    );

            if (cursor != null) {

                try {

                    if (cursor.moveToFirst()) {

                        int nameIndex =
                                cursor.getColumnIndex(
                                        android.provider.OpenableColumns.DISPLAY_NAME
                                );

                        if (nameIndex >= 0) {

                            String detectedName =
                                    cursor.getString(nameIndex);

                            if (detectedName != null &&
                                    !detectedName.trim().isEmpty()) {

                                filename =
                                        detectedName.trim();
                            }
                        }
                    }

                } finally {

                    cursor.close();
                }
            }


            java.io.InputStream input =
                    getContentResolver()
                    .openInputStream(uri);

            java.io.BufferedReader reader =
                    new java.io.BufferedReader(
                    new java.io.InputStreamReader(input)
            );

            StringBuilder text =
                    new StringBuilder();

            String line;

            while ((line = reader.readLine()) != null) {
                text.append(line).append("\n");
            }

            reader.close();


            String response =
                    api().importSpotifyFile(
                    filename,
                    text.toString()
            );

            JSONObject json =
                    new JSONObject(response);


            runOnUiThread(() -> {

                Toast.makeText(
                        this,
                        "✅ Playlist importada",
                        Toast.LENGTH_SHORT
                ).show();

                loadSpotifyLists();

            });


        } catch(Exception e) {

            runOnUiThread(() -> {

                Toast.makeText(
                        this,
                        "❌ Error importando: "
                        + e.getMessage(),
                        Toast.LENGTH_LONG
                ).show();

            });
        }

    }).start();
}


}
