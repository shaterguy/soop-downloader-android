package com.shaterguy.soopdownloader;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import com.shaterguy.soopdownloader.engine.Engine;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.json.*;

public final class DownloadService extends Service {
    public static final String PREFS="downloads", CHANNEL="downloads", ENQUEUE="enqueue", CANCEL="cancel";
    private static final int NOTICE=7;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ArrayDeque<String> queue=new ArrayDeque<>();
    private final Set<String> pending=new HashSet<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;
    private volatile boolean destroyed;
    private String active;
    private int latestStart;
    private long lastNotice;
    static volatile boolean alive;

    public static String normalize(String text) {
        if(text==null || text.length()>16384) throw new IllegalArgumentException("SOOP 영상 주소를 확인해 주세요.");
        Matcher m=Pattern.compile("https?://[^\\s<>\"]+",Pattern.CASE_INSENSITIVE).matcher(text);
        if(!m.find()) throw new IllegalArgumentException("SOOP 영상 주소를 넣어 주세요.");
        String token=m.group();
        if(m.find()) throw new IllegalArgumentException("한 번에 영상 주소 하나를 공유해 주세요.");
        try {
            java.net.URI uri=new java.net.URI(token);
            if(!"https".equalsIgnoreCase(uri.getScheme()) || !"vod.sooplive.com".equalsIgnoreCase(uri.getHost()) || uri.getRawUserInfo()!=null || (uri.getPort()!=-1 && uri.getPort()!=443))
                throw new IllegalArgumentException("SOOP 영상 주소만 지원합니다.");
            Matcher route=Pattern.compile("/(?:player|PLAYER/STATION)/(\\d+)(/catch)?/?",Pattern.CASE_INSENSITIVE).matcher(uri.getRawPath());
            if(!route.matches()) throw new IllegalArgumentException("다시보기 또는 개별 캐치 영상의 공유 주소를 넣어 주세요.");
            return "https://vod.sooplive.com/player/"+route.group(1)+(route.group(2)==null?"":"/catch");
        } catch(java.net.URISyntaxException e){throw new IllegalArgumentException("영상 주소 형식을 확인해 주세요.");}
    }
    @Override public void onCreate() {
        super.onCreate(); alive=true;
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"영상 다운로드",NotificationManager.IMPORTANCE_LOW));
        recover(this);
    }
    static void recover(Context c) {
        SharedPreferences p=c.getSharedPreferences(PREFS,0);
        String partial=p.getString("pendingUri","");
        if(!partial.isEmpty()) {
            Uri uri=Uri.parse(partial);
            try(android.database.Cursor cursor=c.getContentResolver().query(uri,new String[]{MediaStore.Video.Media.IS_PENDING},null,null,null)){
                if(cursor!=null&&cursor.moveToFirst()&&cursor.getInt(0)==1)c.getContentResolver().delete(uri,null,null);
            }catch(Exception ignored){}
            p.edit().putString("pendingUri","").commit();
        }
        if(p.getBoolean("busy",false)) p.edit().putBoolean("busy",false).putString("phase","다운로드가 중단되었습니다. 다시 시도해 주세요.").putString("pendingUri","").putInt("queued",0).apply();
        File cache=new File(c.getCacheDir(),"download");
        File[] files=cache.listFiles(); if(files!=null)for(File f:files)if(f.isFile())f.delete();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        latestStart=startId;
        if(intent==null){stopSelf(startId);return START_NOT_STICKY;}
        if(CANCEL.equals(intent.getAction())){cancelled=true;queue.clear();pending.clear();prefs().edit().putInt("queued",0).apply();if(active==null)stopSelf(startId);return START_NOT_STICKY;}
        String url;
        try {url=normalize(intent.getStringExtra("url"));} catch(Exception e){if(active==null){status(e.getMessage(),0,false);stopSelf(startId);}return START_NOT_STICKY;}
        if(pending.contains(url)) return START_NOT_STICKY;
        startForeground(NOTICE,notification("다운로드 준비 중",0),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        pending.add(url); queue.add(url); prefs().edit().putInt("queued",Math.max(0,queue.size()-(active==null?1:0))).apply();
        if(active==null) next();
        return START_NOT_STICKY;
    }
    private SharedPreferences prefs(){return getSharedPreferences(PREFS,0);}
    private void next(){
        if(destroyed)return;
        active=queue.poll();
        if(active==null){stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(latestStart);return;}
        cancelled=false;String url=active;
        prefs().edit().putString("lastUrl",url).putInt("queued",queue.size()).apply();
        status("최고 화질 확인 중",0,true);
        worker.submit(()->runDownload(url));
    }
    private void runDownload(String url){
        Uri output=null; File file=null; boolean published=false;
        try {
            File dir=new File(getCacheDir(),"download"); if(!dir.exists()&&!dir.mkdirs())throw new IOException("임시 저장 공간을 만들지 못했습니다.");
            file=new File(dir,UUID.randomUUID()+".mp4");
            Engine.Result result=Engine.download(url,file,new Engine.Listener(){
                public void progress(String phase,int percent){status(phase,percent,true);}
                public boolean isCancelled(){return cancelled||Thread.currentThread().isInterrupted();}
            });
            if(cancelled)throw new InterruptedIOException("취소되었습니다.");
            if(!file.isFile()||file.length()==0)throw new IOException("저장할 영상이 없습니다.");
            status("내 기기에 저장 중",99,true);
            String title=result.title==null?"SOOP 영상":result.title;
            String name=title.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]","_").trim();
            if(name.length()>90)name=name.substring(0,90);if(name.isEmpty())name="SOOP";
            name+="_"+System.currentTimeMillis()+".mp4";
            ContentValues v=new ContentValues();v.put(MediaStore.Video.Media.DISPLAY_NAME,name);v.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");v.put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/SOOP Downloader");v.put(MediaStore.Video.Media.IS_PENDING,1);
            output=getContentResolver().insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),v);
            if(output==null)throw new IOException("갤러리 저장 위치를 만들지 못했습니다.");
            prefs().edit().putString("pendingUri",output.toString()).commit();
            try(InputStream in=new FileInputStream(file);OutputStream out=getContentResolver().openOutputStream(output)){
                if(out==null)throw new IOException("저장 공간에 접근하지 못했습니다.");
                byte[] b=new byte[128*1024];int n;while((n=in.read(b))!=-1){if(cancelled)throw new InterruptedIOException("취소되었습니다.");out.write(b,0,n);}
            }
            if(cancelled)throw new InterruptedIOException("취소되었습니다.");
            v.clear();v.put(MediaStore.Video.Media.IS_PENDING,0);
            if(getContentResolver().update(output,v,null,null)!=1)throw new IOException("영상 저장을 확정하지 못했습니다.");
            published=true;
            String quality=result.width>0&&result.height>0?result.width+" × "+result.height:"원본 스트림";
            JSONArray history;
            try{history=new JSONArray(prefs().getString("history","[]"));}catch(JSONException e){history=new JSONArray();}
            JSONObject item=new JSONObject().put("title",title).put("uri",output.toString()).put("quality",quality).put("bytes",file.length()).put("url",url);
            JSONArray updated=new JSONArray().put(item);for(int i=0;i<Math.min(29,history.length());i++)updated.put(history.get(i));
            prefs().edit().putString("history",updated.toString()).putString("pendingUri","").apply();
            status("저장 완료 · "+quality,100,false);
        }catch(Exception e){
            String message=cancelled?"다운로드를 취소했습니다.":e.getMessage();
            if(message==null||message.trim().isEmpty())message="다운로드하지 못했습니다. 네트워크와 영상 주소를 확인해 주세요.";
            // Avoid recording CDN tokens, cookies or signed URLs in UI/history.
            message=message.replaceAll("https?://\\S+","[영상 주소]");
            status(message,0,false);
        }finally{
            if(output!=null&&!published)try{getContentResolver().delete(output,null,null);}catch(Exception ignored){}
            if(output!=null && output.toString().equals(prefs().getString("pendingUri",""))) prefs().edit().putString("pendingUri","").commit();
            if(file!=null)file.delete();
            main.post(()->{pending.remove(url);active=null;if(!destroyed)next();});
        }
    }
    private void status(String text,int percent,boolean busy){
        if(destroyed)return;
        prefs().edit().putString("phase",text).putInt("progress",Math.max(0,Math.min(100,percent))).putBoolean("busy",busy).apply();
        long now=SystemClock.elapsedRealtime();if(now-lastNotice>750||!busy){lastNotice=now;getSystemService(NotificationManager.class).notify(NOTICE,notification(text,percent));}
    }
    private Notification notification(String text,int progress){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent cancel=PendingIntent.getService(this,1,new Intent(this,DownloadService.class).setAction(CANCEL),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(com.shaterguy.soopdownloader.R.drawable.ic_download).setContentTitle("SOOP Downloader").setContentText(text).setContentIntent(content).setOnlyAlertOnce(true).setOngoing(true).setProgress(100,Math.max(0,progress),progress<=0).addAction(new Notification.Action.Builder(null,"취소",cancel).build()).build();
    }
    @Override public void onTimeout(int startId,int fgsType){cancelled=true;queue.clear();status("백그라운드 실행 시간이 끝났습니다. 앱에서 다시 시도해 주세요.",0,false);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    @Override public void onDestroy(){destroyed=true;cancelled=true;queue.clear();pending.clear();worker.shutdownNow();alive=false;super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
