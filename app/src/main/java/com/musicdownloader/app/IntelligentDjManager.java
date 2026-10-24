package com.musicdownloader.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * DJ_INTELIGENTE_V1. Solo descubre candidatos. No toca Media3, colas,
 * favoritos, descargas, listas ni el estado del buscador habitual.
 */
public final class IntelligentDjManager {
    private IntelligentDjManager() { }

    public enum Mode { RELATED, VARIED, SURPRISE }

    public interface Searcher {
        List<ApiClient.Song> search(String query, int limit) throws Exception;
        List<String> similar(String artist) throws Exception;
    }

    public interface Progress {
        // Progreso y resultados acumulados en la MISMA entrega.
        // Nunca se incluyen más canciones que las solicitadas.
        void update(int queriedArtists, int totalArtists, int found,
                List<ApiClient.Song> availableSongs);
    }

    public interface Cancellation {
        boolean cancelled();
    }

    // Solo puntos de partida de diferentes estilos. Las canciones siempre
    // se obtienen mediante el buscador EXISTENTE de Music Downloader.
    private static final String[][] STYLE_SEEDS = {
        {"Pink Floyd", "Dire Straits", "Fleetwood Mac", "The Rolling Stones", "Queen", "The Beatles"},
        {"Rosalía", "Estopa", "Alejandro Sanz", "Lola Índigo", "Amaral", "Vetusta Morla"},
        {"Daft Punk", "The Chemical Brothers", "Moby", "Kraftwerk", "Avicii", "Fatboy Slim"},
        {"Miles Davis", "Nina Simone", "John Coltrane", "Ella Fitzgerald", "Herbie Hancock"},
        {"Bob Marley", "Burning Spear", "Manu Chao", "Toots and the Maytals", "UB40"},
        {"Sade", "Stevie Wonder", "Aretha Franklin", "Marvin Gaye", "Amy Winehouse"},
        {"Metallica", "Iron Maiden", "Black Sabbath", "AC/DC", "Nirvana"},
        {"Juan Luis Guerra", "Celia Cruz", "Héctor Lavoe", "Marc Anthony", "Buena Vista Social Club"},
        {"Ludovico Einaudi", "Yo-Yo Ma", "Claude Debussy", "Max Richter", "Hans Zimmer"},
        {"Coldplay", "Radiohead", "Muse", "Arctic Monkeys", "The Killers"},
        {"Camarón de la Isla", "Paco de Lucía", "Niña Pastori", "Ketama", "Tomatito"},
        {"Björk", "Massive Attack", "Portishead", "Gorillaz", "Air"},
        {"Tina Turner", "Whitney Houston", "Madonna", "David Bowie", "Michael Jackson"},
        {"Rubén Blades", "Bomba Estéreo", "Natalia Lafourcade", "Jorge Drexler", "Carlos Vives"}
    };

    public static String label(Mode mode) {
        switch (mode) {
            case RELATED: return "Artistas relacionados";
            case VARIED: return "Música muy variada";
            default: return "Sorpréndeme";
        }
    }

    // Limites por artista de la sesion (no afectan al buscador normal).
    private static final int RELATED_LIMIT = 2;
    private static final int VARIED_LIMIT = 2;  // Primero una de cada artista.
    private static final int SURPRISE_LIMIT = 1;

    public static List<ApiClient.Song> generate(
            Mode mode, String artist, int requested,
            Searcher source, Progress progress, Cancellation cancellation) throws Exception {
        if (source == null) throw new IllegalArgumentException("Buscador no disponible");
        requested = Math.max(1, Math.min(100, requested));
        check(cancellation);

        Random random = new Random(System.nanoTime());
        List<String> artists = djArtists(mode, artist, requested, source, random, cancellation);
        int quota = mode == Mode.RELATED ? RELATED_LIMIT
                : mode == Mode.VARIED ? VARIED_LIMIT : SURPRISE_LIMIT;

        // Primera vuelta: UNA cancion por artista, para que la sesion sea
        // variada desde sus primeros resultados visibles.
        List<List<ApiClient.Song>> groups = new ArrayList<>();
        List<ApiClient.Song> output = new ArrayList<>();
        HashSet<String> ids = new HashSet<>();
        HashSet<String> titles = new HashSet<>();
        int[] selected = new int[artists.size()];
        int scanned = 0;
        for (int i = 0; i < artists.size(); i++) {
            check(cancellation);
            List<ApiClient.Song> group = new ArrayList<>();
            try {
                List<ApiClient.Song> found = source.search(artists.get(i), 10);
                if (found != null) group.addAll(found);
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception ignored) {
                // Si falla un artista, probar los siguientes sin abortar.
            }
            groups.add(group);
            selected[i] += appendUnique(output, ids, titles, group, 1, requested);
            scanned++;
            publish(progress, scanned, artists.size(), output);
            if (output.size() >= requested) return output;
        }

        // Segunda vuelta: solo RELATED y VARIED pueden repetir un artista,
        // y NUNCA mas de dos canciones. No hay relleno ilimitado.
        if (quota > 1 && output.size() < requested) {
            for (int i = 0; i < groups.size() && output.size() < requested; i++) {
                check(cancellation);
                int remaining = quota - selected[i];
                if (remaining <= 0) continue;
                selected[i] += appendUnique(output, ids, titles,
                        groups.get(i), remaining, requested);
                publish(progress, scanned, artists.size(), output);
            }
        }
        // Si no existen suficientes artistas/resultados, devolver menos
        // canciones antes que repetir excesivamente el mismo cantante.
        return output;
    }

    private static List<String> djArtists(Mode mode, String artist, int requested,
            Searcher source, Random random, Cancellation cancellation) throws Exception {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (mode == Mode.SURPRISE) {
            // Recorrer generos alternados: rock, pop espanol, jazz, electronica,
            // flamenco, latina, etc. No depende del artista escrito.
            List<List<String>> genres = new ArrayList<>();
            for (String[] style : STYLE_SEEDS) {
                List<String> options = new ArrayList<>();
                Collections.addAll(options, style);
                Collections.shuffle(options, random);
                genres.add(options);
            }
            int desired = Math.min(100, requested + Math.max(4, requested / 4));
            for (int round = 0; names.size() < desired; round++) {
                check(cancellation);
                List<Integer> genreOrder = new ArrayList<>();
                for (int j = 0; j < genres.size(); j++) {
                    if (round < genres.get(j).size()) genreOrder.add(j);
                }
                if (genreOrder.isEmpty()) break;
                Collections.shuffle(genreOrder, random);
                for (int g : genreOrder) {
                    if (names.size() >= desired) break;
                    addArtist(names, genres.get(g).get(round));
                }
            }
            // Para sesiones excepcionalmente grandes, mas candidatos
            // aleatorios a traves del Last.fm ya disponible.
            if (names.size() < requested && !names.isEmpty()) {
                List<String> seeds = new ArrayList<>(names);
                Collections.shuffle(seeds, random);
                for (int i = 0; i < Math.min(3, seeds.size()) && names.size() < requested; i++) {
                    check(cancellation);
                    try { appendNames(names, source.similar(seeds.get(i))); }
                    catch (InterruptedException interrupted) { throw interrupted; }
                    catch (Exception ignored) { }
                }
            }
            return new ArrayList<>(names);
        }

        if (artist == null || artist.trim().isEmpty()) {
            throw new IllegalArgumentException(mode == Mode.VARIED
                    ? "Escribe un artista para Música muy variada"
                    : "Escribe primero un artista en el buscador");
        }
        String anchor = artist.trim();
        addArtist(names, anchor);
        List<String> firstHop = new ArrayList<>();
        try {
            check(cancellation);
            List<String> similar = source.similar(anchor);
            if (similar != null) {
                for (String candidate : similar) {
                    if (candidate == null || candidate.trim().isEmpty()
                            || candidate.trim().equalsIgnoreCase(anchor)) continue;
                    if (!containsArtist(firstHop, candidate)) firstHop.add(candidate.trim());
                }
                // Priorizar coincidencias de Last.fm cercanas al artista original.
                // En VARIED solo mezclamos el tramo menos cercano.
                if (mode == Mode.VARIED && firstHop.size() > 4) {
                    Collections.shuffle(firstHop.subList(4, firstHop.size()), random);
                }
                appendNames(names, firstHop);
            }
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception ignored) {
            // Si falla Last.fm, no introducir artistas de generos ajenos.
        }

        // Last.fm suele aportar solo ocho artistas; con uno o dos temas por
        // cantante no bastarian para sesiones de 20/30 canciones.
        // Ampliar SOLO desde los artistas mas cercanos del primer nivel.
        // No emplear STYLE_SEEDS, que cambiarian el estilo de referencia.
        int target = mode == Mode.RELATED
                ? Math.min(60, (requested + 1) / 2 + 3)
                : Math.min(60, requested + 2);
        int maxExpansion = mode == Mode.RELATED ? 2 : 3;
        for (int i = 0; i < firstHop.size() && i < maxExpansion
                && names.size() < target; i++) {
            check(cancellation);
            try {
                appendNames(names, source.similar(firstHop.get(i)));
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception ignored) {
                // Otro relacionado puede no estar disponible.
            }
        }
        return new ArrayList<>(names);
    }

    private static boolean containsArtist(List<String> names, String candidate) {
        String key = normalize(candidate);
        for (String name : names) {
            if (normalize(name).equals(key)) return true;
        }
        return false;
    }

    private static void addArtist(LinkedHashSet<String> names, String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) return;
        String cleaned = candidate.trim();
        for (String existing : names) {
            if (normalize(existing).equals(normalize(cleaned))) return;
        }
        names.add(cleaned);
    }

    private static void publish(Progress progress, int scanned, int total,
            List<ApiClient.Song> output) {
        if (progress != null)
            progress.update(scanned, total, output.size(), new ArrayList<>(output));
    }

    // Devuelve el numero REAL de nuevas canciones, para respetar el cupo
    // aunque haya resultados duplicados o sin videoId.
    private static int appendUnique(List<ApiClient.Song> output,
            HashSet<String> ids, HashSet<String> titles,
            List<ApiClient.Song> group, int maxAdd, int requested) {
        int added = 0;
        for (ApiClient.Song song : group) {
            if (added >= maxAdd || output.size() >= requested) break;
            if (song == null) continue;
            String id = firstNonEmpty(song.videoId, song.id).trim();
            if (id.isEmpty()) continue;
            String title = normalize(song.title) + "|" +
                    normalize(firstNonEmpty(song.artist, song.channel));
            if (!ids.contains(id) && !titles.contains(title)) {
                ids.add(id);
                titles.add(title);
                output.add(song);
                added++;
            }
        }
        return added;
    }

    private static void check(Cancellation cancellation) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() ||
                (cancellation != null && cancellation.cancelled())) {
            throw new InterruptedException("DJ cancelado");
        }
    }

    private static void appendNames(LinkedHashSet<String> names, List<String> related) {
        if (related == null) return;
        for (String item : related) addArtist(names, item);
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a : b == null ? "" : b;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    /** Consulta al endpoint Last.fm YA EXISTENTE de la Raspberry Pi. */
    public static List<String> getSimilarArtists(String serverUrl, String artist) throws Exception {
        List<String> names = new ArrayList<>();
        if (serverUrl == null || !serverUrl.matches("(?i)^https?://.+")) return names;
        URL url = new URL(serverUrl.replaceAll("/+$", "") +
                "/api/artists/similar?artist=" + URLEncoder.encode(artist, "UTF-8"));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(18000);
            if (connection.getResponseCode() != 200) return names;
            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) content.append(line);
            }
            JSONArray array = new JSONObject(content.toString()).optJSONArray("artists");
            if (array == null) return names;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String name = item.optString("name", "").trim();
                if (!name.isEmpty() && !name.equalsIgnoreCase(artist)) names.add(name);
            }
            return names;
        } finally {
            connection.disconnect();
        }
    }
}
