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
