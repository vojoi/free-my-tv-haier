package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Uses only the TV app's exported public search interface. No account tokens or private APIs. */
final class NativeCatalog {
    static final String KINO = "ru.kinopoisk.tv";
    static final String OKKO = "ru.more.play", IVI = "ru.ivi.client";
    private static final ScheduledExecutorService TIMEOUT = Executors.newSingleThreadScheduledExecutor();
    static String normalize(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]", "");
    }
    static String safeLink(String link) {
        return safeLink(KINO, link);
    }
    static String safeLink(String pkg, String link) {
        if (link == null || link.length() > 2048) return null;
        Uri u;
        try { u = link.startsWith("intent:") ? Intent.parseUri(link, Intent.URI_INTENT_SCHEME).getData() : Uri.parse(link); }
        catch (Exception e) { return null; }
        if (u == null || u.isOpaque() || u.getUserInfo() != null || u.getPort() != -1) return null;
        if (OKKO.equals(pkg)) {
            if (!"okko".equals(u.getScheme()) || !"movie".equals(u.getHost())) return null;
            String id=u.getQueryParameter("uid"), type=u.getQueryParameter("type");
            if (id == null || !id.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
                    || !("MOVIE".equals(type) || "SERIAL".equals(type))) return null;
            return new Uri.Builder().scheme("okko").authority("movie").appendQueryParameter("uid",id)
                    .appendQueryParameter("type",type).build().toString();
        }
        if (IVI.equals(pkg)) {
            return "ruiviclient".equals(u.getScheme()) && ("movie".equals(u.getHost()) || "compilation".equals(u.getHost()))
                    && u.getPath() != null && u.getPath().matches("/open/[0-9]{1,12}/?")
                    ? new Uri.Builder().scheme("ruiviclient").authority(u.getHost()).path(u.getPath()).build().toString() : null;
        }
        if (!KINO.equals(pkg) || !"kpatv".equals(u.getScheme()) || !"film".equals(u.getHost())) return null;
        String id = u.getQueryParameter("filmId");
        return "kpatv".equals(u.getScheme()) && "film".equals(u.getHost()) && id != null && id.matches("[a-zA-Z0-9-]{1,80}")
                ? new Uri.Builder().scheme("kpatv").authority("film").appendQueryParameter("filmId", id).build().toString() : null;
    }
    static final class Entry {
        final String title, image, link;
        Entry(String title, String image, String link) { this.title=title; this.image=image; this.link=link; }
    }
    static String safeImage(String image) {
        if (image == null || image.length() > 2048) return null;
        Uri u = Uri.parse(image);
        String host = u.getHost();
        return "https".equals(u.getScheme()) && u.getUserInfo() == null && (u.getPort() == -1 || u.getPort() == 443)
                && host != null && (host.endsWith(".cdn.yandex.net") || host.equals("avatars.mds.yandex.net")
                || host.equals("static.okko.tv") || host.equals("thumbs.dfs.ivi.ru")) ? image : null;
    }
    static Entry find(Context context, String title, String original) throws Exception {
        CancellationSignal cancel = new CancellationSignal();
        ScheduledFuture<?> deadline = TIMEOUT.schedule(cancel::cancel, 8, TimeUnit.SECONDS);
        Entry found = null;
        try (Cursor c = context.getContentResolver().query(
                Uri.parse("content://ru.kinopoisk.tv.search.kinopoisk.tv/search_suggest_query?limit=10"),
                null, null, new String[]{title}, null, cancel)) {
            if (c == null) return null;
            int name = c.getColumnIndex("suggest_text_1"), secondary = c.getColumnIndex("suggest_text_2"),
                    data = c.getColumnIndex("suggest_intent_data"), action = c.getColumnIndex("suggest_intent_action"),
                    image = c.getColumnIndex("suggest_result_card_image");
            if (name < 0 || data < 0 || action < 0 || image < 0) return null;
            String wanted = normalize(title), own = normalize(original);
            while (c.moveToNext()) {
                if (!wanted.equals(normalize(c.getString(name))) || !Intent.ACTION_VIEW.equals(c.getString(action))) continue;
                // Original title disambiguates translated titles when the provider supplies it.
                String other = secondary < 0 ? "" : normalize(c.getString(secondary));
                if (!own.isEmpty() && !own.equals(wanted) && !other.isEmpty() && !own.equals(other)) continue;
                String link = safeLink(c.getString(data));
                String poster = safeImage(c.getString(image));
                if (link == null || poster == null) continue;
                if (found != null && !found.link.equals(link)) return null; // ambiguous remakes: omit
                found = new Entry(c.getString(name), poster, link);
            }
            return found;
        } finally { deadline.cancel(false); }
    }
    static boolean open(Context c, String link) {
        return open(c, KINO, link);
    }
    static boolean open(Context c, String pkg, String link) {
        String safe = safeLink(pkg, link);
        if (safe == null) return false;
        try { c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(safe)).setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; }
        catch (RuntimeException e) { return false; }
    }

    /** Retrieves a portrait only when the service's own content ID matches the banner's ID. */
    static String portrait(Context context, String pkg, String title, String link) {
        String authority = KINO.equals(pkg) ? "ru.kinopoisk.tv.search.kinopoisk.tv/search_suggest_query"
                : IVI.equals(pkg) ? "ru.ivi.client.tv.data.search/search/search_suggest_query" : null;
        if (authority == null) return null;
        CancellationSignal cancel = new CancellationSignal();
        ScheduledFuture<?> deadline = TIMEOUT.schedule(cancel::cancel, 6, TimeUnit.SECONDS);
        try (Cursor c = context.getContentResolver().query(Uri.parse("content://"+authority+"?limit=10"),
                null,null,new String[]{title},null,cancel)) {
            if(c==null) return null;
            int data=c.getColumnIndex("suggest_intent_data"),image=c.getColumnIndex("suggest_result_card_image");
            if(data<0 || image<0) return null;
            while(c.moveToNext()) if(link.equals(safeLink(pkg,c.getString(data)))) return safeImage(c.getString(image));
        } catch(Exception ignored) {} finally { deadline.cancel(false); }
        return null;
    }
}
