package com.musicdownloader.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ApiClient {

    private final String baseUrl;

    public ApiClient(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    private String get(String path) throws Exception {
        URL url = new URL(baseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();

            if (code < 200 || code >= 300) {
                throw new Exception("Servidor respondió HTTP " + code);
            }

            return readResponse(conn.getInputStream());

        } finally {
            conn.disconnect();
        }
    }

    private String post(String path, JSONObject body) throws Exception {
        URL url = new URL(baseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
            );

            byte[] data =
                    body.toString()
                            .getBytes(StandardCharsets.UTF_8);

            try (OutputStream output =
                         conn.getOutputStream()) {
                output.write(data);
            }

            int code = conn.getResponseCode();

            InputStream stream;

            if (code >= 200 && code < 300) {
                stream = conn.getInputStream();
            } else if (conn.getErrorStream() != null) {
                stream = conn.getErrorStream();
            } else {
                throw new Exception(
                        "Servidor respondió HTTP " + code
                );
            }

            String response =
                    readResponse(stream);

            if (code < 200 || code >= 300) {

                String message = response;

                try {
                    JSONObject error =
                            new JSONObject(response);

                    message = error.optString(
                            "error",
                            error.optString(
                                    "message",
                                    response
                            )
                    );

                } catch (Exception ignored) {
                }

                throw new Exception(
                        "HTTP " + code + ": " + message
                );
            }

            return response;

        } finally {
            conn.disconnect();
        }
    }

    private String readResponse(
            InputStream input) throws Exception {

        BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                input,
                                StandardCharsets.UTF_8
                        )
                );

        StringBuilder result =
                new StringBuilder();

        String line;

        while ((line = reader.readLine()) != null) {
            result.append(line);
        }

        reader.close();

        return result.toString();
    }

    public List<Song> searchSongs(
            String query) throws Exception {

        String encoded =
                URLEncoder.encode(query, "UTF-8");

        JSONObject json =
                new JSONObject(
                        get("/api/search?q=" + encoded)
                );

        JSONArray results =
                json.optJSONArray("results");

        List<Song> songs =
                new ArrayList<>();

        if (results == null) {
            return songs;
        }

        for (int i = 0; i < results.length(); i++) {

            JSONObject item =
                    results.getJSONObject(i);

            Song song = new Song();

            song.id =
                    item.optString("id");

            song.title =
                    item.optString("title");

            song.channel =
                    item.optString("channel");

            song.artist =
                    item.optString("artist");

            song.thumbnail =
                    item.optString("thumbnail");

            song.duration =
                    item.optInt("duration", 0);

            songs.add(song);
        }

        return songs;
    }

    public List<Album> searchAlbums(
            String query) throws Exception {

        String encoded =
                URLEncoder.encode(query, "UTF-8");

        JSONObject json =
                new JSONObject(
                        get("/api/album?q=" + encoded)
                );

        JSONArray results =
                json.optJSONArray("albums");

        List<Album> albums =
                new ArrayList<>();

        if (results == null) {
            return albums;
        }

        for (int i = 0; i < results.length(); i++) {

            JSONObject item =
                    results.getJSONObject(i);

            Album album = new Album();

            album.id =
                    item.optString("id");

            album.title =
                    item.optString("title");

            album.artist =
                    item.optString("artist");

            album.date =
                    item.optString("date");

            album.type =
                    item.optString("type");

            albums.add(album);
        }

        return albums;
    }

    public Album getAlbum(
            String id) throws Exception {

        String encoded =
                URLEncoder.encode(id, "UTF-8");

        JSONObject json =
                new JSONObject(
                        get("/api/album?id=" + encoded)
                );

        Album album = new Album();

        album.id =
                json.optString(
                        "release_group_id",
                        id
                );

        album.title =
                json.optString("title");

        album.artist =
                json.optString("artist");

        JSONArray tracks =
                json.optJSONArray("tracks");

        if (tracks != null) {

            for (int i = 0;
                 i < tracks.length();
                 i++) {

                JSONObject item =
                        tracks.getJSONObject(i);

                Song song = new Song();

                song.number =
                        item.optInt(
                                "number",
                                i + 1
                        );

                song.title =
                        item.optString("title");

                song.channel =
                        item.optString("artist");

                song.artist =
                        item.optString("artist");

                song.album =
                        item.optString("album");

                song.id =
                        item.optString("id");

                song.videoId =
                        item.optString("video_id");

                song.thumbnail =
                        item.optString("thumbnail");

                song.duration =
                        item.optInt(
                                "duration",
                                0
                        );

                album.tracks.add(song);
            }
        }

        return album;
    }

    public Song resolveAlbumTrack(
            String artist,
            String title) throws Exception {

        String query =
                artist + " - " + title;

        String encoded =
                URLEncoder.encode(
                        query,
                        "UTF-8"
                );

        JSONObject json =
                new JSONObject(
                        get(
                                "/api/album-track?q="
                                        + encoded
                        )
                );

        Song song = new Song();

        song.id =
                json.optString("id");

        song.videoId =
                song.id;

        song.title =
                json.optString("title");

        song.thumbnail =
                json.optString("thumbnail");

        song.duration =
                json.optInt(
                        "duration",
                        0
                );

        song.channel =
                json.optString("channel");

        song.artist =
                artist;

        return song;
    }

    public String getPreviewUrl(
            String videoId) throws Exception {

        String encoded =
                URLEncoder.encode(
                        videoId,
                        "UTF-8"
                );

        JSONObject json =
                new JSONObject(
                        get(
                                "/api/preview?id="
                                        + encoded
                        )
                );

        String url =
                json.optString("url");

        if (url.isEmpty()) {
            throw new Exception(
                    "El servidor no devolvió URL de audio"
            );
        }

        return url;
    }

    /*
     * ============================================================
     * SPOTIFY
     * ============================================================
     */

    public List<SpotifyPlaylist> getSpotifyLists()
            throws Exception {

        JSONObject json =
                new JSONObject(
                        get("/api/spotify/lists")
                );

        JSONArray array =
                firstArray(
                        json,
                        "lists",
                        "playlists",
                        "items",
                        "results"
                );

        List<SpotifyPlaylist> lists =
                new ArrayList<>();

        if (array == null) {
            return lists;
        }

        for (int i = 0;
             i < array.length();
             i++) {

            JSONObject item =
                    array.optJSONObject(i);

            if (item == null) {
                continue;
            }

            SpotifyPlaylist playlist =
                    new SpotifyPlaylist();

            playlist.id =
                    firstString(
                            item,
                            "id",
                            "playlist_id",
                            "spotify_id"
                    );

            playlist.name =
                    firstString(
                            item,
                            "name",
                            "title",
                            "playlist_name"
                    );

            playlist.url =
                    firstString(
                            item,
                            "url",
                            "spotify_url"
                    );

            playlist.trackCount =
                    firstInt(
                            item,
                            "track_count",
                            "tracks_count",
                            "count",
                            "total"
                    );

            lists.add(playlist);
        }

        return lists;
    }

    public SpotifyPlaylist loadSpotifyList(
            String id) throws Exception {

        JSONObject body = new JSONObject();

        body.put(
                "id",
                id
        );

        JSONObject json =
                new JSONObject(
                        post(
                                "/api/spotify/list/load",
                                body
                        )
                );

        return parseSpotifyPlaylist(json);
    }

    public void deleteSpotifyList(
            String id) throws Exception {

        JSONObject body =
                new JSONObject();

        body.put("id", id);

        post(
                "/api/spotify/list/delete",
                body
        );
    }

    public SpotifyPlaylist importSpotifyPlaylist(
            String url) throws Exception {

        JSONObject body =
                new JSONObject();

        body.put("url", url);
        body.put("spotify_url", url);

        String response =
                post(
                        "/api/spotify/import",
                        body
                );

        JSONObject json =
                new JSONObject(response);

        return parseSpotifyPlaylist(json);
    }


    public JSONObject download(
            String videoId,
            String title) throws Exception {

        return download(
                videoId,
                title,
                null,
                null,
                0,
                0
        );
    }

    public JSONObject download(
            String videoId,
            String title,
            String albumGroup,
            String albumTitle,
            int albumTrackIndex,
            int albumTrackTotal) throws Exception {

        JSONObject body =
                new JSONObject();

        body.put("id", videoId);

        if (title != null && !title.isEmpty()) {
            body.put("title", title);
        }

        if (albumGroup != null && !albumGroup.isEmpty()) {
            body.put("album_group", albumGroup);
        }

        if (albumTitle != null && !albumTitle.isEmpty()) {
            body.put("album_title", albumTitle);
        }

        if (albumTrackIndex > 0) {
            body.put(
                    "album_track_index",
                    albumTrackIndex
            );
        }

        if (albumTrackTotal > 0) {
            body.put(
                    "album_track_total",
                    albumTrackTotal
            );
        }

        String response =
                post(
                        "/api/download",
                        body
                );

        return new JSONObject(response);
    }

    public JSONObject downloadMobile(
            String videoId,
            String title) throws Exception {

        return downloadMobile(
                videoId,
                title,
                null,
                null,
                0,
                0,
                "mp3"
        );
    }

    public JSONObject downloadMobile(
            String videoId,
            String title,
            String albumGroup,
            String albumTitle,
            int albumTrackIndex,
            int albumTrackTotal) throws Exception {

        return downloadMobile(
                videoId,
                title,
                albumGroup,
                albumTitle,
                albumTrackIndex,
                albumTrackTotal,
                "mp3"
        );
    }

    public JSONObject downloadMobile(
            String videoId,
            String title,
            String albumGroup,
            String albumTitle,
            int albumTrackIndex,
            int albumTrackTotal,
            String format) throws Exception {

        JSONObject body =
                new JSONObject();

        body.put(
                "id",
                videoId
        );

        if (title != null && !title.isEmpty()) {

            body.put(
                    "title",
                    title
            );
        }

        if (albumGroup != null &&
                !albumGroup.isEmpty()) {

            body.put(
                    "album_group",
                    albumGroup
            );
        }

        if (albumTitle != null &&
                !albumTitle.isEmpty()) {

            body.put(
                    "album_title",
                    albumTitle
            );
        }

        if (albumTrackIndex > 0) {

            body.put(
                    "album_track_index",
                    albumTrackIndex
            );
        }

        if (albumTrackTotal > 0) {

            body.put(
                    "album_track_total",
                    albumTrackTotal
            );
        }

        if (!"opus".equalsIgnoreCase(format)) {
            format = "mp3";
        } else {
            format = "opus";
        }

        body.put(
                "format",
                format
        );

        String response =
                post(
                        "/api/download-mobile",
                        body
                );

        return new JSONObject(response);
    }

    public String getMobileDownloadUrl(
            String job) {

        return baseUrl
                + "/api/download-mobile-file?id="
                + job;
    }


    public JSONObject spotifyDownload(
            SpotifyTrack track) throws Exception {

        JSONObject trackObject =
                spotifyTrackToJson(track);

        JSONObject body =
                new JSONObject();

        JSONArray tracks =
                new JSONArray();

        tracks.put(trackObject);

        body.put(
                "tracks",
                tracks
        );

        String response =
                post(
                        "/api/spotify/download",
                        body
                );

        return new JSONObject(response);
    }

    public JSONObject spotifyDownloadPlaylist(
            SpotifyPlaylist playlist)
            throws Exception {

        JSONObject body =
                new JSONObject();

        body.put(
                "playlist",
                spotifyPlaylistToJson(
                        playlist
                )
        );

        body.put(
                "tracks",
                spotifyTracksToJson(
                        playlist.tracks
                )
        );

        String response =
                post(
                        "/api/spotify/download",
                        body
                );

        return new JSONObject(response);
    }

    public JSONObject getDownloadStatus(
            String job) throws Exception {

        String response =
                get(
                        "/api/status?id="
                                + job
                );

        return new JSONObject(response);
    }


    public JSONObject pauseDownload(
            String job) throws Exception {

        String response =
                get(
                        "/api/pause?id="
                                + job
                );

        return new JSONObject(response);
    }


    public JSONObject resumeDownload(
            String job) throws Exception {

        String response =
                get(
                        "/api/resume?id="
                                + job
                );

        return new JSONObject(response);
    }


    public JSONObject cancelDownload(
            String job) throws Exception {

        String response =
                get(
                        "/api/cancel?id="
                                + job
                );

        return new JSONObject(response);
    }

    private JSONObject spotifyTrackToJson(
            SpotifyTrack track)
            throws Exception {

        JSONObject json =
                new JSONObject();

        json.put(
                "title",
                track.title
        );

        json.put(
                "artist",
                track.artist
        );

        if (!track.album.isEmpty()) {
            json.put(
                    "album",
                    track.album
            );
        }

        if (!track.url.isEmpty()) {
            json.put(
                    "url",
                    track.url
            );
        }

        if (!track.id.isEmpty()) {

            json.put(
                    "id",
                    track.id
            );

            json.put(
                    "spotify_id",
                    track.id
            );
        }

        if (track.duration > 0) {
            json.put(
                    "duration",
                    track.duration
            );
        }

        return json;
    }

    private JSONArray spotifyTracksToJson(
            List<SpotifyTrack> tracks)
            throws Exception {

        JSONArray array =
                new JSONArray();

        for (
                SpotifyTrack track :
                tracks
        ) {

            array.put(
                    spotifyTrackToJson(track)
            );
        }

        return array;
    }

    private JSONObject spotifyPlaylistToJson(
            SpotifyPlaylist playlist)
            throws Exception {

        JSONObject json =
                new JSONObject();

        json.put(
                "id",
                playlist.id
        );

        json.put(
                "name",
                playlist.name
        );

        if (!playlist.url.isEmpty()) {

            json.put(
                    "url",
                    playlist.url
            );
        }

        return json;
    }

    private SpotifyPlaylist parseSpotifyPlaylist(
            JSONObject json)
            throws Exception {

        JSONObject source = json;

        JSONObject nested =
                json.optJSONObject("playlist");

        if (nested != null) {
            source = nested;
        }

        SpotifyPlaylist playlist =
                new SpotifyPlaylist();

        playlist.id =
                firstString(
                        source,
                        "id",
                        "playlist_id",
                        "spotify_id"
                );

        playlist.name =
                firstString(
                        source,
                        "name",
                        "title",
                        "playlist_name"
                );

        playlist.url =
                firstString(
                        source,
                        "url",
                        "spotify_url"
                );

        playlist.trackCount =
                firstInt(
                        source,
                        "track_count",
                        "tracks_count",
                        "count",
                        "total"
                );

        JSONArray tracks =
                firstArray(
                        source,
                        "tracks",
                        "items",
                        "songs",
                        "results"
                );

        if (tracks == null) {

            tracks =
                    firstArray(
                            json,
                            "tracks",
                            "items",
                            "songs",
                            "results"
                    );
        }

        if (tracks != null) {

            for (int i = 0;
                 i < tracks.length();
                 i++) {

                JSONObject item =
                        tracks.optJSONObject(i);

                if (item == null) {
                    continue;
                }

                JSONObject trackObject =
                        item;

                JSONObject nestedTrack =
                        item.optJSONObject(
                                "track"
                        );

                if (nestedTrack != null) {
                    trackObject =
                            nestedTrack;
                }

                SpotifyTrack track =
                        new SpotifyTrack();

                track.id =
                        firstString(
                                trackObject,
                                "id",
                                "spotify_id"
                        );

                track.title =
                        firstString(
                                trackObject,
                                "title",
                                "name"
                        );

                track.artist =
                        firstString(
                                trackObject,
                                "artist",
                                "artists",
                                "author"
                        );

                track.album =
                        firstString(
                                trackObject,
                                "album",
                                "album_name"
                        );

                track.url =
                        firstString(
                                trackObject,
                                "url",
                                "spotify_url",
                                "external_url"
                        );

                track.thumbnail =
                        firstString(
                                trackObject,
                                "thumbnail",
                                "image",
                                "cover",
                                "artwork"
                        );

                track.duration =
                        firstInt(
                                trackObject,
                                "duration",
                                "duration_ms"
                        );

                if (track.duration > 10000) {
                    track.duration =
                            track.duration / 1000;
                }

                playlist.tracks.add(track);
            }
        }

        playlist.trackCount =
                playlist.tracks.size();

        return playlist;
    }

    private JSONArray firstArray(
            JSONObject json,
            String... keys) {

        for (String key : keys) {

            JSONArray array =
                    json.optJSONArray(key);

            if (array != null) {
                return array;
            }
        }

        return null;
    }

    private String firstString(
            JSONObject json,
            String... keys) {

        for (String key : keys) {

            Object value =
                    json.opt(key);

            if (value == null) {
                continue;
            }

            if (value instanceof JSONArray) {

                JSONArray array =
                        (JSONArray) value;

                if (array.length() > 0) {

                    JSONObject first =
                            array.optJSONObject(0);

                    if (first != null) {

                        String name =
                                first.optString(
                                        "name"
                                );

                        if (!name.isEmpty()) {
                            return name;
                        }
                    }
                }

            } else {

                String text =
                        String.valueOf(value);

                if (
                        !text.equals("null") &&
                        !text.isEmpty()
                ) {
                    return text;
                }
            }
        }

        return "";
    }

    private int firstInt(
            JSONObject json,
            String... keys) {

        for (String key : keys) {

            if (!json.has(key)) {
                continue;
            }

            Object value =
                    json.opt(key);

            if (value instanceof Number) {

                return ((Number) value).intValue();
            }

            try {

                return Integer.parseInt(
                        String.valueOf(value)
                );

            } catch (Exception ignored) {
            }
        }

        return 0;
    }

    public static class Song {

        public int number;
        public String id = "";
        public String videoId = "";
        public String title = "";
        public String artist = "";
        public String channel = "";
        public String album = "";
        public String thumbnail = "";
        public int duration;
    }

    public static class Album {

        public String id = "";
        public String title = "";
        public String artist = "";
        public String date = "";
        public String type = "";

        public List<Song> tracks =
                new ArrayList<>();
    }

    public static class SpotifyPlaylist {

        public String id = "";
        public String name = "";
        public String url = "";
        public int trackCount = 0;

        public List<SpotifyTrack> tracks =
                new ArrayList<>();
    }

    public static class SpotifyTrack {

        public String id = "";
        public String title = "";
        public String artist = "";
        public String album = "";
        public String url = "";
        public String thumbnail = "";
        public int duration = 0;
    }

public JSONObject getQueue() throws Exception {

    String response =
            get("/api/queue");

    return new JSONObject(response);
}


public JSONObject getMobileDownloads() throws Exception {

    String response =
            get("/api/downloads");

    return new JSONObject(response);
}


public JSONObject deleteDownloadHistory(String job) throws Exception {

    String response =
            get(
                    "/api/downloads/delete?id="
                            + java.net.URLEncoder.encode(
                                    job,
                                    "UTF-8"
                            )
            );

    return new JSONObject(response);
}


public JSONObject resetMobileDownloads() throws Exception {

    String response =
            post(
                    "/api/downloads/reset",
                    new JSONObject()
            );

    return new JSONObject(response);
}


public String importSpotifyFile(
        String filename,
        String content) throws Exception {

    JSONObject body =
            new JSONObject();

    body.put(
            "filename",
            filename
    );

    body.put(
            "content",
            content
    );

    return post(
            "/api/spotify/import",
            body
    );
}


}
