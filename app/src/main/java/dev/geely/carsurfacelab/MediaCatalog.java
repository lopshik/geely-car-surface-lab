package dev.geely.carsurfacelab;

import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Size;
import android.util.LruCache;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
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
import android.provider.DocumentsContract;
import java.util.Comparator;
import java.util.Locale;
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
    private static final ExecutorService POSTER_WORKER = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final LruCache<String,Bitmap> POSTER_CACHE = new LruCache<>(24);
    private static final Set<String> THUMB_PENDING = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> THUMB_FAILED = Collections.synchronizedSet(new HashSet<>());

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

    /** Scan only the user-chosen folder. No broad storage or media permissions. */
    public static int scanFolder(Context context) {
        String treeText = prefs(context).getString("video_folder_uri", null);
        if (treeText == null) {
            prefs(context).edit().putString("catalog_error", "Папка не выбрана").apply();
            return -1;
        }
        ArrayList<Entry> movies = new ArrayList<>();
        try {
            Uri tree = Uri.parse(treeText);
            String folderId = DocumentsContract.getTreeDocumentId(tree);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId);
            String[] projection = { DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE };
            try (Cursor cursor = context.getContentResolver()
                    .query(children, projection, null, null, null)) {
                if (cursor == null) throw new IllegalStateException("Папка недоступна");
                int id = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int name = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mime = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
                while (cursor.moveToNext() && movies.size() < LIMIT) {
                    String docId = cursor.getString(id);
                    String filename = cursor.getString(name);
                    String type = cursor.getString(mime);
                    if (filename == null || docId == null) continue;
                    String lower = filename.toLowerCase(Locale.ROOT);
                    boolean supported = type != null && type.startsWith("video/");
                    if (!supported) supported = lower.endsWith(".mp4") ||
                            lower.endsWith(".mkv") || lower.endsWith(".avi") ||
                            lower.endsWith(".webm") || lower.endsWith(".mov") ||
                            lower.endsWith(".m4v") || lower.endsWith(".3gp") ||
                            lower.endsWith(".ts");
                    if (!supported) continue;
                    String fileUri = DocumentsContract.buildDocumentUriUsingTree(tree,docId).toString();
                    movies.add(new Entry(fileUri,prettyName(filename),"Видео",0));
                }
            }
            movies.sort(Comparator.comparing(e -> e.title.toLowerCase(Locale.ROOT)));
            JSONArray json = new JSONArray();
            for (Entry e : movies) json.put(e.json());
            prefs(context).edit().putString("catalog",json.toString())
                    .putLong("catalog_scanned",System.currentTimeMillis())
                    .remove("catalog_error").apply();
            return movies.size();
        } catch (Exception e) {
            Log.e(TAG,"Selected video directory scan failed",e);
            prefs(context).edit().putString("catalog_error",
                    e.getClass().getSimpleName() + ": " + e.getMessage()).apply();
            return -1;
        }
    }

    public static String folderLabel(Context context) {
        String raw = prefs(context).getString("video_folder_uri",null);
        if (raw == null) return "Выбрать папку";
        try {
            String id = DocumentsContract.getTreeDocumentId(Uri.parse(raw));
            int slash = id.lastIndexOf('/');
            int colon = id.lastIndexOf(':');
            return slash >= 0 ? id.substring(slash+1) :
                    colon >= 0 ? id.substring(colon+1) : id;
        } catch (RuntimeException e) { return "Папка видео"; }
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
            // A fresh MediaStore scan must not silently remove files added with
            // ACTION_OPEN_DOCUMENT. Keep the separately persisted SAF entries.
            JSONArray manual = new JSONArray(prefs(c).getString("manual_catalog", "[]"));
            HashSet<String> scanned = new HashSet<>();
            for (int i = 0; i < result.length(); i++) {
                scanned.add(result.getJSONObject(i).optString("uri"));
            }
            for (int i = 0; i < manual.length() && result.length() < LIMIT; i++) {
                JSONObject j = manual.optJSONObject(i);
                if (j == null) continue;
                String uri = j.optString("uri");
                if (!uri.isEmpty() && scanned.add(uri)) result.put(j);
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
            JSONArray oldManual = new JSONArray(prefs(c).getString("manual_catalog", "[]"));
            JSONArray manual = new JSONArray();
            manual.put(e.json());
            for (int i = 0; i < oldManual.length() && manual.length() < 60; i++) {
                JSONObject m = oldManual.optJSONObject(i);
                if (m != null && !e.uri.equals(m.optString("uri"))) manual.put(m);
            }
            prefs(c).edit().putString("catalog", array.toString())
                    .putString("manual_catalog", manual.toString()).apply();
        } catch (JSONException ignored) {}
        return e;
    }

    public static void choose(Context c, Entry e) {
        // Clearing interruption is intentional user selection, not a gear change.
        prefs(c).edit().putBoolean("resume_interrupted", false)
                .putString("video_uri", e.uri).apply();
    }

    /** Cache-only fast path for Android Auto's binder thread. Never decode video there. */
    public static Bitmap poster(Context c, Entry e) {
        Bitmap cache = POSTER_CACHE.get(e.uri);
        if (cache != null) return cache;
        try {
            File file = posterFile(c,e);
            if (file.isFile()) {
                Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                if (bitmap != null) {
                    POSTER_CACHE.put(e.uri,bitmap);
                    return bitmap;
                }
            }
        } catch (Exception ignored) {}
        return poster(e);
    }

    private static File posterFile(Context c, Entry e) {
        File dir = new File(c.getCacheDir(), "movie-posters");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir,
            PlaybackForegroundService.positionKey(Uri.parse(e.uri)) + ".jpg");
    }

    /** Work happens off the UI thread. No continuous scans or wake locks. */
    public static void preparePoster(Context context, Entry e, Consumer<Bitmap> onReady) {
        Context app = context.getApplicationContext();
        if (e.uri == null || e.uri.isEmpty()) return;
        File file = posterFile(app,e);
        if (file.isFile()) {
            if (onReady != null) UI.post(() -> onReady.accept(poster(app,e)));
            return;
        }
        if (THUMB_FAILED.contains(e.uri) || !THUMB_PENDING.add(e.uri)) return;
        POSTER_WORKER.execute(() -> {
            try {
                Bitmap thumbnail;
                Uri uri = Uri.parse(e.uri);
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    thumbnail = app.getContentResolver().loadThumbnail(uri, new Size(200,200), null);
                } else {
                    thumbnail = MediaStore.Video.Thumbnails.getThumbnail(app.getContentResolver(),
                        android.content.ContentUris.parseId(uri), MediaStore.Video.Thumbnails.MINI_KIND, null);
                }
                if (thumbnail != null) {
                    File out = posterFile(app,e);
                    try (FileOutputStream stream = new FileOutputStream(out)) {
                        thumbnail.compress(Bitmap.CompressFormat.JPEG, 74, stream);
                    }
                    POSTER_CACHE.put(e.uri,thumbnail);
                    if (onReady != null) UI.post(() -> onReady.accept(thumbnail));
                }
            } catch (Exception ex) {
                THUMB_FAILED.add(e.uri);
                Log.d(TAG,"No thumbnail for "+e.title+": "+ex.getClass().getSimpleName());
            } finally {
                THUMB_PENDING.remove(e.uri);
            }
        });
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
