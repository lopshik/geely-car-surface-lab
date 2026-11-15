package dev.geely.carsurfacelab;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IPTV directory cache, intentionally separate from local MediaCatalog.
 * Only user-supplied HTTP(S) playlists are fetched; no channels are bundled.
 * Refresh on use (max once per 6 hours) and explicit "Обновить" on phone.
 * The last successful parsed playlist is NEVER cleared by network failure.
 */
public final class TvCatalog {
    public static final long REFRESH_INTERVAL_MS = 6L*60L*60L*1000L;
    private static final int MAX_BYTES = 2_000_000;
    private static final int MAX_CHANNELS = 1500;
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_UI_TEXT = 120;
    private static final String PREFS = "lab";
    private static final String TAG = "CarVideo.TV";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([\\w-]+)=\"([^\"]*)\"");
    private static volatile List<Channel> memoryCache;
    private static volatile String cachedJson;
    private static volatile boolean fetching;

    private TvCatalog(){}

    public static final class Channel {
        public final String id,name,group,url,logo,userAgent,referer;
        public Channel(String id,String name,String group,String url,String logo,
                       String userAgent,String referer) {
            this.id=id;this.name=name;this.group=group;this.url=url;this.logo=logo;
            this.userAgent=userAgent;this.referer=referer;
        }
        JSONObject toJson() throws Exception {
            return new JSONObject().put("id",id).put("name",name).put("group",group)
                    .put("url",url).put("logo",logo).put("ua",userAgent)
                    .put("referer",referer);
        }
        static Channel fromJson(JSONObject o) {
            return new Channel(o.optString("id"),o.optString("name"),
                    o.optString("group","Другие"),o.optString("url"),
                    o.optString("logo"),o.optString("ua"),o.optString("referer"));
        }
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }

    public static boolean isTvSelected(Context c) {
        return "tv".equals(prefs(c).getString("video_kind","file"));
    }

    public static String sourceUrl(Context c) {
        return prefs(c).getString("tv_source_url","");
    }

    public static boolean validHttpUrl(String raw) {
        if(raw==null || raw.length()>4096 || raw.length()<8) return false;
        try {
            Uri parsed=Uri.parse(raw.trim());
            String scheme=parsed.getScheme();
            return ("http".equalsIgnoreCase(scheme) ||
                    "https".equalsIgnoreCase(scheme)) &&
                    parsed.getHost()!=null && !parsed.getHost().isEmpty();
        } catch(Exception e) {return false;}
    }

    public static boolean configure(Context c,String raw) {
        String url=raw==null?"":raw.trim();
        if(!validHttpUrl(url))return false;
        String old=sourceUrl(c);
        SharedPreferences.Editor e=prefs(c).edit()
                .putString("tv_source_url",url)
                .putLong("tv_last_success",0L)
                .remove("tv_error");
        if(!old.equals(url)){
            e.putString("tv_catalog","[]");
            cachedJson=null;
            memoryCache=null;
            e.remove("tv_favorites");
        }
        e.apply();
        return true;
    }

    public static List<Channel> all(Context c) {
        String json=prefs(c).getString("tv_catalog","[]");
        List<Channel> cached=memoryCache;
        if(cached!=null && json.equals(cachedJson)) return cached;
        List<Channel> result=new ArrayList<>();
        try {
            JSONArray array=new JSONArray(json);
            for(int i=0;i<Math.min(array.length(),MAX_CHANNELS);i++) {
                Channel item=Channel.fromJson(array.optJSONObject(i));
                if(validHttpUrl(item.url))result.add(item);
            }
        } catch(Exception e) {
            Log.w(TAG,"Local channel directory invalid",e);
        }
        List<Channel> resultImmutable=Collections.unmodifiableList(result);
        cachedJson=json;
        memoryCache=resultImmutable;
        return resultImmutable;
    }

    public static List<String> groups(Context c) {
        List<String> names=new ArrayList<>();
        for(Channel item:all(c)) {
            if(!names.contains(item.group))names.add(item.group);
        }
        Collections.sort(names,String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static List<Channel> inGroup(Context c,String group) {
        List<Channel> result=new ArrayList<>();
        Set<String> favorites=favorites(c);
        for(Channel item:all(c)) {
            if(("★ Избранное".equals(group)&&favorites.contains(item.id)) ||
                    item.group.equals(group)) result.add(item);
        }
        return result;
    }

    private static Set<String> favorites(Context c) {
        Set<String> result=new HashSet<>();
        try {
            JSONArray array=new JSONArray(prefs(c).getString("tv_favorites","[]"));
            for(int i=0;i<array.length();i++)result.add(array.optString(i));
        } catch(Exception ignored){}
        return result;
    }

    public static boolean favorite(Context c,Channel ch) {
        return favorites(c).contains(ch.id);
    }

    public static void toggleFavorite(Context c,Channel ch) {
        Set<String> set=favorites(c);
        if(!set.remove(ch.id))set.add(ch.id);
        JSONArray list=new JSONArray();
        for(String id:set)list.put(id);
        prefs(c).edit().putString("tv_favorites",list.toString()).apply();
    }

    public static void select(Context c,Channel channel) {
        if(!validHttpUrl(channel.url))return;
        prefs(c).edit()
                .putString("video_kind","tv")
                .putString("tv_channel_id",channel.id)
                .putString("tv_channel_name",channel.name)
                .putBoolean("resume_interrupted",false)
                .putString("video_uri",channel.url).apply();
    }

    /** Prefer a current channel URL after the provider rotates stream links. */
    public static String resolvedSelectedUrl(Context c,String fallback) {
        String id=prefs(c).getString("tv_channel_id","");
        if(id.isEmpty())return fallback;
        for(Channel ch:all(c))if(ch.id.equals(id))return ch.url;
        return fallback;
    }

    public static Channel adjacent(Context c,int direction) {
        String current=prefs(c).getString("tv_channel_id","");
        List<Channel> all=all(c);
        if(all.isEmpty())return null;
        for(int i=0;i<all.size();i++) {
            if(all.get(i).id.equals(current)) {
                return all.get((i+direction+all.size())%all.size());
            }
        }
        return all.get(0);
    }

    public static String status(Context c) {
        SharedPreferences p=prefs(c);
        long last=p.getLong("tv_last_success",0L);
        String stamp=last==0?"не обновлялся":
                android.text.format.DateFormat.format("dd.MM HH:mm",last).toString();
        String error=p.getString("tv_error","");
        return all(c).size()+" каналов · "+stamp+
                (error.isEmpty()?"":" · Обновление не удалось (сохранён старый список)");
    }

    public static void refreshIfStale(Context context,Consumer<String> callback) {
        SharedPreferences p=prefs(context);
        if(System.currentTimeMillis()-p.getLong("tv_last_success",0L) <
                REFRESH_INTERVAL_MS && !all(context).isEmpty()) {
            if(callback!=null)UI.post(()->callback.accept(status(context)));
            return;
        }
        refresh(context,callback);
    }

    public static void refresh(Context context,Consumer<String> callback) {
        Context app=context.getApplicationContext();
        if(fetching){
            if(callback!=null)UI.post(()->callback.accept("Обновление уже выполняется"));
            return;
        }
        String source=sourceUrl(app);
        if(!validHttpUrl(source)){
            if(callback!=null)UI.post(()->callback.accept("Сначала введи URL M3U"));
            return;
        }
        fetching=true;
        WORKER.execute(()-> {
            String result;
            try {
                String content=fetchText(source);
                List<Channel> channels=parsePlaylist(content,source);
                if(channels.isEmpty())throw new IllegalArgumentException(
                        "Нет поддерживаемых HTTP(S) телеканалов");
                JSONArray json=new JSONArray();
                for(Channel channel:channels)json.put(channel.toJson());
                String raw=json.toString();
                // A saved URL may be changed on the phone while a fetch is in-flight:
                // never overwrite the new playlist with an old source response.
                if(!source.equals(sourceUrl(app))) {
                    result="Источник изменён; повтори обновление";
                } else {
                    prefs(app).edit().putString("tv_catalog",raw)
                            .putLong("tv_last_success",System.currentTimeMillis())
                            .remove("tv_error").apply();
                    cachedJson=raw;
                    memoryCache=Collections.unmodifiableList(channels);
                    result=channels.size()+" телеканалов обновлено";
                }
            } catch(Exception e) {
                // No network URI, token, password or entire response in logs.
                String type=e.getClass().getSimpleName();
                Log.w(TAG,"Playlist refresh failed: "+type);
                prefs(app).edit().putString("tv_error",type).apply();
                result="Не удалось обновить ("+type+"). Старый список сохранён.";
            } finally {
                fetching=false;
            }
            String message=result;
            if(callback!=null)UI.post(()->callback.accept(message));
        });
    }

    private static String fetchText(String source) throws Exception {
        URL url=new URL(source);
        for(int redirects=0;redirects<=MAX_REDIRECTS;redirects++){
            HttpURLConnection connection=(HttpURLConnection)url.openConnection();
            try{
                connection.setConnectTimeout(9000);
                connection.setReadTimeout(12000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept","audio/x-mpegurl,application/vnd.apple.mpegurl,text/plain,*/*");
                connection.setRequestProperty("User-Agent","CarVideo/1.1 (Android)");
                int status=connection.getResponseCode();
                if(status==301||status==302||status==303||status==307||status==308) {
                    String target=connection.getHeaderField("Location");
                    if(target==null)throw new IllegalStateException("Empty redirect");
                    url=new URL(url,target);
                    if(!validHttpUrl(url.toString()))throw new IllegalArgumentException("Bad redirect");
                    continue;
                }
                if(status!=200)throw new IllegalStateException("HTTP "+status);
                ByteArrayOutputStream data=new ByteArrayOutputStream();
                try(InputStream in=connection.getInputStream()) {
                    byte[] buf=new byte[8192];
                    for(int len;(len=in.read(buf))!=-1;){
                        if(data.size()+len>MAX_BYTES)
                            throw new IllegalStateException("Playlist exceeds 2 MB");
                        data.write(buf,0,len);
                    }
                }
                return new String(data.toByteArray(),StandardCharsets.UTF_8);
            }finally{connection.disconnect();}
        }
        throw new IllegalStateException("Too many redirects");
    }

    private static String attribute(String line,String key) {
        Matcher m=ATTRIBUTE.matcher(line);
        while(m.find())if(key.equalsIgnoreCase(m.group(1)))return m.group(2);
        return "";
    }

    private static String shortText(String raw,String fallback){
        String s=raw==null?"":raw.replace('\n',' ').replace('\r',' ').trim();
        if(s.isEmpty())s=fallback;
        return s.length()>MAX_UI_TEXT?s.substring(0,MAX_UI_TEXT):s;
    }

    private static String stableId(String id,String group,String name){
        String input=!id.isEmpty()?id:(group+"|"+name);
        try{
            byte[] hash=MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder("ch");
            for(int i=0;i<10;i++)out.append(String.format(Locale.US,"%02x",hash[i]&255));
            return out.toString();
        }catch(Exception ignored){return String.valueOf(input.hashCode());}
    }

    public static List<Channel> parsePlaylist(String body,String base) throws Exception {
        if(body==null||body.length()>MAX_BYTES)
            throw new IllegalArgumentException("Invalid playlist size");
        body=body.replace("\uFEFF","");
        if(!body.trim().startsWith("#EXTM3U"))
            throw new IllegalArgumentException("Not an extended M3U playlist");
        if(body.contains("#EXT-X-STREAM-INF"))
            throw new IllegalArgumentException("This is an HLS stream, not a channel list");
        List<Channel> result=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        String name=null,group="Другие",id="",logo="",ua="",referer="";
        int lines=0;
        for(String raw:body.split("\n")) {
            if(++lines>15000)throw new IllegalStateException("Too many playlist lines");
            String line=raw.trim();
            if(line.isEmpty())continue;
            if(line.startsWith("#EXTINF:")) {
                int comma=line.indexOf(',');
                name=comma>=0?shortText(line.substring(comma+1),"Канал"):"Канал";
                group=shortText(attribute(line,"group-title"),"Другие");
                id=attribute(line,"tvg-id");
                logo=attribute(line,"tvg-logo");
                ua="";referer="";
            }else if(line.startsWith("#EXTGRP:") && name!=null){
                group=shortText(line.substring(8),"Другие");
            }else if(line.startsWith("#EXTVLCOPT:http-user-agent=")){
                ua=line.substring("#EXTVLCOPT:http-user-agent=".length()).trim();
            }else if(line.startsWith("#EXTVLCOPT:http-referrer=")){
                referer=line.substring("#EXTVLCOPT:http-referrer=".length()).trim();
            }else if(line.charAt(0)!='#' && name!=null){
                String resolved=new URL(new URL(base),line).toString();
                if(validHttpUrl(resolved)){
                    String stable=stableId(id,group,name);
                    if(seen.add(stable)){
                        result.add(new Channel(stable,name,group,resolved,logo,
                                ua.length()>250?"":ua,referer.length()>500?"":referer));
                    }
                }
                name=null; group="Другие"; id=""; logo=""; ua=""; referer="";
                if(result.size()>=MAX_CHANNELS)break;
            }
        }
        return result;
    }
}
