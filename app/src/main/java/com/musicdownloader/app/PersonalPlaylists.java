package com.musicdownloader.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

// PERSONAL_PLAYLISTS_FASE1_20261018
// Almacenamiento LOCAL y separado de las listas importadas de Spotify.
public final class PersonalPlaylists {
    private static final String PREFS = "personal_playlists_v1";
    private static final String KEY = "lists";
    private PersonalPlaylists() {}

    public static synchronized JSONArray load(Context ctx) {
        try {
            return new JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, "[]"));
        } catch (Exception ex) {
            android.util.Log.e("PersonalPlaylists", "Lectura", ex);
            return new JSONArray();
        }
    }

    // PERSONAL_EXPORT_REUSE_ORIGINAL_V6
    // Las ediciones se guardan LOCALMENTE; el archivo se actualiza solo al exportar.
    private static boolean save(Context ctx, JSONArray lists) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, lists.toString()).commit();
    }

    public static synchronized String create(Context ctx, String name) throws Exception {
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) throw new Exception("Introduce un nombre");
        JSONArray all = load(ctx);
        JSONObject list = new JSONObject();
        String id = UUID.randomUUID().toString();
        list.put("id", id);
        list.put("name", name);
        list.put("tracks", new JSONArray());
        all.put(list);
        if (!save(ctx, all)) throw new Exception("No se pudo guardar la lista");
        return id;
    }

    // PERSONAL_IMPORT_MY_LISTS_V1: importar un archivo COMO NUEVA LISTA PERSONAL.
    // Transacción única de SharedPreferences: no crea una lista vacía ante un fallo.
    public static synchronized String importNew(Context ctx, String name, JSONArray tracks)
            throws Exception {
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) throw new Exception("Escribe un nombre de lista");
        if (name.length() > 140) throw new Exception("Nombre demasiado largo");
        if (tracks == null || tracks.length() == 0)
            throw new Exception("El archivo no contiene canciones válidas");
        if (tracks.length() > PersonalPlaylistFileParser.MAX_TRACKS)
            throw new Exception("Demasiadas canciones en el archivo");
        JSONArray all = load(ctx);
        JSONObject list = new JSONObject();
        String id = UUID.randomUUID().toString();
        list.put("id", id);
        list.put("name", name);
        list.put("tracks", new JSONArray(tracks.toString()));
        // Sin URL por defecto. Se puede añadir desde Editar lista (V3).
        all.put(list);
        if (!save(ctx, all)) throw new Exception("No se pudo guardar la lista importada");
        return id;
    }

    // PERSONAL_IMPORT_WRITEBACK_DELETE_SAFE_V5
    // Cada archivo importado recuerda su URI original, permiso SAF y formato.
    // Se guarda siempre primero una copia privada del archivo original.
    public static synchronized String importLinked(Context ctx, String name, JSONArray tracks,
            android.net.Uri file, String format) throws Exception {
        if (file == null || !"content".equals(file.getScheme()))
            throw new Exception("Selecciona un archivo mediante el explorador de Android");
        if (!("csv".equals(format) || "txt".equals(format) || "json".equals(format)))
            throw new Exception("El archivo debe ser CSV, TXT o JSON");
        if (tracks == null || tracks.length() == 0)
            throw new Exception("El archivo no contiene canciones");
        if (tracks.length() > PersonalPlaylistFileParser.MAX_TRACKS)
            throw new Exception("Demasiadas canciones");
        name = name == null ? "" : name.trim();
        if (name.isEmpty() || name.length() > 140)
            throw new Exception("Nombre vacío o demasiado largo");
        String id = UUID.randomUUID().toString();
        // Si la copia inicial falla, no importamos una lista aparentemente editable.
        String hash = backupOriginal(ctx, id, file, format);
        JSONArray all = load(ctx);
        JSONObject list = new JSONObject();
        list.put("id", id);
        list.put("name", name);
        list.put("tracks", new JSONArray(tracks.toString()));
        list.put("source_file_uri", file.toString());
        list.put("source_file_ext", format);
        all.put(list);
        if (!save(ctx, all)) throw new Exception("No se pudo guardar la lista");
        if (!ctx.getSharedPreferences("personal_file_hash_v5", Context.MODE_PRIVATE)
                .edit().putString(id, hash).commit())
            throw new Exception("La lista se guardó, pero no se pudo registrar el archivo vinculado");
        return id;
    }

    private static final java.util.concurrent.ExecutorService SOURCE_WRITER =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private static String hexDigest(java.security.MessageDigest digest) {
        byte[] b = digest.digest();
        StringBuilder result = new StringBuilder(b.length * 2);
        for (byte x : b) result.append(String.format(java.util.Locale.ROOT, "%02x", x & 255));
        return result.toString();
    }

    private static String backupOriginal(Context ctx, String id, android.net.Uri uri,
            String ext) throws Exception {
        java.io.File dir = new java.io.File(ctx.getFilesDir(), "personal_original_backups");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("No se puede crear respaldo local");
        java.io.File output = new java.io.File(dir, id + "." + ext);
        java.io.File temp = new java.io.File(dir, id + "." + ext + ".tmp");
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        int count = 0;
        try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri);
                java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
            if (in == null) throw new Exception("No se pudo leer el archivo de origen");
            byte[] buffer = new byte[8192]; int n;
            while ((n=in.read(buffer))!=-1) {
                count += n;
                if (count > 12 * 1024 * 1024)
                    throw new Exception("Archivo demasiado grande para respaldo");
                md.update(buffer,0,n);out.write(buffer,0,n);
            }
            out.getFD().sync();
        } catch (Exception ex) { temp.delete();throw ex; }
        if (!temp.renameTo(output)) {
            temp.delete();throw new Exception("No se pudo guardar respaldo original");
        }
        return hexDigest(md);
    }

    private static String hashFile(Context ctx, android.net.Uri uri) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("Archivo original inaccesible");
            byte[] buffer=new byte[8192];int n,bytes=0;
            while ((n=in.read(buffer))!=-1) {
                bytes+=n;
                if(bytes>12*1024*1024) throw new Exception("Archivo de origen demasiado grande");
                digest.update(buffer,0,n);
            }
        }
        return hexDigest(digest);
    }

    // Para JSON conservar todas las claves de canciones (portadas, IDs, URIs,
    // duración, etc.). El exportador normal sigue como estaba.
    private static String linkedExport(JSONObject snapshot, String ext) throws Exception {
        if (!"json".equals(ext)) return export(snapshot, ext);
        JSONObject payload=new JSONObject();
        payload.put("name", snapshot.optString("name", ""));
        JSONArray tracks=snapshot.optJSONArray("tracks");
        payload.put("tracks", tracks==null ? new JSONArray() : new JSONArray(tracks.toString()));
        return payload.toString(2);
    }

    private static void writeLinked(Context ctx, JSONObject snapshot) throws Exception {
        String id = snapshot.optString("id", "");
        String ext = snapshot.optString("source_file_ext", "");
        String link = snapshot.optString("source_file_uri", "");
        if (id.isEmpty() || link.isEmpty()) return;
        android.net.Uri uri = android.net.Uri.parse(link);
        android.content.SharedPreferences hashPrefs =
                ctx.getSharedPreferences("personal_file_hash_v5", Context.MODE_PRIVATE);
        String expected = hashPrefs.getString(id, "");
        if (expected.isEmpty()) throw new Exception("Falta la referencia del archivo original");
        String existing = hashFile(ctx, uri);
        if (!expected.equals(existing))
            throw new Exception("El archivo cambió fuera de la app: no se ha sobrescrito");
        String body=linkedExport(snapshot, ext);
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // Escritura al URI ORIGINAL, no una exportación con nombre nuevo.
        try (java.io.OutputStream output = ctx.getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new Exception("Archivo no admite escritura");
            output.write(bytes); output.flush();
        }
        java.security.MessageDigest md=java.security.MessageDigest.getInstance("SHA-256");
        md.update(bytes);
        if (!hashPrefs.edit().putString(id, hexDigest(md)).commit())
            throw new Exception("Archivo actualizado pero no se pudo guardar la verificación");
    }

    // Actualiza tras cada edición. Las tareas se ejecutan EN ORDEN en un hilo propio.
    // Si falla el permiso o el documento cambia externamente, se preserva la edición
    // local y se muestra un aviso explícito; jamás se elimina el documento de origen.
    public static void syncLinked(Context ctx, String id, boolean force) {
        JSONObject snapshot=get(ctx,id);
        if (snapshot==null || snapshot.optString("source_file_uri", "").isEmpty()) return;
        Context app=ctx.getApplicationContext();
        SOURCE_WRITER.execute(() -> {
            try {
                writeLinked(app,snapshot);
                android.util.Log.i("PersonalPlaylists", "Archivo personal actualizado: "
                        + snapshot.optString("name", ""));
                if (force) new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        android.widget.Toast.makeText(app,
                            "Archivo original sobrescrito correctamente",
                            android.widget.Toast.LENGTH_LONG).show());
            } catch (Exception problem) {
                android.util.Log.e("PersonalPlaylists", "Archivo original NO actualizado",problem);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        android.widget.Toast.makeText(app,
                            "Guardado en Mis listas, pero NO en el archivo original: "
                            + problem.getMessage(), android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }

    // El importador V1 no guardaba el URI: vincular sin importar ni sustituir canciones.
    public static synchronized void linkExisting(Context ctx, String id,
            android.net.Uri file, String ext) throws Exception {
        if (!("csv".equals(ext) || "txt".equals(ext) || "json".equals(ext)))
            throw new Exception("Selecciona CSV, TXT o JSON");
        JSONArray lists=load(ctx);
        JSONObject selected=null;
        for(int i=0;i<lists.length();i++) {
            JSONObject l=lists.optJSONObject(i);
            if(l!=null && id.equals(l.optString("id"))) { selected=l;break; }
        }
        if(selected==null) throw new Exception("Lista personal no encontrada");
        if(!selected.optString("source_file_uri", "").isEmpty())
            throw new Exception("Esta lista ya tiene un archivo vinculado");
        String hash=backupOriginal(ctx,id,file,ext);
        selected.put("source_file_uri",file.toString());
        selected.put("source_file_ext",ext);
        if (!ctx.getSharedPreferences("personal_file_hash_v5", Context.MODE_PRIVATE)
                .edit().putString(id,hash).commit())
            throw new Exception("No se pudo guardar la huella del archivo");
        if (!save(ctx,lists)) throw new Exception("No se pudo vincular el archivo");
    }

    // Escribir solo si cambió el contenido del archivo, no por metadatos internos.
    private static void syncEditedLinkedFiles(Context ctx, JSONArray oldLists,
            JSONArray newLists) {
        for (int i=0; i<newLists.length(); i++) {
            JSONObject now=newLists.optJSONObject(i);
            if (now==null || now.optString("source_file_uri", "").isEmpty()) continue;
            String id=now.optString("id", "");
            JSONObject old=null;
            for(int j=0;j<oldLists.length();j++) {
                JSONObject candidate=oldLists.optJSONObject(j);
                if(candidate!=null && id.equals(candidate.optString("id"))) {
                    old=candidate; break;
                }
            }
            if (old==null) continue; // nueva importación: NO modificar el original
            try {
                String ext=now.optString("source_file_ext", "");
                if (!linkedExport(now,ext).equals(linkedExport(old,ext)))
                    syncLinked(ctx,id,false);
            } catch (Exception err) {
                android.util.Log.e("PersonalPlaylists", "Comparación de archivo",err);
            }
        }
    }

    public static synchronized void add(Context ctx, String id, JSONArray songs) throws Exception {
        JSONArray all = load(ctx);
        boolean found = false;
        for (int i=0;i<all.length();i++) {
            JSONObject list = all.getJSONObject(i);
            if (!id.equals(list.optString("id"))) continue;
            found = true;
            JSONArray tracks = list.optJSONArray("tracks");
            if (tracks == null) tracks = new JSONArray();
            for (int j=0;j<songs.length();j++) {
                JSONObject song = songs.optJSONObject(j);
                if (song == null || song.optString("title").trim().isEmpty()) continue;
                // Evitar añadir exactamente el mismo archivo/canción dos veces.
                boolean exists = false;
                for (int k=0;k<tracks.length();k++) {
                    JSONObject prev = tracks.optJSONObject(k);
                    if (prev != null && prev.optString("title").equals(song.optString("title"))
                        && prev.optString("artist").equals(song.optString("artist"))
                        && prev.optString("uri").equals(song.optString("uri"))) {
                        exists=true; break;
                    }
                }
                if (!exists) tracks.put(new JSONObject(song.toString()));
            }
            list.put("tracks", tracks);
            break;
        }
        if (!found) throw new Exception("La lista ya no existe");
        if (!save(ctx, all)) throw new Exception("No se pudo guardar");
    }


    // PERSONAL_EDITOR_V2_20261018: modificar exclusivamente listas locales.
    public static synchronized JSONObject get(Context ctx, String id) {
        JSONArray all = load(ctx);
        for (int i=0; i<all.length(); i++) {
            JSONObject item=all.optJSONObject(i);
            if (item!=null && id.equals(item.optString("id"))) {
                try { return new JSONObject(item.toString()); }
                catch(Exception ignored) { return item; }
            }
        }
        return null;
    }

    // PERSONAL_DELETE_LIST_V4_20261018
    // Elimina solamente el registro de una lista PERSONAL del almacenamiento local.
    public static synchronized void deleteList(Context ctx, String id) throws Exception {
        if (id == null || id.trim().isEmpty()) throw new Exception("ID no válido");
        JSONArray previous = load(ctx);
        JSONArray remaining = new JSONArray();
        boolean found = false;
        for (int i = 0; i < previous.length(); i++) {
            JSONObject item = previous.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) {
                found = true;
            } else if (item != null) {
                remaining.put(item);
            }
        }
        if (!found) throw new Exception("Lista no encontrada");
        if (!save(ctx, remaining)) throw new Exception("No se pudo guardar el borrado");
    }

    // SPOTIFY_PERSONAL_URL_V3: el vínculo vive dentro de cada lista local.
    // Al borrar la lista con deleteList() desaparece su URL automáticamente.
    public static synchronized void updateSpotifyUrl(Context ctx, String id, String url)
            throws Exception {
        JSONArray all = load(ctx);
        boolean found = false;
        for (int i = 0; i < all.length(); i++) {
            JSONObject list = all.optJSONObject(i);
            if (list == null || !id.equals(list.optString("id"))) continue;
            found = true;
            String old = list.optString("spotify_url", "");
            if (url == null || url.isEmpty()) {
                list.remove("spotify_url");
                list.remove("spotify_checked");
                list.remove("spotify_auto_sync_ready");
            } else {
                list.put("spotify_url", url);
                if (!url.equals(old)) {
                    list.remove("spotify_checked");
                    list.put("spotify_auto_sync_ready", false);
                }
            }
            break;
        }
        if (!found) throw new Exception("Lista personal no encontrada");
        if (!save(ctx, all)) throw new Exception("No se pudo guardar la URL");
    }

    // Sustitución segura SOLO si la URL no cambió durante la consulta remota.
    // No borra audios del dispositivo ni otros registros de listas.
    // SPOTIFY_CONFIRMAR_RECORTE_V4: por defecto nunca admite lista incompleta.
    public static synchronized JSONObject updateFromSpotify(Context ctx, String id,
            String checkedUrl, JSONArray remoteTracks) throws Exception {
        return updateFromSpotify(ctx, id, checkedUrl, remoteTracks, false);
    }

    public static synchronized JSONObject updateFromSpotify(Context ctx, String id,
            String checkedUrl, JSONArray remoteTracks, boolean userConfirmed) throws Exception {
        JSONArray all = load(ctx);
        if (remoteTracks == null || remoteTracks.length() == 0)
            throw new Exception("Spotify no devolvió canciones");
        for (int i = 0; i < all.length(); i++) {
            JSONObject list = all.optJSONObject(i);
            if (list == null || !id.equals(list.optString("id"))) continue;
            if (!checkedUrl.equals(list.optString("spotify_url", "")))
                throw new Exception("La URL se ha cambiado durante la comprobación");
            JSONArray old = list.optJSONArray("tracks");
            if (old == null) old = new JSONArray();
            // El reproductor incrustado de Spotify puede recortar playlists grandes.
            // Rechazar reducciones sospechosas, y especialmente resultados de 50/100.
            int n = old.length(), m = remoteTracks.length();
            boolean suspicious = m < n && ((m == 50 || m == 100)
                    || (n > 20 && m * 5 < n * 4));
            if (suspicious && !userConfirmed)
                throw new Exception("Spotify devolvió menos canciones. Se protege tu lista.");
            JSONArray updated = new JSONArray();
            boolean[] reused = new boolean[n];
            int added = 0;
            for (int j = 0; j < m; j++) {
                JSONObject remote = remoteTracks.optJSONObject(j);
                if (remote == null || remote.optString("title", "").isEmpty())
                    throw new Exception("Respuesta incompleta de Spotify");
                String key = key(remote);
                JSONObject song = null;
                for (int k = 0; k < n; k++) {
                    JSONObject prev = old.optJSONObject(k);
                    if (!reused[k] && prev != null && key.equals(key(prev))) {
                        reused[k] = true;
                        song = prev; // conservar portadas, URI local, duración y vídeo ya resueltos
                        break;
                    }
                }
                if (song == null) {
                    added++;
                    song = new JSONObject(remote.toString());
                }
                updated.put(song);
            }
            int removed = 0;
            for (boolean keep : reused) if (!keep) removed++;
            boolean reordered = false;
            if (added == 0 && removed == 0) {
                for (int j = 0; j < m; j++) {
                    if (!key(old.optJSONObject(j)).equals(key(updated.optJSONObject(j)))) {
                        reordered = true; break;
                    }
                }
            }
            boolean changed = added != 0 || removed != 0 || reordered;
            if (changed && suspicious && userConfirmed) {
                // Mantener copia anterior aunque se confirme el recorte accidentalmente.
                boolean backupOk = ctx.getSharedPreferences(
                        "personal_spotify_sync_copias", Context.MODE_PRIVATE).edit()
                        .putString("lista_" + id, list.toString()).commit();
                if (!backupOk) throw new Exception(
                        "No se pudo guardar copia previa; se conserva tu lista");
            }
            if (changed) list.put("tracks", updated);
            list.put("spotify_checked", System.currentTimeMillis());
            list.put("spotify_auto_sync_ready", true);
            if (!save(ctx, all)) throw new Exception("No se pudieron guardar los cambios");
            JSONObject result = new JSONObject();
            result.put("changed", changed);
            result.put("added", added);
            result.put("removed", removed);
            result.put("reordered", reordered);
            result.put("count", m);
            return result;
        }
        throw new Exception("Esta lista personal ya no existe");
    }
    private static String key(JSONObject item) {
        if (item == null) return "";
        return item.optString("title", "").trim().toLowerCase(java.util.Locale.ROOT)
                + "|" + item.optString("artist", "").trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static synchronized void rename(Context ctx, String id, String name) throws Exception {
        if (name==null || name.trim().isEmpty()) throw new Exception("Nombre vacío");
        JSONArray all=load(ctx); boolean ok=false;
        for(int i=0;i<all.length();i++) {
            JSONObject item=all.optJSONObject(i);
            if(item!=null && id.equals(item.optString("id"))) {
                item.put("name",name.trim()); ok=true; break;
            }
        }
        if(!ok || !save(ctx,all)) throw new Exception("No se pudo renombrar");
    }

    public static synchronized void removeTrack(Context ctx, String id, int index) throws Exception {
        editTrack(ctx,id,index,0);
    }
    public static synchronized void moveTrack(Context ctx, String id, int index, int direction) throws Exception {
        editTrack(ctx,id,index,direction);
    }
    private static void editTrack(Context ctx, String id, int index, int direction) throws Exception {
        JSONArray all=load(ctx); boolean found=false;
        for(int i=0;i<all.length();i++) {
            JSONObject list=all.optJSONObject(i);
            if(list==null || !id.equals(list.optString("id"))) continue;
            JSONArray old=list.optJSONArray("tracks");
            if(old==null || index<0 || index>=old.length()) throw new Exception("Canción inexistente");
            int target=index+direction;
            if(direction!=0 && (target<0 || target>=old.length())) return;
            JSONArray updated=new JSONArray();
            for(int k=0;k<old.length();k++) {
                if(direction==0 && k==index) continue;
                int source=k;
                if(direction!=0) {
                    if(k==target) source=index;
                    else if(k==index) source=target;
                }
                updated.put(old.get(source));
            }
            list.put("tracks",updated); found=true; break;
        }
        if(!found || !save(ctx,all)) throw new Exception("No se pudo editar la lista");
    }

    // PERSONAL_ARTWORK_DURATION_V7: completar datos ausentes en listas ya creadas.
    public static synchronized void mergeTrackMetadata(Context ctx, String id, int index,
            JSONObject fields) throws Exception {
        JSONArray lists = load(ctx);
        for (int i = 0; i < lists.length(); i++) {
            JSONObject list = lists.optJSONObject(i);
            if (list == null || !id.equals(list.optString("id"))) continue;
            JSONArray tracks = list.optJSONArray("tracks");
            if (tracks == null || index < 0 || index >= tracks.length()) return;
            JSONObject track = tracks.optJSONObject(index);
            if (track == null) return;
            boolean changed = false;
            java.util.Iterator<String> keys = fields.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                boolean empty = "duration".equals(key) ? track.optLong(key, 0L) <= 0
                    : track.optString(key, "").isEmpty();
                if (empty) {
                    track.put(key, fields.get(key));
                    changed = true;
                }
            }
            if (changed && !save(ctx, lists)) throw new Exception("No se pudieron guardar metadatos");
            return;
        }
    }

    public static String export(JSONObject list, String format) throws Exception {
        JSONArray tracks=list.optJSONArray("tracks");
        if (tracks==null) tracks=new JSONArray();
        if ("json".equals(format)) {
            JSONObject payload=new JSONObject();
            payload.put("name",list.optString("name"));
            JSONArray out=new JSONArray();
            for(int i=0;i<tracks.length();i++) {
                JSONObject t=tracks.getJSONObject(i), x=new JSONObject();
                x.put("name",t.optString("title"));
                x.put("artist",t.optString("artist"));
                x.put("album",t.optString("album"));
                if(t.optLong("duration",0)>0) x.put("duration_ms",t.optLong("duration")*1000);
                out.put(x);
            }
            payload.put("tracks",out);
            return payload.toString(2);
        }
        StringBuilder b=new StringBuilder();
        if ("csv".equals(format)) {
            b.append("Track Name,Artist Name,Album Name,Duration_ms\r\n");
            for(int i=0;i<tracks.length();i++) {
                JSONObject t=tracks.getJSONObject(i);
                b.append(csv(t.optString("title"))).append(',')
                    .append(csv(t.optString("artist"))).append(',')
                    .append(csv(t.optString("album"))).append(',');
                if(t.optLong("duration",0)>0) b.append(t.optLong("duration")*1000);
                b.append("\r\n");
            }
        } else if("txt".equals(format)) {
            for(int i=0;i<tracks.length();i++) {
                JSONObject t=tracks.getJSONObject(i);
                String artist=t.optString("artist").replace('\n',' ').replace('\r',' ');
                String title=t.optString("title").replace('\n',' ').replace('\r',' ');
                if(!artist.isEmpty()) b.append(artist).append(" - ");
                b.append(title).append('\n');
            }
        } else throw new Exception("Formato desconocido");
        return b.toString();
    }
    private static String csv(String text) {
        if(text==null) text="";
        return "\""+text.replace("\"","\"\"")+"\"";
    }
}
