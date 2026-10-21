package io.freemytv;

import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import java.util.*;

/** Read-only Android TV recommendations published by the installed cinemas. Never reads watch history. */
final class TvCatalog {
    static final String[] PACKAGES={NativeCatalog.KINO,NativeCatalog.OKKO,NativeCatalog.IVI};
    static String app(String pkg) { return NativeCatalog.KINO.equals(pkg)?"kinopoisk":NativeCatalog.OKKO.equals(pkg)?"okko":"ivi"; }
    static String name(String pkg) { return NativeCatalog.KINO.equals(pkg)?"Кинопоиск":NativeCatalog.OKKO.equals(pkg)?"Okko":"Иви"; }
    static List<Recs.Pick> load(Context context) {
        Map<String,List<Recs.Pick>> services=new LinkedHashMap<>();
        for(String pkg:PACKAGES)services.put(pkg,new ArrayList<>());
        Set<String> seen=new HashSet<>();
        String[] columns={"_id","package_name","title","poster_art_uri","poster_art_aspect_ratio","intent_uri","browsable"};
        try(Cursor c=context.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,columns,null,null,"_id ASC")) {
            if(c!=null)while(c.moveToNext()) {
                String pkg=c.getString(1);
                List<Recs.Pick> list=services.get(pkg);
                if(list==null || list.size()>=64 || c.getInt(6)!=1 || c.isNull(4) || c.getInt(4)!=TvContract.PreviewPrograms.ASPECT_RATIO_16_9)continue;
                if(HomeApps.launchIntent(context.getPackageManager(),pkg)==null)continue;
                String title=c.getString(2), image=NativeCatalog.safeImage(c.getString(3)), link=NativeCatalog.safeLink(pkg,c.getString(5));
                if(title==null || title.trim().isEmpty() || title.length()>300 || image==null || link==null || !seen.add(pkg+":"+link))continue;
                Recs.Pick p=new Recs.Pick();
                p.id=c.getInt(0); p.pkg=pkg; p.app=app(pkg); p.title=title; p.link=link; p.meta=name(pkg);
                p.backdrop=largeImage(image); p.banner=true; p.detailed=true;
                Uri u=Uri.parse(link);
                p.kind=NativeCatalog.IVI.equals(pkg)?("movie".equals(u.getHost())?"movie":"tv")
                        :NativeCatalog.OKKO.equals(pkg)?("MOVIE".equals(u.getQueryParameter("type"))?"movie":"tv"):"mixed";
                list.add(p);
            }
        }
        for(String pkg:PACKAGES) {
            List<Recs.Pick> source=services.get(pkg), selected=new ArrayList<>();
            for(String kind:new String[]{"movie","tv"}) {
                int n=0;for(Recs.Pick p:source)if(kind.equals(p.kind) && n++<4)selected.add(p);
            }
            for(Recs.Pick p:source)if(selected.size()<8 && !selected.contains(p))selected.add(p);
            services.put(pkg,selected);
        }
        List<Recs.Pick> result=new ArrayList<>();
        for(int i=0;i<8;i++)for(List<Recs.Pick> list:services.values())if(i<list.size())result.add(list.get(i));
        for(Recs.Pick p:result) p.portrait=NativeCatalog.portrait(context,p.pkg,p.title,p.link);
        return result;
    }
    static String largeImage(String image) {
        Uri u=Uri.parse(image);
        if("static.okko.tv".equals(u.getHost()))return u.buildUpon().clearQuery().appendQueryParameter("size","1280x720").build().toString();
        if("avatars.mds.yandex.net".equals(u.getHost()) || u.getHost().endsWith(".cdn.yandex.net"))
            return image.replaceFirst("/[0-9]+x[0-9]+$","/1280x720");
        return image;
    }
}
