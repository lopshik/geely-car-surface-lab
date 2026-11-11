package dev.geely.carsurfacelab;

import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A small offline cache, shared by the phone UI and the projected car library.
 * The host binder must never receive hundreds of rows or large thumbnails.
 * No broad file-system permission, internet, or background periodic scanning.
 */
public final class MediaCatalog {
    private static final String TAG = "CarVideo.Catalog";
    private static final int LIMIT = 250;

    public static final class Entry {
        public final String uri, title, folder;
        public final long durationMs;
        Entry(String uri, String title, String folder, long durationMs) {
            this.uri = uri;
            this.title = title;
            this.folder = folder;
            this.durationMs = durationMs;
        }
        JSONObject json() throws JSONException {
            return new JSONObject().put("uri", uri).put("title", title)
                    .put("folder", folder).put("duration", durationMs);
        }
        static Entry from(JSONObject j) {
            return new Entry(j.optString("uri"), j.optString("title", "Видео"),
                    j.optString("folder", "Телефон"), j.optLong("duration", 0));
        }
    }

    private MediaCatalog() {}

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("lab", Context.MODE_PRIVATE);
    }

    public static String prettyName(String filename) {
        if (filename == null || filename.trim().isEmpty()) return "Видео";
        String n = filename.replace('_', ' ');
        int dot = n.lastIndexOf('.');
        if (dot > 0 && n.length() - dot < 8) n = n.substring(0, dot);
        return n;
    }

    public static Entry selected(Context c) {
        String uri = prefs(c).getString("video_uri", null);
        if (uri == null || uri.isEmpty()) return null;
        for (Entry e : all(c)) if (uri.equals(e.uri)) return e;
        return new Entry(uri, prettyName(Uri.parse(uri).getLastPathSegment()), "Выбранный файл", 0);
    }

    public static List<Entry> all(Context c) {
        ArrayList<Entry> result = new ArrayList<>();
        String selected = prefs(c).getString("video_uri", null);
        try {
            JSONArray data = new JSONArray(prefs(c).getString("catalog", "[]"));
            for (int i = 0; i < data.length() && result.size() < LIMIT; i++) {
                Entry e = Entry.from(data.getJSONObject(i));
                if (e.uri == null || e.uri.isEmpty()) continue;
                // Put the currently selected movie first, even with huge libraries.
                if (e.uri.equals(selected)) result.add(0, e);
                else result.add(e);
            }
        } catch (JSONException e) {
            Log.w(TAG, "Catalog cache unreadable, rebuild with Scan", e);
        }
        if (selected != null) {
            boolean found = false;
            for (Entry e : result) if (selected.equals(e.uri)) { found = true; break; }
            if (!found) result.add(0, new Entry(selected,
                    prettyName(Uri.parse(selected).getLastPathSegment()), "Выбранный файл", 0));
        }
        return result;
    }

    /** Called on a background worker, not on the UI thread. */
    public static int scan(Context c) {
        JSONArray result = new JSONArray();
        String[] columns = { MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION, MediaStore.Video.Media.RELATIVE_PATH };
        if (Build.VERSION.SDK_INT < 29) {
            columns = new String[]{ MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION };
        }
        try (Cursor cursor = c.getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, columns,
                null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (cursor == null) return 0;
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
            int folderCol = Build.VERSION.SDK_INT >= 29 ?
                    cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH) : -1;
            while (cursor.moveToNext() && result.length() < LIMIT) {
                long id = cursor.getLong(idCol);
                String uri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString();
                String folder = folderCol >= 0 ? cursor.getString(folderCol) : "Телефон";
                if (folder == null) folder = "Телефон";
                Entry e = new Entry(uri,
                        prettyName(cursor.getString(nameCol)), folder,
                        cursor.isNull(durationCol) ? 0 : cursor.getLong(durationCol));
                result.put(e.json());
            }
            prefs(c).edit().putString("catalog", result.toString())
                    .putLong("catalog_scanned", System.currentTimeMillis()).apply();
            return result.length();
        } catch (Exception e) {
            Log.w(TAG, "MediaStore scan failed", e);
            prefs(c).edit().putString("catalog_error",
                    e.getClass().getSimpleName() + ": " + e.getMessage()).apply();
            return -1;
        }
    }

    public static Entry openDocument(Context c, Uri uri) {
        String name = uri.getLastPathSegment();
        try (Cursor cursor = c.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = cursor.getString(idx);
            }
        } catch (Exception ignored) {}
        Entry e = new Entry(uri.toString(), prettyName(name), "Выбранный файл", 0);
        // Keep manually selected SAF files visible on the car even without MediaStore permission.
        List<Entry> entries = all(c);
        JSONArray array = new JSONArray();
        try {
            array.put(e.json());
            for (Entry prev : entries) {
                if (!prev.uri.equals(e.uri) && array.length() < LIMIT) array.put(prev.json());
            }
            prefs(c).edit().putString("catalog", array.toString()).apply();
        } catch (JSONException ignored) {}
        return e;
    }

    public static void choose(Context c, Entry e) {
        // Clearing interruption is intentional user selection, not a gear change.
        prefs(c).edit().putBoolean("resume_interrupted", false)
                .putString("video_uri", e.uri).apply();
    }

    public static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s >= 3600) ? String.format(java.util.Locale.US, "%d:%02d:%02d",
                s / 3600, s / 60 % 60, s % 60)
                : String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    /** Artwork is rendered to an in-memory Bitmap, NOT to the Android Auto video Surface. */
    public static Bitmap poster(Entry e) {
        int w = 200, h = 200;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int hue = Math.floorMod(e.title.hashCode(), 360);
        float[] hsv = {hue, .58f, .48f};
        int top = Color.HSVToColor(hsv);
        hsv[2] = .13f;
        int bottom = Color.HSVToColor(hsv);
        p.setShader(new LinearGradient(0, 0, 200, 200, top, bottom, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(new RectF(0, 0, w, h), 20, 20, p);
        p.setShader(null);
        p.setColor(0xAAFFFFFF);
        Path triangle = new Path();
        triangle.moveTo(84, 60); triangle.lineTo(84, 140);
        triangle.lineTo(144, 100); triangle.close();
        canvas.drawPath(triangle, p);
        p.setColor(0x66FFFFFF);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(14);
        canvas.drawText("CARVIDEO", 100, 180, p);
        return bitmap;
    }
}
