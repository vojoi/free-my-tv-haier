package io.freemytv;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bundled landscape photos: independent of recommendations, accounts and network availability. */
final class NatureGallery {
    static final class Photo {
        final String id, title, credit, file;
        Photo(JSONObject row) throws org.json.JSONException {
            id = row.getString("id");
            title = row.getString(Ui.ru ? "title_ru" : "title");
            credit = row.getString("credit");
            file = row.getString("file");
            if (!file.matches("[a-z-]+\\.jpg")) throw new IllegalArgumentException("Invalid gallery filename");
        }
    }

    private final Context context;
    private final List<Photo> photos = new ArrayList<>();

    NatureGallery(Context c) {
        context = c.getApplicationContext();
        try (InputStream in = context.getAssets().open("gallery/photos.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
            JSONArray rows = new JSONArray(bytes.toString("UTF-8"));
            for (int i = 0; i < rows.length(); i++) photos.add(new Photo(rows.getJSONObject(i)));
            Collections.shuffle(photos);
        } catch (Exception e) {
            AppLog.w("screensaver gallery: " + e.getMessage());
        }
    }

    List<Photo> photos() { return Collections.unmodifiableList(photos); }

    /** Runs on the image worker; the cache is only an adapter for Backdrop's file decoder. */
    File file(Photo photo) throws IOException {
        File dir = new File(context.getCacheDir(), "nature-gallery-v1");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create gallery cache");
        File target = new File(dir, photo.file);
        if (target.isFile() && target.length() > 0) return target;
        File temp = new File(dir, photo.file + ".tmp");
        try (InputStream in = context.getAssets().open("gallery/" + photo.file);
                FileOutputStream out = new FileOutputStream(temp)) {
            byte[] buffer = new byte[32768];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        if (!temp.renameTo(target)) throw new IOException("Cannot save gallery photo");
        return target;
    }
}
