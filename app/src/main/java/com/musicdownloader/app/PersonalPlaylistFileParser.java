package com.musicdownloader.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Importación LOCAL a Mis Listas; no crea nada en listas Spotify del servidor. */
// PERSONAL_IMPORT_MY_LISTS_V1
public final class PersonalPlaylistFileParser {
    private PersonalPlaylistFileParser() { }
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    public static final int MAX_TRACKS = 2000;

    public static final class Result {
        public final JSONArray tracks;
        public final String suggestedName;
        public final int ignored;
        Result(JSONArray tracks, String suggestedName, int ignored) {
            this.tracks = tracks; this.suggestedName = suggestedName; this.ignored = ignored;
        }
    }

    public static Result parse(InputStream input, String fileName) throws Exception {
        if (input == null) throw new Exception("No se pudo abrir el archivo");
        String text;
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > MAX_BYTES)
                    throw new Exception("El archivo supera 4 MB; importa uno más pequeño");
                out.write(buffer, 0, read);
            }
            text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (text.trim().isEmpty()) throw new Exception("El archivo está vacío");
        String base = fileName == null ? "Lista importada" : fileName.replaceAll("(?i)\\.(csv|txt|json)$", "").trim();
        if (base.isEmpty()) base = "Lista importada";
        String ext = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (ext.endsWith(".json")) return readJson(text, base);
        if (ext.endsWith(".csv")) return readCsv(text, base);
        if (ext.endsWith(".txt")) return readTxt(text, base);
        // Algunos proveedores eliminan la extensión del nombre original.
        if (text.trim().startsWith("{") || text.trim().startsWith("[")) return readJson(text, base);
        if (text.contains(",") || text.contains(";")) return readCsv(text, base);
        return readTxt(text, base);
    }

    private static Result readJson(String text, String name) throws Exception {
        Object value = new org.json.JSONTokener(text).nextValue();
        JSONArray source;
        if (value instanceof JSONArray) source = (JSONArray) value;
        else if (value instanceof JSONObject) {
            JSONObject root = (JSONObject) value;
            String possible = first(root, "name", "title", "playlist_name");
            if (!possible.isEmpty()) name = possible;
            source = root.optJSONArray("tracks");
            if (source == null) source = root.optJSONArray("items");
            if (source == null) source = root.optJSONArray("songs");
            if (source == null) source = root.optJSONArray("results");
            if (source == null) source = root.optJSONArray("data");
        } else throw new Exception("JSON sin una lista de canciones");
        if (source == null) throw new Exception("El JSON no tiene canciones (tracks/items/songs)");
        JSONArray tracks = new JSONArray(); int ignored = 0;
        for (int i = 0; i < source.length(); i++) {
            if (tracks.length() >= MAX_TRACKS) throw new Exception("Máximo 2000 canciones por importación");
            JSONObject song = source.optJSONObject(i);
            if (song == null) { ignored++; continue; }
            JSONObject nested = song.optJSONObject("track");
            if (nested != null) song = nested;
            String title = first(song, "title", "name", "track_name", "Track Name", "Nombre de la canción");
            if (title.isEmpty()) { ignored++; continue; }
            String artist = first(song, "artist", "artist_name", "Artist Name", "author", "Nombre(s) del artista");
            if (artist.isEmpty()) artist = artistValue(song.opt("artists"));
            String album = first(song, "album_name", "Album Name", "Nombre del álbum");
            if (album.isEmpty()) {
                Object albumValue = song.opt("album");
                album = albumValue instanceof JSONObject ? ((JSONObject) albumValue).optString("name", "")
                        : albumValue instanceof String ? (String) albumValue : "";
            }
            int duration = seconds(song.opt("duration"), false);
            if (duration == 0) duration = seconds(song.opt("duration_ms"), true);
            JSONObject item = create(title, artist, album, duration);
            String thumbnail = first(song, "thumbnail", "image", "cover", "artwork", "URL de la imagen del álbum");
            JSONObject albumObject = song.optJSONObject("album");
            if (thumbnail.isEmpty() && albumObject != null) {
                JSONArray images = albumObject.optJSONArray("images");
                if (images != null && images.length() > 0 && images.optJSONObject(0) != null)
                    thumbnail = images.optJSONObject(0).optString("url", "");
            }
            if (!thumbnail.isEmpty()) item.put("thumbnail", thumbnail);
            String spotifyId = first(song, "spotify_id", "id", "URI de la canción");
            if (!spotifyId.isEmpty()) item.put("spotify_id", spotifyId);
            // Importaciones de Spotify no son rutas de ficheros Offline.
            tracks.put(item);
        }
        return checked(tracks, name, ignored);
    }

    private static Result readTxt(String text, String name) throws Exception {
        JSONArray tracks = new JSONArray(); int ignored = 0;
        for (String row : text.split("\\r?\\n", -1)) {
            row = row.trim();
            if (row.isEmpty() || row.startsWith("#")) { ignored++; continue; }
            if (tracks.length() >= MAX_TRACKS) throw new Exception("Máximo 2000 canciones por importación");
            int dash = row.indexOf(" - ");
            String artist = dash > 0 ? row.substring(0, dash).trim() : "";
            String title = dash > 0 ? row.substring(dash + 3).trim() : row;
            if (!title.isEmpty()) tracks.put(create(title, artist, "", 0));
        }
        return checked(tracks, name, ignored);
    }

    private static Result readCsv(String text, String name) throws Exception {
        char sep = separator(text);
        List<List<String>> rows = csvRows(text, sep);
        if (rows.size() < 2) throw new Exception("CSV sin canciones");
        List<String> columns = rows.get(0);
        int titleColumn = column(columns, "nombre de la cancion", "track name", "track", "title", "song", "name", "titulo", "cancion");
        if (titleColumn < 0) throw new Exception("CSV sin columna de título de canción");
        int artistColumn = column(columns, "nombre s del artista", "artist name", "artist", "artists", "artista", "nombre del artista");
        int albumColumn = column(columns, "nombre del album", "album name", "album", "album title");
        int durationColumn = column(columns, "duracion de la cancion ms", "duration ms", "duration_ms", "duration", "duracion");
        int coverColumn = column(columns, "url de la imagen del album", "thumbnail", "cover", "image", "artwork");
        int idColumn = column(columns, "uri de la cancion", "track uri", "spotify id", "spotify_id");
        JSONArray tracks = new JSONArray(); int ignored = 0;
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String title = cell(row, titleColumn);
            if (title.isEmpty()) { ignored++; continue; }
            if (tracks.length() >= MAX_TRACKS) throw new Exception("Máximo 2000 canciones por importación");
            String durationHeader = durationColumn < 0 ? "" : normalize(columns.get(durationColumn));
            boolean millis = durationHeader.contains("ms") || durationHeader.contains("milisegundo");
            JSONObject item = create(title, cell(row, artistColumn), cell(row, albumColumn),
                    seconds(cell(row, durationColumn), millis));
            String cover = cell(row, coverColumn);
            if (!cover.isEmpty()) item.put("thumbnail", cover);
            String id = cell(row, idColumn);
            if (!id.isEmpty()) item.put("spotify_id", id);
            tracks.put(item);
        }
        return checked(tracks, name, ignored);
    }

    private static Result checked(JSONArray tracks, String name, int ignored) throws Exception {
        if (tracks.length() == 0) throw new Exception("No se encontraron canciones válidas");
        return new Result(tracks, name, ignored);
    }
    private static JSONObject create(String title, String artist, String album, int duration) throws Exception {
        JSONObject item = new JSONObject();
        item.put("title", title == null ? "" : title.trim());
        item.put("artist", artist == null ? "" : artist.trim());
        item.put("album", album == null ? "" : album.trim());
        item.put("duration", Math.max(0, duration));
        item.put("uri", "");
        return item;
    }
    private static int seconds(Object raw, boolean millis) {
        if (raw == null || JSONObject.NULL.equals(raw)) return 0;
        String s = String.valueOf(raw).trim().replace(',', '.');
        if (s.isEmpty()) return 0;
        try {
            if (s.contains(":")) {
                String[] p = s.split(":");
                int total = 0;
                for (String x : p) total = total * 60 + Integer.parseInt(x.trim());
                return Math.max(0, total);
            }
            long value = Math.round(Double.parseDouble(s));
            if (millis) value /= 1000;
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
        } catch (Exception ignored) { return 0; }
    }
    private static String first(JSONObject obj, String... keys) {
        for (String key : keys) {
            Object value = obj.opt(key);
            if (value instanceof String && !((String) value).trim().isEmpty())
                return ((String) value).trim();
            if (value instanceof Number) return String.valueOf(value);
        }
        return "";
    }
    private static String artistValue(Object obj) {
        if (obj instanceof String) return ((String) obj).trim();
        if (!(obj instanceof JSONArray)) return "";
        JSONArray array = (JSONArray) obj; StringBuilder s = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            Object a = array.opt(i);
            String name = a instanceof JSONObject ? ((JSONObject) a).optString("name", "")
                    : a instanceof String ? (String) a : "";
            if (name.isEmpty()) continue;
            if (s.length() > 0) s.append(", ");
            s.append(name);
        }
        return s.toString();
    }
    private static String normalize(String s) {
        String a = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return a.replaceAll("[^a-z0-9]+", " ").trim();
    }
    private static int column(List<String> columns, String... aliases) {
        for (String alias : aliases) {
            String expected = normalize(alias);
            for (int i = 0; i < columns.size(); i++)
                if (normalize(columns.get(i)).equals(expected)) return i;
        }
        return -1;
    }
    private static String cell(List<String> row, int index) {
        return index < 0 || index >= row.size() ? "" : row.get(index).trim();
    }
    private static char separator(String text) {
        int commas = 0, semis = 0; boolean quote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                if (quote && i + 1 < text.length() && text.charAt(i + 1) == '"') i++;
                else quote = !quote;
            } else if (!quote && (c == '\n' || c == '\r')) break;
            else if (!quote && c == ',') commas++;
            else if (!quote && c == ';') semis++;
        }
        return semis > commas ? ';' : ',';
    }
    private static List<List<String>> csvRows(String text, char separator) throws Exception {
        List<List<String>> rows = new ArrayList<>(); List<String> row = new ArrayList<>();
        StringBuilder item = new StringBuilder(); boolean quote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                if (quote && i + 1 < text.length() && text.charAt(i + 1) == '"') { item.append('"'); i++; }
                else quote = !quote;
            } else if (c == separator && !quote) {
                row.add(item.toString()); item.setLength(0);
            } else if ((c == '\r' || c == '\n') && !quote) {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(item.toString()); item.setLength(0);
                if (!(row.size() == 1 && row.get(0).trim().isEmpty())) rows.add(row);
                row = new ArrayList<>();
                if (rows.size() > MAX_TRACKS + 300) throw new Exception("CSV demasiado grande");
            } else item.append(c);
        }
        if (quote) throw new Exception("CSV con comillas sin cerrar");
        row.add(item.toString());
        if (!(row.size() == 1 && row.get(0).trim().isEmpty())) rows.add(row);
        return rows;
    }
}
